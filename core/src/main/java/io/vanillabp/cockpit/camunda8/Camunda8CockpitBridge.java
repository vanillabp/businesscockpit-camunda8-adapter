package io.vanillabp.cockpit.camunda8;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What one configured Camunda 8 cluster answers the Business Cockpit.
 * <p>
 * Every question here is answered by the cluster's searchable storage rather than by its
 * engine: the engine takes commands and hands out jobs, and what a user task looks like right
 * now is something only the storage behind it can be asked. That storage runs behind the engine
 * by design, which decides what a missing record means - see {@link Camunda8CockpitReads}.
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

    final UserTask task;
    try {
      task = cluster
          .client()
          .newUserTaskGetRequest(Long.parseLong(userTask.userTaskId()))
          .send()
          .join();
    } catch (final RuntimeException e) {
      if (Camunda8CockpitReads.nothingFound(e)) {
        // A report is dispatched moments after the cluster handed out the listener job it
        // came from, so a user task the searchable storage has no record of is one it has
        // not written yet rather than one which is gone. Dropping it here would lose a task
        // the cockpit is supposed to show
        throw Camunda8CockpitReads
            .notExportedYet("the user task '%s'".formatted(userTask.userTaskId()), adapterId());
      }
      throw e;
    }

    return Optional
        .of(
            UserTaskDetailsPrefill
                .builder()
                .bpmnProcessVersion(String.valueOf(task.getProcessDefinitionVersion()))
                .workflowId(String.valueOf(task.getProcessInstanceKey()))
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

    final ProcessInstance instance;
    try {
      instance = cluster
          .client()
          .newProcessInstanceGetRequest(Long.parseLong(workflow.workflowId()))
          .send()
          .join();
    } catch (final RuntimeException e) {
      if (Camunda8CockpitReads.nothingFound(e)) {
        throw Camunda8CockpitReads
            .notExportedYet("the workflow '%s'".formatted(workflow.workflowId()), adapterId());
      }
      throw e;
    }

    return Optional
        .of(
            new WorkflowDetailsPrefill(
                String.valueOf(instance.getProcessDefinitionVersion()), instance.getBusinessId(), instance
                    .getProcessDefinitionName(), null));

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
                adapterId(), workflowModuleId, bpmnProcessId, workflowAggregateId, String
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
        // one filter call: a second one replaces the first rather than adding to it
        .filter(filter -> {
          filter.processDefinitionId(scopedProcessId);
          filter
              .variables(
                  Map
                      .of(
                          aggregateIdNameOf(workflowModuleId, bpmnProcessId),
                          aggregateIdSearchValue(workflowAggregateId)));
          if (tenantId != null) {
            filter.tenantId(tenantId);
          }
        })
        .send()
        .join()
        .items()
        .stream()
        // a called process inherits the variables of its caller, and it is a step of the
        // business case rather than a case of its own
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
          filter.bpmnProcessId(scopedProcessId);
          // the variable filter is what makes an id answerable only for the aggregate it
          // belongs to, so that a guessed key cannot read another case's task
          filter
              .processInstanceVariables(
                  Map
                      .of(
                          aggregateIdNameOf(workflowModuleId, bpmnProcessId),
                          aggregateIdSearchValue(workflowAggregateId)));
          if (userTaskKey != null) {
            filter.userTaskKey(userTaskKey);
          }
          if (activeOnly) {
            filter.state(UserTaskState.CREATED);
          }
          if (tenantId != null) {
            filter.tenantId(tenantId);
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
        adapterId(), workflowModuleId, bpmnProcessId, workflowAggregateId, String
            .valueOf(task.getProcessInstanceKey()), String.valueOf(task.getUserTaskKey()), cluster
                .scope()
                .plainTaskDefinitionOf(workflowModuleId, bpmnProcessId,
                    task.getExternalFormReference()), task.getElementId());

  }

  /**
   * The workflow a task lives in, where that is not the workflow the cockpit knows the case by
   * - a call activity's child, whose tasks belong to the case above it.
   */
  private static String subWorkflowIdOf(
      final UserTaskReference userTask,
      final UserTask task) {

    final var workflowId = String.valueOf(task.getProcessInstanceKey());
    return workflowId.equals(userTask.workflowId())
        ? null
        : workflowId;

  }

  /**
   * The value a variable filter has to carry to match a workflow aggregate's id.
   * <p>
   * A Camunda 8 cluster stores every process variable as JSON and its search API compares
   * against that JSON verbatim, so a variable holding the characters <code>4711</code> is
   * matched by <code>"4711"</code> with the quotes and never by a bare <code>4711</code>.
   * VanillaBP writes the aggregate's id as a string whatever type the id attribute has, so the
   * quoting is unconditional. Getting it wrong is invisible: the search answers nothing, which
   * reads exactly like a workflow nobody started.
   */
  private static String aggregateIdSearchValue(
      final String workflowAggregateId) {

    return "\"%s\"".formatted(
        workflowAggregateId
            .replace("\\", "\\\\")
            .replace("\"", "\\\""));

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
   * transaction is not searchable yet - Camunda 8 receives the start command after the commit,
   * and its searchable storage learns about it later still. Waiting here would hold the
   * business transaction open for an exporter, so the change is not reported and the reason is
   * said instead. The workflow's own start is reported by this extension's start-event listener
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
