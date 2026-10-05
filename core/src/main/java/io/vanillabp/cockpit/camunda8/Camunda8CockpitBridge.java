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
import io.vanillabp.integration.extension.spi.election.WorkflowStart;

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
 * The <code>…OfAggregate</code> methods only ever serve the second kind, so they search the
 * storage without asking, apart from {@link #workflowsOfAggregateRightAway}, which reads nothing.
 * <p>
 * <b>A record the storage holds none of.</b> That is silence and not an ending. The exporter which
 * writes the storage runs behind the engine, so a task or a case born a moment ago is missing from
 * a search in the same way one which ended is, and nothing here can tell the two apart. So an
 * empty result travels on with a line in the log naming both readings, which is what decision 8
 * asks for, and no caller may read the end of a task into it. An end reaches the cockpit through
 * this extension's own listeners.
 * <p>
 * <b>Which workflow a case is.</b> VanillaBP writes down the key of a workflow and the version it
 * runs on when it starts it, and {@link WorkflowElection#workflowStartOf} reads that note without
 * asking any BPMS. Where it names both, the bridge takes them as they are and asks the cluster
 * nothing, so a change reported a moment after the start reaches the cockpit although the storage
 * has not written the workflow yet. The process name of such a report comes from the model this
 * extension wired, and not from the storage.
 * <p>
 * Where the note names less, a change of the aggregate cannot be named in the application's
 * transaction without the storage. {@link #workflowsOfAggregateRightAway} then answers nothing,
 * and the extension resolves the change when its outbox entry is dispatched. There the bridge
 * reads the workflow by its key, or searches the storage where VanillaBP knows no key, and an
 * empty answer makes the extension ask again a little later. The version is what picks the
 * application's details provider, so a workflow is never referenced without one it could still
 * get.
 * <p>
 * One bridge serves one adapter id, because during a migration each cluster holds workflows of
 * its own and the cockpit addresses a workflow by the cluster holding it.
 */
public class Camunda8CockpitBridge implements BusinessCockpitBpmsBridge {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitBridge.class);

  private final Camunda8Clients.Cluster cluster;

  private final WorkflowTaskWiring workflowTaskWiring;

  private final WorkflowElection election;

  private final Camunda8CockpitDeployments deployments;

  /**
   * @param cluster The cluster this bridge reads
   * @param workflowTaskWiring VanillaBP's registry, which names the workflow aggregate's id
   *          variable of a BPMN process
   * @param election VanillaBP's election, asked only for what it wrote down when a workflow
   *          started
   * @param deployments What this extension read out of the models while wiring them
   */
  public Camunda8CockpitBridge(
      final Camunda8Clients.Cluster cluster,
      final WorkflowTaskWiring workflowTaskWiring,
      final WorkflowElection election,
      final Camunda8CockpitDeployments deployments) {

    this.cluster = cluster;
    this.workflowTaskWiring = workflowTaskWiring;
    this.election = election;
    this.deployments = deployments;

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

    // the version picks the details provider and the process name comes from the model this
    // extension wired, so a reference which names its version needs nothing of the storage
    if (hasAVersion(workflow.processVersion())) {
      return Optional.of(prefillOf(workflow, workflow.processVersion()));
    }

    // only the storage knows the version of this workflow
    final ProcessInstance instance;
    try {
      instance = cluster
          .client()
          .newProcessInstanceGetRequest(Long.parseLong(workflow.workflowId()))
          .send()
          .join();
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      sayTheStorageHasNotWrittenTheWorkflow(
          "workflow '%s' of aggregate '%s'"
              .formatted(workflow.workflowId(), workflow.workflowAggregateId()),
          e);
      return Optional.empty();
    }
    return Optional.of(prefillOf(workflow, processVersionOf(instance.getProcessDefinitionVersion())));

  }

  /**
   * What a report says about a workflow outside an event of this cluster.
   * <p>
   * The business id is the aggregate's id, as on every other way: a business key is only a
   * business key to VanillaBP where it says what the aggregate's <code>&#64;Id</code> attribute
   * says, so what the cluster holds is not read here (decision 8). The process name is the one
   * this extension read out of the model while wiring it, so that a report needs no record of the
   * storage for it.
   *
   * @param workflow The workflow a report is being built for
   * @param version The version of the model the workflow runs on, or <code>null</code>
   * @return The values
   */
  private WorkflowDetailsPrefill prefillOf(
      final WorkflowReference workflow,
      final String version) {

    return new WorkflowDetailsPrefill(
        version, workflow.workflowAggregateId(), deployments
            .bpmnProcessNameOf(adapterId(), workflow.workflowModuleId(), workflow.bpmnProcessId())
            .orElse(null), null);

  }

  /**
   * Says that the storage holds no record of a workflow the Business Cockpit asked about.
   * <p>
   * Only the dispatch of a changed aggregate asks this way, and it asks again a little later
   * while the answer is empty. The extension says so in its own log, once at the first attempt
   * and once more where it gives up, so a line per attempt here would only repeat it.
   *
   * @param what What was read, for the message
   * @param notFound The cluster's answer, or <code>null</code> for an empty search
   */
  private void sayTheStorageHasNotWrittenTheWorkflow(
      final String what,
      final RuntimeException notFound) {

    logger
        .debug(
            "Camunda8[{}]: the cluster's searchable storage holds no record of {} yet. Either the exporter which writes that storage has not caught up with it, or there is no such record. The Business Cockpit asks again a little later.",
            adapterId(), what, notFound);

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
      sayTheStorageHoldsNoRecordOf(what, e);
      return Optional.empty();
    }

  }

  /**
   * Says out loud that a read of one record found nothing, and that this reads two ways.
   *
   * @param what What was read, for the message
   * @param notFound The cluster's answer
   */
  private void sayTheStorageHoldsNoRecordOf(
      final String what,
      final RuntimeException notFound) {

    logger
        .warn(
            "Camunda8[{}]: the cluster's searchable storage holds no record of {}. The Business Cockpit is told nothing about it, so it keeps showing what it stored before. There are two readings of this. Either that record is gone. Or the exporter which writes that storage has not caught up with it yet, which is what a workflow or a task born in the very transaction asking here looks like - the start of such a workflow is reported by this extension's listeners anyway.",
            adapterId(), what, notFound);

  }

  @Override
  public List<WorkflowReference> workflowsOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    // what VanillaBP wrote down when it started the workflow comes first. With the version the
    // note is the whole answer and the cluster is not asked. With only the key, the workflow is
    // read by that key and not searched for by the aggregate's id
    final var started = startedByVanillaBp(workflowModuleId, bpmnProcessId, workflowAggregateId);
    final var key = started
        .map(WorkflowStart::workflowId)
        .flatMap(this::workflowKeyOf);
    if (key.isPresent()) {
      final var version = started.get().processVersion();
      if (hasAVersion(version)) {
        return List
            .of(
                new WorkflowReference(
                    adapterId(), workflowModuleId, bpmnProcessId, version, workflowAggregateId, String
                        .valueOf(key.get())));
      }
      return aWorkflowVanillaBpStarted(
          workflowModuleId, bpmnProcessId, workflowAggregateId, key.get())
          .stream()
          .toList();
    }

    final var found = searchWorkflows(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (found.isEmpty()) {
      sayTheStorageHasNotWrittenTheWorkflow(
          "a workflow of aggregate '%s' of '%s/%s'"
              .formatted(workflowAggregateId, workflowModuleId, bpmnProcessId),
          null);
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

  /**
   * The workflow of an aggregate where VanillaBP wrote down its key AND its version at the start,
   * and nothing otherwise.
   * <p>
   * This is asked in the application's transaction. Every other way to the workflow reads the
   * searchable storage, and that storage may not hold a workflow which started a moment ago, or
   * any workflow while the exporter stands still. So the change is then left to the dispatch of
   * its entry, which calls {@link #workflowsOfAggregate} and asks again while the storage has not
   * written the workflow.
   */
  @Override
  public Optional<List<WorkflowReference>> workflowsOfAggregateRightAway(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var started = startedByVanillaBp(workflowModuleId, bpmnProcessId, workflowAggregateId);
    final var key = started
        .map(WorkflowStart::workflowId)
        .flatMap(this::workflowKeyOf);
    if (key.isEmpty() || !hasAVersion(started.get().processVersion())) {
      return Optional.empty();
    }
    return Optional
        .of(
            List
                .of(
                    new WorkflowReference(
                        adapterId(), workflowModuleId, bpmnProcessId, started.get()
                            .processVersion(), workflowAggregateId, String.valueOf(key.get()))));

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
   * What VanillaBP wrote down when it started the workflow of one aggregate on this cluster: the
   * key and, where it knows it, the version.
   * <p>
   * The answer says what was true at the start. It does not say that the workflow still runs, so
   * it is used to read that workflow and to name it in a report, and never sent to the cluster as
   * a command. That is also why it serves a change reported after the workflow ended: the
   * cockpit hears of it, the cluster does not.
   * <p>
   * A note of another adapter is left out. During a migration that is a workflow of another
   * cluster, and its key would be read on this one. A note which names no adapter is taken, because
   * that is a key VanillaBP knows from its election cache, and the key itself still has to be one
   * of this BPMS.
   * <p>
   * An empty version is read the same way whether it is "not yet" or "never"
   * ({@link WorkflowStart#versionsAreReported}): the workflow is read by its key. Camunda 8 counts
   * a version for every workflow, so "never" can only mean that the adapter did not say it reports
   * versions. The storage still knows the version then, and every details provider of the
   * application may name one.
   *
   * @return The note, or empty where VanillaBP does not know the workflow. That covers a workflow
   *         nobody started, one started before VanillaBP wrote such notes, one whose note is too
   *         old to be kept, and a process this application does not serve. Nobody can tell these
   *         apart, so nothing is read into an empty answer
   */
  private Optional<WorkflowStart> startedByVanillaBp(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return election
        .workflowStartOf(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .filter(start -> (start.adapterId() == null) || start.adapterId().equals(adapterId()));

  }

  /**
   * Whether a version was named at all. An empty text is no version, the same as a missing one.
   */
  private static boolean hasAVersion(
      final String version) {

    return (version != null) && !version.isBlank();

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
   * The workflow VanillaBP started for one aggregate, read from the storage by its key, where
   * VanillaBP wrote down no version for it.
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
      sayTheStorageHasNotWrittenTheWorkflow(
          "workflow '%s' of aggregate '%s' of '%s/%s', which VanillaBP started without writing down its version"
              .formatted(workflowKey, workflowAggregateId, workflowModuleId, bpmnProcessId),
          e);
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
