package io.vanillabp.cockpit.camunda8;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.wiring.Camunda8ListenerJobs;
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
 * The answer to the cluster follows the adapter's own protocol for a listener job: the job is
 * registered with the drain of its workflow module, so a shutdown waits for this handler instead
 * of closing the client under it; a rejection the cluster sent because it is busy is repeated
 * rather than turned into a failure; and work which a shutdown cut off is left to its lock, so
 * the next instance of the application gets the listener once that lock expires.
 * <p>
 * Every other failure fails the job with no retry left, which raises an incident at once rather
 * than repeating quietly. That is deliberate and it is what Version 1 did - see decision 5 in the
 * repository's DECISIONS.md.
 */
public class Camunda8CockpitJobHandler implements JobHandler {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitJobHandler.class);

  /**
   * What a message about one of these jobs calls it, so an operator reading a shutdown can tell
   * this extension's listeners from the adapter's own deliveries.
   */
  private static final String LISTENER_KIND = "Business Cockpit listener";

  private final Camunda8Clients.Cluster cluster;

  private final String workflowModuleId;

  private final Camunda8CockpitDeployments deployments;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * @param cluster The cluster this worker listens to
   * @param workflowModuleId The workflow module the worker was opened for
   * @param deployments What this extension wired, to translate the job's identifiers back
   * @param publisher Where an observed event is reported, asked for per event rather than up
   *          front: the workers are opened while the application is still starting
   */
  public Camunda8CockpitJobHandler(
      final Camunda8Clients.Cluster cluster,
      final String workflowModuleId,
      final Camunda8CockpitDeployments deployments,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.cluster = cluster;
    this.workflowModuleId = workflowModuleId;
    this.deployments = deployments;
    this.publisher = publisher;

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    Camunda8ListenerJobs
        .completeOrFail(
            adapterId(),
            client,
            job,
            // asked per job rather than remembered when the worker opened: a workflow module
            // which starts again is given a drain of its own, and a handler holding the drain
            // of the run before would leave every job of the new run to its lock
            cluster.factory().drainOf(workflowModuleId),
            LISTENER_KIND,
            Camunda8CockpitListeners.identifierOf(job.getType()),
            job.getBpmnProcessId(),
            // the listeners of this extension are modelled with no retry at all, so failing
            // one raises the incident straight away - see decision 5 in the repository's
            // DECISIONS.md
            () -> Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT,
            () -> {
              report(job);
              // the completion carries no variables: this listener observes a transition and
              // changes nothing about the workflow, and a task listener completed with a
              // payload is refused by the cluster
              return Map.of();
            });

  }

  /**
   * @return The configured adapter id whose cluster this handler serves
   */
  private String adapterId() {

    return cluster.scope().adapterId();

  }

  /**
   * Writes the outbox entry of one job, or says why there is nothing to report.
   */
  private void report(
      final ActivatedJob job) {

    final var wired = deployments
        .listenerOf(adapterId(), workflowModuleId, job.getBpmnProcessId(), job.getType());
    if (wired.isEmpty()) {
      // a job type of this extension which this workflow module did not wire. Another
      // application deployed a model of its own under the same identifiers, and its workflows
      // are none of this module's business - but that is a guess about somebody else's
      // deployment, and the other reading is that this module's own model was deployed by a
      // version of the application which wired more than the running one does, in which case
      // the cockpit is quietly missing events
      logger
          .warn(
              "Camunda8[{}]: the Business Cockpit does not report job '{}' of type '{}' for BPMN process '{}': workflow module '{}' wired no listener of that type for that process. The job is completed. Where that process is one of this application's, its deployed model carries a listener this version no longer adds",
              adapterId(), job.getKey(), job.getType(), job.getBpmnProcessId(), workflowModuleId);
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
    if (job.getKind() == JobKind.EXECUTION_LISTENER) {
      reportWorkflow(job, listener, String.valueOf(workflowAggregateId));
      return;
    }
    // this extension adds task listeners and execution listeners and nothing else, so a job of
    // a third kind carrying one of its job types is a model nobody here understands. Guessing
    // what it means would report something the cockpit then shows; failing the job says it
    throw new IllegalStateException(
        """
            The Business Cockpit received the %s job '%s' (type '%s') of BPMN process '%s' in \
            workflow module '%s'! This extension adds task listeners and execution listeners \
            only, so a job of another kind carrying one of its types comes from a model it did \
            not write - check which listeners the deployed model of that process carries."""
            .formatted(
                job.getKind(), job.getKey(), job.getType(), listener.bpmnProcessId(),
                workflowModuleId));

  }

  private void reportUserTask(
      final ActivatedJob job,
      final WiredListener listener,
      final String workflowAggregateId) {

    final var userTaskKey = job.getUserTask() != null
        ? String.valueOf(job.getUserTask().getUserTaskKey())
        : String.valueOf(job.getKey());
    final var taskDefinition = cluster
        .scope()
        .plainTaskDefinitionOf(
            workflowModuleId, listener.bpmnProcessId(),
            Camunda8CockpitListeners.identifierOf(job.getType()));

    final var kind = userTaskKindOf(job);
    final var written = publisher
        .get()
        .publishUserTaskEvent(
            new UserTaskReference(
                adapterId(), workflowModuleId, listener.bpmnProcessId(), workflowAggregateId, workflowIdOf(
                    job), userTaskKey, taskDefinition, job.getElementId()),
            // the worker's clock, because a listener job carries no time of its own: it is
            // handed out while the transition it gates waits, and what the cluster records
            // about that transition is written by the exporter afterwards
            kind, String.valueOf(job.getKey()), OffsetDateTime.now(),
            EventTransaction.NEW);
    if (!written) {
      logger
          .debug(
              "Camunda8[{}]: the {} of user task '{}' (workflow aggregate '{}' of BPMN process '{}') {}",
              adapterId(), kind, userTaskKey, workflowAggregateId, listener.bpmnProcessId(),
              publisher.get().reportsUserTasks()
                  ? "collapsed into the report waiting to be dispatched"
                  : "was not reported: this application reports no user tasks");
    }

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
              adapterId(), job.getListenerEventType(), job.getProcessInstanceKey(), job
                  .getRootProcessInstanceKey());
      return;
    }

    final var kind = workflowKindOf(job, listener);
    final var written = publisher
        .get()
        .publishWorkflowEvent(
            new WorkflowReference(
                adapterId(), workflowModuleId, listener.bpmnProcessId(), workflowAggregateId, workflowIdOf(job)),
            // the worker's clock, for the reason given where a user task is reported
            kind, String.valueOf(job.getKey()), OffsetDateTime.now(), EventTransaction.NEW);
    if (!written) {
      logger
          .debug(
              "Camunda8[{}]: the {} of workflow '{}' (workflow aggregate '{}' of BPMN process '{}') {}",
              adapterId(), kind, workflowIdOf(job), workflowAggregateId,
              listener.bpmnProcessId(), publisher.get().reportsWorkflows()
                  ? "collapsed into the report waiting to be dispatched"
                  : "was not reported: this application reports no workflows");
    }

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
