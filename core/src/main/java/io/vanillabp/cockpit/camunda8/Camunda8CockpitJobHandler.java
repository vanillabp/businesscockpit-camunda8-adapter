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
import io.vanillabp.camunda8.wiring.Camunda8CancelListeners;
import io.vanillabp.camunda8.wiring.Camunda8ListenerJobs;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * What one listener job of one Camunda 8 cluster means to the Business Cockpit.
 * <p>
 * The handler turns the job into the identifiers the cockpit addresses a task or a workflow by,
 * reads off the job what the cluster says about it, and writes one outbox entry carrying the
 * finished report. The cockpit builds that report here, which means the application's details
 * provider runs here too. What does NOT happen here is talking to the cockpit server: a listener
 * job holds the transition it belongs to open for as long as this method runs, and the entry is
 * sent afterwards by the outbox.
 * <p>
 * Everything a report needs about the task is on the job. The cluster hands out the assignee,
 * the candidates and the two dates with the job, the version of the deployed process comes with
 * it, and the two BPMN names were read out of the model while this extension wired it
 * ({@link Camunda8CockpitDeployments.WiredListener}). The cluster's searchable storage is not
 * asked, and it could not answer: it is written by an exporter which runs behind the engine, so
 * the event this job reports has not reached it while the job is being served. What the storage
 * does answer is a question about now, which is what
 * <code>BusinessCockpitService.getUserTask</code> asks. See {@link Camunda8CockpitBridge}.
 * <p>
 * The values travel to the cockpit's BPMS half through {@link Camunda8EventBeingReported},
 * because the cockpit asks that half for them while it builds the report, and it asks by
 * identifiers.
 * <p>
 * <b>The job is completed after the entry was committed</b>, never before. The entry gets a
 * transaction of its own ({@link EventTransaction#NEW}): a worker thread of the Camunda client
 * carries none, and there is nothing of the cluster's work to join. The cluster commits the
 * transition when the completion arrives, which is after this method returned.
 * <p>
 * The answer to the cluster follows the adapter's own protocol for a listener job. The job is
 * registered with the drain of its workflow module, so a shutdown waits for this handler instead
 * of closing the client under it. A rejection the cluster sent because it is busy is repeated
 * rather than turned into a failure. And work which a shutdown cut off is left to its lock, so
 * the next instance of the application gets the listener once that lock expires.
 * <p>
 * Every other failure fails the job with no retry left, which raises an incident at once rather
 * than repeating quietly. That is deliberate, and it is what Version 1 did. Since the report is
 * built here, a details provider which throws is such a failure: only reading happens on this
 * way, but what is read has to be right, and a defect which repetitions hide is a defect nobody
 * fixes. See decision 5 and decision 8 in the repository's DECISIONS.md.
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
            // one raises the incident straight away. See decision 5 in the repository's
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
      // a job type of this extension which this workflow module did not wire. One reading is
      // that another application deployed a model of its own under the same identifiers, and
      // its workflows are none of this module's business. But that is a guess about somebody
      // else's deployment. The other reading is that this module's own model was deployed by a
      // version of the application which wired more than the running one does, and then the
      // cockpit is quietly missing events
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
    // this extension adds task listeners and execution listeners and nothing else. So a job of
    // a third kind carrying one of its job types is a model nobody here understands. Guessing
    // what it means would report something the cockpit then shows; failing the job says it
    throw new IllegalStateException(
        """
            The Business Cockpit received the %s job '%s' (type '%s') of BPMN process '%s' in \
            workflow module '%s'! The cockpit adapter adds task listeners and execution listeners \
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
    final var userTask = new UserTaskReference(
        adapterId(), workflowModuleId, listener.bpmnProcessId(), processVersionOf(
            job), workflowAggregateId, workflowIdOf(
                job), userTaskKey, taskDefinition, job.getElementId());
    final var written = cluster
        .eventBeingReported()
        .whileReportingTheUserTask(
            userTaskKey,
            valuesOfTheTask(job, listener, userTask),
            () -> publisher
                .get()
                .publishUserTaskEvent(
                    userTask,
                    // the worker's clock, because a listener job carries no time of its own. It
                    // is handed out while the transition it gates waits, and what the cluster
                    // records about that transition is written by the exporter afterwards
                    kind, String.valueOf(job.getKey()), OffsetDateTime.now(),
                    EventTransaction.NEW));
    if (!written) {
      logger
          .debug(
              "Camunda8[{}]: the {} of user task '{}' (workflow aggregate '{}' of BPMN process '{}') {}",
              adapterId(), kind, userTaskKey, workflowAggregateId, listener.bpmnProcessId(),
              publisher.get().reportsUserTasks()
                  ? "produced no outbox entry: either there was nothing to say about that task and the report was dropped, or an entry of the same key is still waiting and the store kept it. Whichever it was is logged where it happened"
                  : "was not reported: this application reports no user tasks");
    }

  }

  private void reportWorkflow(
      final ActivatedJob job,
      final WiredListener listener,
      final String workflowAggregateId) {

    // the cockpit shows business cases, and a called process is a step of one rather than a
    // case of its own. See decision 3 in the repository's DECISIONS.md
    final var rootProcessInstanceKey = cluster.callHierarchy().rootProcessInstanceKeyOf(job);
    if (rootProcessInstanceKey != null) {
      logger
          .debug(
              "Camunda8[{}]: not reporting the {} of process instance {}: it is a called process of workflow {}, which is the business case",
              adapterId(), job.getListenerEventType(), job.getProcessInstanceKey(),
              rootProcessInstanceKey);
      return;
    }

    final var kind = workflowKindOf(job, listener);
    final var workflow = new WorkflowReference(
        adapterId(), workflowModuleId, listener.bpmnProcessId(), processVersionOf(
            job), workflowAggregateId, workflowIdOf(job));
    final var written = cluster
        .eventBeingReported()
        .whileReportingTheWorkflow(
            workflow.workflowId(),
            valuesOfTheWorkflow(job, listener, workflow),
            () -> publisher
                .get()
                .publishWorkflowEvent(
                    workflow,
                    // the worker's clock, for the reason given where a user task is reported
                    kind, String.valueOf(job.getKey()), OffsetDateTime.now(),
                    EventTransaction.NEW));
    if (!written) {
      logger
          .debug(
              "Camunda8[{}]: the {} of workflow '{}' (workflow aggregate '{}' of BPMN process '{}') {}",
              adapterId(), kind, workflowIdOf(job), workflowAggregateId,
              listener.bpmnProcessId(), publisher.get().reportsWorkflows()
                  ? "produced no outbox entry: either there was nothing to say about that workflow and the report was dropped, or an entry of the same key is still waiting and the store kept it. Whichever it was is logged where it happened"
                  : "was not reported: this application reports no workflows");
    }

  }

  /**
   * What the cluster says about the user task this job is about, read off the job.
   * <p>
   * The job carries the fields a person sees on a task: who it is assigned to, who may claim it
   * and the two dates. The two BPMN names are not on it and were read out of the model while
   * this extension wired the process. Nothing is asked of the cluster, which is the point: the
   * event this job reports is not in the cluster's searchable storage yet.
   * <p>
   * Process variables are not among the values. A worker of this extension asks the cluster for
   * the workflow aggregate's id and for nothing else, so a <code>&#64;TaskParam</code> parameter
   * of a details provider receives <code>null</code> here. Fetching more would make every
   * listener job of every workflow carry them.
   *
   * @param job The listener job
   * @param listener Where the listener sits, which is what carries the two BPMN names
   * @param userTask The task as the cockpit addresses it
   * @return The values, which travel to the cockpit's BPMS half
   */
  private UserTaskDetailsPrefill valuesOfTheTask(
      final ActivatedJob job,
      final WiredListener listener,
      final UserTaskReference userTask) {

    final var values = UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(processVersionOf(job))
        // the workflow of a task is the business case, which is the instance the reference
        // carries. For a task of a called process the job's own process instance is the step
        // below that case, and it is reported as the sub-workflow. See decision 3 in the
        // repository's DECISIONS.md
        .workflowId(userTask.workflowId())
        .subWorkflowId(subWorkflowIdOf(job, userTask))
        .bpmnTaskName(listener.elementName())
        .bpmnProcessName(listener.bpmnProcessName());
    final var properties = job.getUserTask();
    if (properties == null) {
      // a task listener always carries them. A job which does not is a job of a kind this
      // extension did not model, and the report says what it can rather than failing over a
      // field nobody has to see
      logger
          .warn(
              "Camunda8[{}]: the Business Cockpit listener job '{}' (type '{}') of BPMN process '{}' carries no user-task properties. The report of that task therefore names no assignee, no candidates and no dates. Check which listeners the deployed model of that process carries",
              adapterId(), job.getKey(), job.getType(), listener.bpmnProcessId());
      return values.build();
    }
    return values
        .assignee(properties.getAssignee())
        .candidateUsers(properties.getCandidateUsers())
        .candidateGroups(properties.getCandidateGroups())
        .dueDate(properties.getDueDate())
        .followUpDate(properties.getFollowUpDate())
        .build();

  }

  /**
   * What the cluster says about the workflow this job is about, read off the job.
   * <p>
   * The business id is the workflow aggregate's id, which the reference already carries. To
   * VanillaBP a business key is a business key only where it says what the aggregate's
   * <code>&#64;Id</code> attribute says, so the cockpit names that id and never what the cluster
   * holds beside it. The answer is therefore the same on every release line and on every way a
   * report is built. See decision 8 in the repository's DECISIONS.md.
   * <p>
   * Nobody is named as the initiator. Camunda 8 records who started an instance nowhere this
   * extension can read it.
   *
   * @param job The listener job
   * @param listener Where the listener sits, which is what carries the BPMN name of the process
   * @param workflow The workflow as the cockpit addresses it
   * @return The values, which travel to the cockpit's BPMS half
   */
  private static WorkflowDetailsPrefill valuesOfTheWorkflow(
      final ActivatedJob job,
      final WiredListener listener,
      final WorkflowReference workflow) {

    return new WorkflowDetailsPrefill(
        processVersionOf(job), workflow.workflowAggregateId(), listener.bpmnProcessName(), null);

  }

  /**
   * The workflow a task lives in, where that is not the workflow the cockpit knows the case by.
   * That happens for a call activity's child, whose tasks belong to the case above it.
   */
  private static String subWorkflowIdOf(
      final ActivatedJob job,
      final UserTaskReference userTask) {

    final var jobsOwnWorkflowId = String.valueOf(job.getProcessInstanceKey());
    return jobsOwnWorkflowId.equals(userTask.workflowId())
        ? null
        : jobsOwnWorkflowId;

  }

  /**
   * What happened to the user task, as the cockpit tells the cases apart. The three listeners
   * this extension adds report the three transitions a task goes through. A cluster which
   * delivers another one, a task somebody assigned for example, reports a change of the task and
   * nothing more specific.
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
   * What happened to the workflow.
   * <p>
   * A cancelled workflow says so with the job of the <code>cancel</code> listener at its
   * process, which 8.10 brought and which the adapter recognises for us. It is asked first,
   * because the case has to be closed rather than refreshed, and because the literal its event
   * type carries has no name on the older clients.
   * <p>
   * The other two listeners of this extension are <code>end</code> listeners, and what they sit
   * on tells them apart: the process itself ends the workflow, a start event begins it.
   * <p>
   * On 8.8 and 8.9 a cancelled workflow is reported as nothing at all. The cluster hands out no
   * job for it, and the <code>end</code> listener of a process does not run when the instance is
   * terminated. See decision 3 and decision 11 in the repository's DECISIONS.md.
   */
  private static WorkflowEventKind workflowKindOf(
      final ActivatedJob job,
      final WiredListener listener) {

    if (Camunda8CancelListeners.isCancellationOfTheProcess(job)) {
      return WorkflowEventKind.CANCELLED;
    }
    if (job.getListenerEventType() != ListenerEventType.END) {
      return WorkflowEventKind.UPDATED;
    }
    return listener.scopedBpmnProcessId().equals(job.getElementId())
        ? WorkflowEventKind.COMPLETED
        : WorkflowEventKind.CREATED;

  }

  /**
   * The version of the deployed BPMN process this job comes from, which is what picks between
   * details providers serving different versions of one model.
   * <p>
   * A job always names it, and it is the version of the job's OWN process. For a task of a
   * called process that is the called model, which is also the model the reference names - the
   * workflow the report is filed under is the case above it (see decision 3 in the repository's
   * DECISIONS.md), but the process id and the version belong together.
   *
   * @param job The listener job
   * @return The version, as the cluster counts it
   */
  private static String processVersionOf(
      final ActivatedJob job) {

    return String.valueOf(job.getProcessDefinitionVersion());

  }

  /**
   * The instance a business case is. An element of a called process belongs to the workflow its
   * whole hierarchy hangs below.
   */
  private String workflowIdOf(
      final ActivatedJob job) {

    final var root = cluster.callHierarchy().rootProcessInstanceKeyOf(job);
    return String.valueOf(root == null ? job.getProcessInstanceKey() : root);

  }

}
