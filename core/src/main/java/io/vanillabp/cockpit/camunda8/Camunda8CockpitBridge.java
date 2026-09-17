package io.vanillabp.cockpit.camunda8;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.processservice.Camunda8Searches;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What one configured Camunda 8 cluster answers the Business Cockpit.
 * <p>
 * Two different questions arrive here, and telling them apart is the whole job of the two
 * <code>prefilled…</code> methods.
 * <ul>
 * <li><b>What an event says.</b> A report is built while a listener job of this cluster is being
 * served, and the values of that event are on the job. The worker read them off it and left them
 * in {@link Camunda8EventBeingReported}, so they are answered from there. The cluster is not
 * asked, and it could not answer: its searchable storage is written by an exporter which runs
 * behind the engine, so the event being reported has not reached it while the job waits.</li>
 * <li><b>What is true now.</b> <code>BusinessCockpitService.getUserTask</code> and
 * <code>aggregateChanged</code> ask about a task or a case the application names, at the moment
 * it asks. Nothing is being reported then, so the answer comes from the searchable storage, and
 * a record it holds none of means there is nothing to show.</li>
 * </ul>
 * The three <code>…OfAggregate</code> methods only ever serve the second kind, so they search the
 * storage without asking.
 * <p>
 * One bridge serves one adapter id, because during a migration each cluster holds workflows of
 * its own and the cockpit addresses a workflow by the cluster holding it.
 */
