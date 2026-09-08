package io.vanillabp.cockpit.camunda8;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * What one listener job of one Camunda 8 cluster means to the Business Cockpit.
 * <p>
 * The handler does two things and nothing else: it turns the job into the identifiers the
 * cockpit addresses a task or a workflow by, and it writes one outbox entry. Reading what the
 * cluster knows about the task and asking the application for the business details happens
 * later, while that entry is dispatched - a listener job holds the transition it belongs to
 * open for as long as this method runs, and a report which talked to a cockpit server here
 * would hold a user task from appearing for as long as that server takes.
 * <p>
 * <b>The job is completed after the entry was committed</b>, never before. The entry gets a
 * transaction of its own ({@link EventTransaction#NEW}): a worker thread of the Camunda client
 * carries none, and there is nothing of the cluster's work to join - the cluster commits the
 * transition when the completion arrives, which is after this method returned.
 * <p>
 * A failure fails the job, and because these listeners carry no retries that raises an incident
 * rather than repeating quietly. That is deliberate and it is what Version 1 did: a report
 * which cannot be written is a defect somebody has to see, and the alternative - completing the
 * job anyway - would let the workflow run on while the cockpit silently loses the event.
 */
public class Camunda8CockpitJobHandler implements JobHandler {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitJobHandler.class);

  private final Camunda8Scope scope;

  private final String workflowModuleId;

  private final Camunda8CockpitDeployments deployments;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * @param scope The cluster this worker listens to
   * @param workflowModuleId The workflow module the worker was opened for
   * @param deployments What this extension wired, to translate the job's identifiers back
   * @param publisher Where an observed event is reported, asked for per event rather than up
   *          front: the workers are opened while the application is still starting
   */
  public Camunda8CockpitJobHandler(
      final Camunda8Scope scope,
      final String workflowModuleId,
      final Camunda8CockpitDeployments deployments,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.scope = scope;
    this.workflowModuleId = workflowModuleId;
    this.deployments = deployments;
    this.publisher = publisher;

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    try {
      report(job);
      client.newCompleteCommand(job.getKey()).send().join();
    } catch (final Exception e) {
      logger
          .warn(
              "Camunda8[{}]: the Business Cockpit could not report the {} job '{}' (type '{}') of workflow module '{}' - failing the job, which raises an incident because these listeners carry no retries",
              scope.adapterId(), job.getKind(), job.getKey(), job.getType(), workflowModuleId, e);
      client
          .newFailCommand(job.getKey())
          .retries(0)
          .errorMessage(Camunda8Errors.incidentMessage(e))
          .send()
          .join();
    }

  }

  /**
   * Writes the outbox entry of one job, or says why there is nothing to report.
   */
  private void report(
      final ActivatedJob job) {

    final var wired = deployments
        .listenerOf(workflowModuleId, job.getBpmnProcessId(), job.getType());
    if (wired.isEmpty()) {
      // a job type of this extension which this workflow module did not wire: another
      // application deployed a model of its own under the same identifiers, and its
      // workflows are none of this module's business
      logger
          .debug(
              "Camunda8[{}]: not reporting job '{}' of type '{}': workflow module '{}' wired no such listener for BPMN process '{}'",
              scope.adapterId(), job.getKey(), job.getType(), workflowModuleId, job.getBpmnProcessId());
      return;
    }
    final var listener = wired.get();

    final var workflowAggregateId = job.getVariablesAsMap().get(listener.aggregateIdName());
    if (workflowAggregateId == null) {
      throw new IllegalStateException(
          """
              The Business Cockpit listener job '%s' (type '%s') of BPMN process '%s' in workflow \
              module '%s' carries no value for the workflow aggregate's id variable '%s'! Every \
              report the cockpit gets names the business case it is about, so there is nothing to \
              report without it. A workflow started outside VanillaBP has to carry that variable \
              like any other one."""
              .formatted(
                  job.getKey(), job.getType(), listener.bpmnProcessId(), workflowModuleId,
                  listener.aggregateIdName()));
    }

    if (job.getKind() == JobKind.TASK_LISTENER) {
      reportUserTask(job, listener, String.valueOf(workflowAggregateId));
      return;
    }
    reportWorkflow(job, listener, String.valueOf(workflowAggregateId));

  }

  private void reportUserTask(
      final ActivatedJob job,
      final WiredListener listener,
      final String workflowAggregateId) {

    final var userTaskKey = job.getUserTask() != null
        ? String.valueOf(job.getUserTask().getUserTaskKey())
        : String.valueOf(job.getKey());
    final var taskDefinition = scope
        .plainTaskDefinitionOf(
            workflowModuleId, listener.bpmnProcessId(),
            Camunda8CockpitListeners.identifierOf(job.getType()));

    publisher
        .get()
        .publishUserTaskEvent(
            new UserTaskReference(
                scope.adapterId(), workflowModuleId, listener.bpmnProcessId(), workflowAggregateId, workflowIdOf(
                    job), userTaskKey, taskDefinition, job.getElementId()),
            userTaskKindOf(job), String.valueOf(job.getKey()), OffsetDateTime.now(),
            EventTransaction.NEW);

  }

  private void reportWorkflow(
      final ActivatedJob job,
      final WiredListener listener,
      final String workflowAggregateId) {

    // the cockpit shows business cases, and a called process is a step of one rather than a
    // case of its own - see decision 3 in the repository's DECISIONS.md
    if (!isRootWorkflow(job)) {
      logger
          .debug(
              "Camunda8[{}]: not reporting the {} of process instance {}: it is a called process of workflow {}, which is the business case",
              scope.adapterId(), job.getListenerEventType(), job.getProcessInstanceKey(), job
                  .getRootProcessInstanceKey());
      return;
    }

    publisher
        .get()
        .publishWorkflowEvent(
            new WorkflowReference(
                scope.adapterId(), workflowModuleId, listener.bpmnProcessId(), workflowAggregateId, workflowIdOf(job)),
            workflowKindOf(job, listener), String.valueOf(job.getKey()), OffsetDateTime.now(),
            EventTransaction.NEW);

  }

  /**
   * What happened to the user task, as the cockpit distinguishes it. The three listeners this
   * extension adds report the three transitions a task goes through; a cluster which delivers
   * another one (a task somebody assigned, say) reports a change of the task and nothing more
   * specific.
   */
  private static UserTaskEventKind userTaskKindOf(
      final ActivatedJob job) {

    if (job.getListenerEventType() == ListenerEventType.CREATING) {
      return UserTaskEventKind.CREATED;
    }
    if (job.getListenerEventType() == ListenerEventType.CANCELING) {
      return UserTaskEventKind.CANCELED;
    }
    if (job.getListenerEventType() == ListenerEventType.COMPLETING) {
      return UserTaskEventKind.COMPLETED;
    }
    return UserTaskEventKind.UPDATED;

  }

  /**
   * What happened to the workflow. Both of this extension's execution listeners are
   * <code>end</code> listeners and they are told apart by what they sit on: the process itself
   * ends the workflow, a start event begins it.
   * <p>
   * A workflow which was terminated rather than finished is not reported as such, because
   * Camunda 8 has no listener for it before 8.10 - see decision 3 in the repository's
   * DECISIONS.md.
   */
  private static WorkflowEventKind workflowKindOf(
      final ActivatedJob job,
      final WiredListener listener) {

    if (job.getListenerEventType() != ListenerEventType.END) {
      return WorkflowEventKind.UPDATED;
    }
    return listener.scopedBpmnProcessId().equals(job.getElementId())
        ? WorkflowEventKind.COMPLETED
        : WorkflowEventKind.CREATED;

  }

  /**
   * The instance a business case is: an element of a called process belongs to the workflow its
   * whole hierarchy hangs below.
   */
  private static String workflowIdOf(
      final ActivatedJob job) {

    return String
        .valueOf(
            job.getRootProcessInstanceKey() == null
                ? job.getProcessInstanceKey()
                : job.getRootProcessInstanceKey());

  }

  private static boolean isRootWorkflow(
      final ActivatedJob job) {

    return (job.getRootProcessInstanceKey() == null) || (job.getRootProcessInstanceKey() == job
        .getProcessInstanceKey());

  }

}
