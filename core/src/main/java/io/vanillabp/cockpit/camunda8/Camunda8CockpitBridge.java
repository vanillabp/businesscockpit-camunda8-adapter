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
import io.vanillabp.integration.extension.spi.election.WorkflowElection;

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
 * it asks. Nothing is being reported then, so the answer comes from the searchable storage.</li>
 * </ul>
 * The three <code>…OfAggregate</code> methods only ever serve the second kind, so they search the
 * storage without asking.
 * <p>
 * <b>A record the storage holds none of.</b> That is silence and not an ending. The exporter which
 * writes the storage runs behind the engine, so a task or a case born a moment ago is missing from
 * a search in the same way one which ended is, and nothing here can tell the two apart. So an
 * empty result travels on with a line in the log naming both readings, which is what decision 8
 * asks for, and no caller may read the end of a task into it. An end reaches the cockpit through
 * this extension's own listeners.
 * <p>
 * <b>Which workflow a case is.</b> VanillaBP writes down the key of a workflow when it starts it,
 * and {@link WorkflowElection#workflowIdOf} reads that note without asking any BPMS. Where it
 * answers, the bridge reads that one workflow by its key instead of searching the storage by the
 * aggregate's id. An empty answer means that VanillaBP does not know, for one of several reasons
 * nobody can tell apart. So nothing is read into it, and the bridge searches the storage the way
 * it always did.
 * <p>
 * The key does not make a report possible while the storage has not written the workflow yet.
 * A report needs the version the workflow runs on, because the version picks the application's
 * details provider, and only the storage knows it. A report without it would be served by the
 * wrong provider, or by none, and the cockpit would replace the details it shows with that. So a
 * workflow the storage does not hold yet is not reported, and the log says why.
 * <p>
 * One bridge serves one adapter id, because during a migration each cluster holds workflows of
 * its own and the cockpit addresses a workflow by the cluster holding it.
 */
public class Camunda8CockpitBridge implements BusinessCockpitBpmsBridge {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitBridge.class);

  private final Camunda8Clients.Cluster cluster;

  private final WorkflowTaskWiring workflowTaskWiring;

  private final WorkflowElection election;

  /**
   * @param cluster The cluster this bridge reads
   * @param workflowTaskWiring VanillaBP's registry, which names the workflow aggregate's id
   *          variable of a BPMN process
   * @param election VanillaBP's election, asked only for the key it wrote down when a workflow
   *          started
   */
  public Camunda8CockpitBridge(
      final Camunda8Clients.Cluster cluster,
      final WorkflowTaskWiring workflowTaskWiring,
      final WorkflowElection election) {

    this.cluster = cluster;
    this.workflowTaskWiring = workflowTaskWiring;
    this.election = election;

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

    // the key VanillaBP wrote down when it started the workflow comes first, so the workflow is
    // read by its key and not searched for by the aggregate's id
    final var started = startedByVanillaBp(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .flatMap(this::workflowKeyOf);
    if (started.isPresent()) {
      return aWorkflowVanillaBpStarted(
          workflowModuleId, bpmnProcessId, workflowAggregateId, started.get())
          .stream()
          .toList();
    }

    final var found = searchWorkflows(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (found.isEmpty()) {
      sayTheStorageHoldsNoRecord("workflow", workflowModuleId, bpmnProcessId, workflowAggregateId);
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
      final var found = searchUserTasks(
          workflowModuleId, bpmnProcessId, workflowAggregateId, null, true);
      if (found.isEmpty()) {
        sayTheStorageHoldsNoRecord(
            "active user tasks", workflowModuleId, bpmnProcessId, workflowAggregateId);
      }
      return found
          .stream()
          .map(task -> referenceOf(workflowModuleId, bpmnProcessId, workflowAggregateId, task))
          .toList();
    }

    final var references = new LinkedList<UserTaskReference>();
    // the public method says the same thing about an empty answer, so this branch searches
    // instead of calling it. Going through it would log every missing task twice
    userTaskIds
        .forEach(
            userTaskId -> userTaskKeyOf(userTaskId)
                .ifPresent(
                    userTaskKey -> searchUserTaskOfAggregate(
                        workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskKey)
                        .ifPresentOrElse(
                            references::add,
                            () -> sayTheStorageHoldsNoRecord(
                                "user task '%s'".formatted(userTaskId), workflowModuleId,
                                bpmnProcessId, workflowAggregateId))));
    return references;

  }

  @Override
  public Optional<UserTaskReference> userTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String userTaskId) {

    final var userTaskKey = userTaskKeyOf(userTaskId);
    if (userTaskKey.isEmpty()) {
      // no key of this cluster, so this is a clear answer and not a silence worth a warning
      return Optional.empty();
    }

    final var found = searchUserTaskOfAggregate(
        workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskKey.get());
    if (found.isEmpty()) {
      sayTheStorageHoldsNoRecord(
          "user task '%s'".formatted(userTaskId), workflowModuleId, bpmnProcessId,
          workflowAggregateId);
    }
    return found;

  }

  /**
   * One task of one aggregate as the searchable storage holds it, and nothing said about an empty
   * answer. Each caller says that itself, because saying it here would say it twice for the ids a
   * caller named.
   *
   * @param userTaskKey The task's key as the cluster counts it
   * @return The task, or empty where the storage holds no such task of that aggregate
   */
  private Optional<UserTaskReference> searchUserTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Long userTaskKey) {

    return searchUserTasks(
        workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskKey, false)
        .stream()
        .findFirst()
        .map(task -> referenceOf(workflowModuleId, bpmnProcessId, workflowAggregateId, task));

  }

  /**
   * The key Camunda 8 would know a user task by, read off the id the application named.
   *
   * @param userTaskId The id the caller asked about
   * @return The key, or empty where that id is no key of this BPMS
   */
  private Optional<Long> userTaskKeyOf(
      final String userTaskId) {

    try {
      return Optional.of(Long.valueOf(userTaskId));
    } catch (final NumberFormatException e) {
      // an id of another BPMS: during a migration the application may still hold ids the
      // cluster never handed out, and none of them is a task of this one. That is a clear
      // answer, so it stays a debug line and gets none of the warning an empty search gets
      logger
          .debug(
              "Camunda8[{}]: '{}' is not a Camunda 8 user-task key, so this cluster holds no such task",
              adapterId(), userTaskId, e);
      return Optional.empty();
    }

  }

  /**
   * The key of the workflow VanillaBP started for one aggregate, as VanillaBP wrote it down.
   * <p>
   * The answer says what was true at the start. It does not say that the workflow still runs, so
   * it is used to read that workflow and to name it in a report, and never sent to the cluster as
   * a command.
   *
   * @return The key, or empty where VanillaBP does not know it. That covers a workflow nobody
   *         started, one started before VanillaBP wrote such notes, one whose note is too old to
   *         be kept, and a process this application does not serve. Nobody can tell these apart,
   *         so nothing is read into an empty answer
   */
  private Optional<String> startedByVanillaBp(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return election.workflowIdOf(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

  /**
   * The key Camunda 8 would know a workflow by, read off the id VanillaBP wrote down.
   *
   * @param workflowId The id VanillaBP wrote down
   * @return The key, or empty where that id is no key of this BPMS. During a migration the id
   *         may come from another BPMS, and then the search decides
   */
  private Optional<Long> workflowKeyOf(
      final String workflowId) {

    try {
      return Optional.of(Long.valueOf(workflowId));
    } catch (final NumberFormatException e) {
      logger
          .debug(
              "Camunda8[{}]: '{}' is not a Camunda 8 process instance key, so the storage is searched instead",
              adapterId(), workflowId, e);
      return Optional.empty();
    }

  }

  /**
   * The workflow VanillaBP started for one aggregate, read from the storage by its key.
   * <p>
   * A workflow which started a moment ago is not in the storage yet. Then it is not referenced,
   * because the report would go out without the version, and the version is what picks the
   * application's details provider. The log says that this is the exporter running behind,
   * which is the one reading left when VanillaBP knows the key.
   *
   * @param workflowKey The key VanillaBP wrote down
   * @return The workflow, or empty where the storage does not hold it yet
   */
  private Optional<WorkflowReference> aWorkflowVanillaBpStarted(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Long workflowKey) {

    try {
      final var instance = cluster
          .client()
          .newProcessInstanceGetRequest(workflowKey)
          .send()
          .join();
      return Optional
          .of(
              new WorkflowReference(
                  adapterId(), workflowModuleId, bpmnProcessId, processVersionOf(
                      instance.getProcessDefinitionVersion()), workflowAggregateId, String
                          .valueOf(workflowKey)));
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      logger
          .warn(
              "Camunda8[{}]: the Business Cockpit asked about the workflow of aggregate '{}' of '{}/{}'. VanillaBP started it as workflow '{}', and the cluster's searchable storage has not written it yet. Only that storage knows the version the workflow runs on, and the version picks the details provider of a report. So this change is not reported, and the cockpit keeps showing what it stored before. A change reported once that storage holds the workflow reaches the cockpit.",
              adapterId(), workflowAggregateId, workflowModuleId, bpmnProcessId, workflowKey);
      return Optional.empty();
    }

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
   * Says out loud that a search of one aggregate found nothing, and that this reads two ways.
   * <p>
   * Both ways into this class ask about now. The application reports a change inside its own
   * transaction, and a workflow started by that very transaction is not searchable yet: Camunda 8
   * receives the start command after the commit, and its searchable storage learns about it later
   * still. The application also reads a task through
   * <code>BusinessCockpitService.getUserTask</code>, where the same lag hides a task which is
   * wide awake. Waiting for the exporter would hold a business transaction or a web request open
   * for it, so neither way waits.
   * <p>
   * What is left is to say both readings, because nothing here can tell them apart. Decision 8
   * asks for exactly that: an empty result and a line in the log naming what it may mean. A
   * report is dropped, a read is answered with nothing, the cockpit keeps what it stored before,
   * and no task ends because of it. The start of a workflow and the end of a task are reported by
   * this extension's own listeners either way.
   *
   * @param what What was searched for, for the message
   */
  private void sayTheStorageHoldsNoRecord(
      final String what,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    logger
        .warn(
            "Camunda8[{}]: the Business Cockpit asked about the {} of aggregate '{}' of '{}/{}', and the cluster's searchable storage holds no record of that. There are two readings of this and nothing here can tell them apart. Either there is no such record any more. Or the exporter which writes that storage has not caught up with it yet, which is what a workflow or a task born in the very transaction asking here looks like. So the cockpit was told nothing and keeps showing what it stored before, and no task of this case ends because of it. The start of a workflow and the end of a task are reported by this extension's own listeners anyway, so a call right after starting a workflow is superfluous.",
            adapterId(), what, workflowAggregateId, workflowModuleId, bpmnProcessId);

  }

}