public class Camunda8CockpitBridge implements BusinessCockpitBpmsBridge {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitBridge.class);

  private final Camunda8Clients.Cluster cluster;

  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * @param cluster The cluster this bridge reads
   * @param workflowTaskWiring VanillaBP's registry, which names the workflow aggregate's id
   *          variable of a BPMN process
   */
  public Camunda8CockpitBridge(
      final Camunda8Clients.Cluster cluster,
      final WorkflowTaskWiring workflowTaskWiring) {

    this.cluster = cluster;
    this.workflowTaskWiring = workflowTaskWiring;

  }

  @Override
  public String adapterId() {

    return cluster.scope().adapterId();

  }

  @Override
  public String adapterType() {

    return Camunda8DeploymentService.ADAPTER_TYPE;

  }

  @Override
  public Optional<UserTaskDetailsPrefill> prefilledUserTaskDetails(
      final UserTaskReference userTask) {

    final var ofTheEvent = cluster
        .eventBeingReported()
        .userTaskValuesOf(userTask.userTaskId());
    if (ofTheEvent.isPresent()) {
      return ofTheEvent;
    }

    return readFromTheStorage(
        "the user task '%s'".formatted(userTask.userTaskId()),
        () -> cluster
            .client()
            .newUserTaskGetRequest(Long.parseLong(userTask.userTaskId()))
            .send()
            .join())
        .map(
            task -> UserTaskDetailsPrefill
                .builder()
                .bpmnProcessVersion(processVersionOf(task.getProcessDefinitionVersion()))
                // the workflow of a task is the business case, which is the instance the
                // reference carries. For a task of a called process the cluster's own
                // processInstanceKey is the step below that case, and the next line reports it
                // as the sub-workflow. See decision 3
                .workflowId(userTask.workflowId())
                .subWorkflowId(subWorkflowIdOf(userTask, task))
                .bpmnTaskName(task.getName())
                .bpmnProcessName(task.getProcessName())
                .assignee(task.getAssignee())
                .candidateUsers(task.getCandidateUsers())
                .candidateGroups(task.getCandidateGroups())
                .dueDate(task.getDueDate())
                .followUpDate(task.getFollowUpDate())
                .build());

  }

  @Override
  public Optional<WorkflowDetailsPrefill> prefilledWorkflowDetails(
      final WorkflowReference workflow) {

    final var ofTheEvent = cluster
        .eventBeingReported()
        .workflowValuesOf(workflow.workflowId());
    if (ofTheEvent.isPresent()) {
      return ofTheEvent;
    }

    return readFromTheStorage(
        "the workflow '%s'".formatted(workflow.workflowId()),
        () -> cluster
            .client()
            .newProcessInstanceGetRequest(Long.parseLong(workflow.workflowId()))
            .send()
            .join())
        .map(
            instance -> new WorkflowDetailsPrefill(
                processVersionOf(instance.getProcessDefinitionVersion()),
                // the workflow aggregate's id, which the reference already carries. A business
                // key is only a business key to VanillaBP where it says what the aggregate's
                // @Id attribute says, so what the cluster holds is not read here. See decision 8
                // in the repository's DECISIONS.md
                workflow.workflowAggregateId(), instance.getProcessDefinitionName(), null));

  }

  /**
   * Asks the cluster's searchable storage about one record, for a question about now.
   * <p>
   * A record the storage holds none of means there is nothing to show. The application asked
   * about a task or a case of its own, in its own time, and an empty answer is what it can work
   * with: the cockpit keeps what it stored before. The reason is said out loud, because the same
   * answer comes back for a record the exporter has not written yet, and the two cannot be told
   * apart from here.
   * <p>
   * Every other answer travels on unchanged. An outage must not look like an empty result, or a
   * cockpit would quietly stop showing what is there. WHICH answer means "I do not hold that" is
   * the adapter's to say ({@code Camunda8Errors#notFound}): the REST gateway says it with HTTP
   * <code>404</code> and the gRPC gateway with the status <code>NOT_FOUND</code>.
   *
   * @param <T> The kind of record
   * @param what What is being read, for the message
   * @param read The request to the cluster
   * @return The record, or empty where the storage holds none
   */
  private <T> Optional<T> readFromTheStorage(
      final String what,
      final Supplier<T> read) {

    try {
      return Optional.of(read.get());
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      logger
          .warn(
              "Camunda8[{}]: the cluster's searchable storage holds no record of {}. The Business Cockpit is told nothing about it, so it keeps showing what it stored before. There are two readings of this. Either that record is gone. Or the exporter which writes that storage has not caught up with it yet, which is what a workflow or a task born in the very transaction asking here looks like - the start of such a workflow is reported by this extension's listeners anyway.",
              adapterId(), what, e);
      return Optional.empty();
    }

  }

  @Override
  public List<WorkflowReference> workflowsOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var found = searchWorkflows(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (found.isEmpty()) {
      reportNothingToSee("workflow", workflowModuleId, bpmnProcessId, workflowAggregateId);
    }
    return found
        .stream()
        .map(
            instance -> new WorkflowReference(
                adapterId(), workflowModuleId, bpmnProcessId, processVersionOf(
                    instance.getProcessDefinitionVersion()), workflowAggregateId, String
                        .valueOf(instance.getProcessInstanceKey())))
        .toList();

  }

  @Override
  public List<UserTaskReference> userTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final List<String> userTaskIds) {

    if ((userTaskIds == null) || userTaskIds.isEmpty()) {
      return searchUserTasks(workflowModuleId, bpmnProcessId, workflowAggregateId, null, true)
          .stream()
          .map(task -> referenceOf(workflowModuleId, bpmnProcessId, workflowAggregateId, task))
          .toList();
    }

    final var references = new LinkedList<UserTaskReference>();
    userTaskIds
        .forEach(
            userTaskId -> userTaskOfAggregate(
                workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskId)
                .ifPresentOrElse(
                    references::add,
                    () -> reportNothingToSee(
                        "user task '%s'".formatted(userTaskId), workflowModuleId, bpmnProcessId,
                        workflowAggregateId)));
    return references;

  }

  @Override
  public Optional<UserTaskReference> userTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String userTaskId) {

    final Long userTaskKey;
    try {
      userTaskKey = Long.valueOf(userTaskId);
    } catch (final NumberFormatException e) {
      // an id of another BPMS: during a migration the application may still hold ids the
      // cluster never handed out, and none of them is a task of this one
      logger
          .debug(
              "Camunda8[{}]: '{}' is not a Camunda 8 user-task key, so this cluster holds no such task",
              adapterId(), userTaskId, e);
      return Optional.empty();
    }

    return searchUserTasks(
        workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskKey, false)
        .stream()
        .findFirst()
        .map(task -> referenceOf(workflowModuleId, bpmnProcessId, workflowAggregateId, task));

  }

  /**
   * The workflows of one aggregate this cluster holds, whether they are still running or not:
   * a case the cockpit shows keeps its history after it ended.
   */
  private List<ProcessInstance> searchWorkflows(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var scopedProcessId = cluster
        .scope()
        .scopedProcessIdOf(workflowModuleId, bpmnProcessId);
    final var tenantId = cluster.scope().tenantIdOf(workflowModuleId);
    return cluster
        .client()
        .newProcessInstanceSearchRequest()
        // the process id as the cluster knows it, the tenant of the workflow module and the
        // aggregate's id quoted as the JSON a variable is stored in. The adapter spells all
        // three, because a search which spells one of them differently answers nothing, and
        // nothing reads exactly like a workflow which was never started
        .filter(
            filter -> Camunda8Searches
                .scopedTo(
                    filter, scopedProcessId, tenantId,
                    aggregateIdNameOf(workflowModuleId, bpmnProcessId), workflowAggregateId))
        .send()
        .join()
        .items()
        .stream()
        // a called process inherits the variables of its caller, and it is a step of the
        // business case rather than a case of its own. The adapter leaves this to whoever
        // searched, because what has to be sorted out depends on the question which was asked
        .filter(instance -> instance.getParentProcessInstanceKey() == null)
        .toList();

  }

  /**
   * The user tasks of one aggregate this cluster holds.
   *
   * @param userTaskKey One task's key, or <code>null</code> for all of them
   * @param activeOnly Whether only a task somebody can still work on counts
   */
  private List<UserTask> searchUserTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Long userTaskKey,
      final boolean activeOnly) {

    final var scopedProcessId = cluster
        .scope()
        .scopedProcessIdOf(workflowModuleId, bpmnProcessId);
    final var tenantId = cluster.scope().tenantIdOf(workflowModuleId);
    return cluster
        .client()
        .newUserTaskSearchRequest()
        .filter(filter -> {
          // the process id as the cluster knows it, the tenant of the workflow module and
          // the aggregate's id as a variable of the process instance. The adapter spells all
          // three, because a search which spells one of them differently answers nothing,
          // and nothing reads exactly like a case which has no such task. It also means a
          // key somebody guessed reads no task of another case
          Camunda8Searches
              .scopedTo(
                  filter, scopedProcessId, tenantId,
                  aggregateIdNameOf(workflowModuleId, bpmnProcessId), workflowAggregateId);
          // which tasks of that aggregate are meant is this bridge's own question, so it
          // adds that part itself
          if (userTaskKey != null) {
            filter.userTaskKey(userTaskKey);
          }
          if (activeOnly) {
            filter.state(UserTaskState.CREATED);
          }
        })
        .send()
        .join()
        .items();

  }

  private UserTaskReference referenceOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final UserTask task) {

    return new UserTaskReference(
        adapterId(), workflowModuleId, bpmnProcessId, processVersionOf(
            task.getProcessDefinitionVersion()), workflowAggregateId, String
                .valueOf(task.getProcessInstanceKey()), String.valueOf(task.getUserTaskKey()), cluster
                    .scope()
                    .plainTaskDefinitionOf(workflowModuleId, bpmnProcessId,
                        task.getExternalFormReference()), task.getElementId());

  }

  /**
   * The version of the deployed BPMN process, spelled the way Camunda 8 counts it: the number
   * the cluster raises by one every time a model is deployed under a process id it already
   * holds.
   * <p>
   * The searchable storage answers no version at all for a record whose process definition it
   * has not written yet, and that is why this is not a plain conversion. Such a record carries
   * no version, and the reference says so by carrying none: the platform then serves the report
   * with a details provider which names no version, and passes over every provider which names
   * one. The text <code>"null"</code> would lose against every version range just the same, but
   * it would read like a version somebody deployed, in a message and on a screen alike.
   *
   * @param version What the cluster answered
   * @return The version as a string, or <code>null</code> where the cluster named none
   */
  private static String processVersionOf(
      final Integer version) {

    return version == null
        ? null
        : String.valueOf(version);

  }

  /**
   * The workflow a task lives in, where that is not the workflow the cockpit knows the case by.
   * That happens for a call activity's child, whose tasks belong to the case above it.
   */
  private static String subWorkflowIdOf(
      final UserTaskReference userTask,
      final UserTask task) {

    final var workflowId = String.valueOf(task.getProcessInstanceKey());
    return workflowId.equals(userTask.workflowId())
        ? null
        : workflowId;

  }

  private String aggregateIdNameOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId);

  }

  /**
   * Says out loud that a change the application reported reaches nobody.
   * <p>
   * The application called this inside its own transaction, and a workflow started by that very
   * transaction is not searchable yet. Camunda 8 receives the start command after the commit,
   * and its searchable storage learns about it later still. Waiting here would hold the business
   * transaction open for an exporter. So the change is not reported, and the reason is said
   * instead. The workflow's own start is reported by this extension's start-event listener
   * either way, with whatever the aggregate holds at that moment.
   */
  private void reportNothingToSee(
      final String what,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    logger
        .warn(
            "Camunda8[{}]: the Business Cockpit was told that aggregate '{}' of '{}/{}' changed, but the cluster's searchable storage holds no {} of it. Nothing was reported, so the cockpit keeps showing the older state until something else happens to that workflow. A workflow started in the very transaction which reported the change is not searchable yet - its start is reported by the cockpit's own start-event listener anyway, so a call right after starting a workflow is superfluous.",
            adapterId(), workflowAggregateId, workflowModuleId, bpmnProcessId, what);

  }

}
