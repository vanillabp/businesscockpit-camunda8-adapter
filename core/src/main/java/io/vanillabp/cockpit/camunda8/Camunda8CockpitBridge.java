package io.vanillabp.cockpit.camunda8;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.processservice.Camunda8Searches;
import io.vanillabp.camunda8.processservice.Camunda8VariableFilters;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;

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
 * Most of these questions arrive when the extension dispatches an outbox entry, and only
 * <code>getUserTask</code> asks in the application's own time.
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
 * <b>A changed user task.</b> Its report needs the assignee, the candidates and the dates, and
 * outside a listener job only the storage knows them. So the bridge builds no such report in the
 * application's transaction ({@link #reportsAChangedUserTaskRightAway}). The extension names the
 * task from what VanillaBP wrote down when it delivered it, and the bridge reads the task by its
 * key when the entry is dispatched. An empty answer there makes the extension ask again a little
 * later.
 * <p>
 * <b>The variables of a task read from the storage.</b> A record of a user task in the storage
 * carries no process variables. So where a details provider of the task reads one with
 * <code>&#64;TaskParam</code>, or where the task sits in a multi-instance element, the bridge asks
 * the storage for them in a second request. See {@link #variablesOfTheTask}.
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

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * Creates the bridge through which the cockpit reads one cluster.
   *
   * @param cluster The cluster this bridge reads
   * @param workflowTaskWiring VanillaBP's registry, which names the workflow aggregate's id
   *          variable of a BPMN process
   * @param election VanillaBP's election, asked only for what it wrote down when a workflow
   *          started
   * @param deployments What this extension read out of the models while wiring them
   * @param publisher The Business Cockpit extension, asked which variables the details providers
   *          of a task read. It is asked for when a task is read and not now, because the
   *          extension is built from the bridges
   */
  public Camunda8CockpitBridge(
      final Camunda8Clients.Cluster cluster,
      final WorkflowTaskWiring workflowTaskWiring,
      final WorkflowElection election,
      final Camunda8CockpitDeployments deployments,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.cluster = cluster;
    this.workflowTaskWiring = workflowTaskWiring;
    this.election = election;
    this.deployments = deployments;
    this.publisher = publisher;

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

    final UserTask read;
    try {
      read = cluster
          .client()
          .newUserTaskGetRequest(Long.parseLong(userTask.userTaskId()))
          .send()
          .join();
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      sayTheStorageHasNotWritten(
          "user task '%s' of aggregate '%s'"
              .formatted(userTask.userTaskId(), userTask.workflowAggregateId()),
          e);
      return Optional.empty();
    }
    final var variables = variablesOfTheTask(userTask, read);
    if (variables.isEmpty()) {
      return Optional.empty();
    }
    return Optional
        .of(read)
        .map(
            task -> UserTaskDetailsPrefill
                .builder()
                .variables(variables.get())
                .multiInstances(roundsOf(userTask, task, variables.get()))
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

  /**
   * The process variables a report of one task needs, read from the searchable storage.
   * <p>
   * A listener job carries the variables its worker asked for, and a record of a task in the
   * storage carries none. So the same names are asked for here: the variables the
   * details providers of the task read with <code>&#64;TaskParam</code>, and the variables of the
   * multi-instance elements around the task. Without them a later report of a task would give
   * such a parameter <code>null</code>, although the first report gave it the value. See decision
   * 28 in the repository's DECISIONS.md.
   * <p>
   * The request asks for the effective variables of the task. Those are the variables the task
   * sees, from its own scope up to its process instance, and where two scopes hold the same name
   * the inner one wins. That is what a job carries as well. Where nobody reads a variable, the
   * task sits in no multi-instance element of its own process, and no caller names the process by
   * an expression, nothing is asked. Such a caller may hand its rounds down to the task, so then
   * the variable which carries them is asked for as well.
   * <p>
   * The names are asked for with the workflow module and the BPMN process of the reference,
   * because the extension picks the details provider by those two.
   *
   * @param userTask The task as the cockpit addresses it
   * @param task The task as the storage holds it
   * @return The values by name, which may be empty. Empty as a whole where the storage holds no
   *         record of the task's variables yet. Then the report waits, as it waits for the task
   */
  private Optional<Map<String, Object>> variablesOfTheTask(
      final UserTaskReference userTask,
      final UserTask task) {

    final var names = new TreeSet<String>(
        publisher
            .get()
            .variablesTheDetailsProvidersRead(
                userTask.workflowModuleId(), userTask.bpmnProcessId(), userTask.taskDefinition(),
                userTask.bpmnTaskId()));
    final var multiInstances = multiInstancesOf(userTask, task);
    final var chain = multiInstances
        .map(registry -> registry.chainOf(task.getBpmnProcessId(), task.getElementId()))
        .orElse(List.of());
    // a process called by an expression may sit in rounds of its caller, which no chain of its
    // own model shows. The caller hands them down in a variable, and the adapter knows which
    // processes such a variable can reach
    final var mayInheritRounds = multiInstances
        .map(registry -> registry.mayBeHandedAChain(task.getBpmnProcessId()))
        .orElse(false);
    if (names.isEmpty() && chain.isEmpty() && !mayInheritRounds) {
      return Optional.of(Map.of());
    }
    // the adapter's own list for the element, the same one the worker of the listener uses. The
    // aggregate's id is left out, because no report reads it from here
    Camunda8FetchVariables.collect(names, null, chain);

    final List<Variable> stored;
    try {
      stored = cluster
          .client()
          .newUserTaskEffectiveVariableSearchRequest(task.getUserTaskKey())
          .filter(filter -> filter.name(name -> name.in(List.copyOf(names))))
          // a provider gets the whole value, as it does from a job. The storage shortens long
          // values unless it is told not to
          .withFullValues()
          .send()
          .join()
          .items();
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      sayTheStorageHasNotWritten(
          "the variables of user task '%s' of aggregate '%s'"
              .formatted(userTask.userTaskId(), userTask.workflowAggregateId()),
          e);
      return Optional.empty();
    }
    final var jsonMapper = cluster.client().getConfiguration().getJsonMapper();
    final var byName = new LinkedHashMap<String, Object>();
    stored
        .forEach(
            variable -> byName
                .put(variable.getName(), jsonMapper.fromJson(variable.getValue(), Object.class)));
    return Optional.of(byName);

  }

  /**
   * The multi-instance elements of the workflow module a task belongs to, as the adapter collected
   * them while it wired the module's models.
   *
   * @param userTask The task as the cockpit addresses it
   * @param task The task as the storage holds it
   * @return The elements, or empty where the module has not started on this cluster or the record
   *         names no process or no element to look them up by
   */
  private Optional<Camunda8MultiInstance.Registry> multiInstancesOf(
      final UserTaskReference userTask,
      final UserTask task) {

    if ((task.getBpmnProcessId() == null) || (task.getElementId() == null)) {
      return Optional.empty();
    }
    return deployments.multiInstancesOf(adapterId(), userTask.workflowModuleId());

  }

  /**
   * The rounds of the multi-instance elements around a task, read the same way as from a listener
   * job.
   *
   * @param userTask The task as the cockpit addresses it
   * @param task The task as the storage holds it
   * @param variables The variables read for it
   * @return The rounds, outermost first. Empty where the task is not part of one
   */
  private Map<String, HandlerMultiInstance> roundsOf(
      final UserTaskReference userTask,
      final UserTask task,
      final Map<String, Object> variables) {

    return multiInstancesOf(userTask, task)
        .map(
            multiInstances -> Camunda8CockpitJobHandler
                .multiInstancesOf(multiInstances, task.getBpmnProcessId(), task.getElementId(), variables))
        .orElse(Map.of());

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
      sayTheStorageHasNotWritten(
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
   * Says that the storage holds no record of a workflow or a user task the Business Cockpit
   * asked about by its key.
   * <p>
   * The dispatch of a changed aggregate or a changed user task asks this way, and it asks again a
   * little later while the answer is empty. The extension says so in its own log, once at the
   * first attempt and once more where it gives up, so a line per attempt here would only repeat
   * it. <code>BusinessCockpitService.getUserTask</code> reads a task by its key as well, but only
   * after a search found it, and an empty search says both readings out loud itself.
   *
   * @param what What was read, for the message
   * @param notFound The cluster's answer, or <code>null</code> for an empty search
   */
  private void sayTheStorageHasNotWritten(
      final String what,
      final RuntimeException notFound) {

    logger
        .debug(
            "Camunda8[{}]: the cluster's searchable storage holds no record of {} yet. Either the exporter which writes that storage has not caught up with it, or there is no such record. The Business Cockpit asks again a little later.",
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
      sayTheStorageHasNotWritten(
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

  /**
   * <code>false</code>: the report of a changed user task needs its assignee, its candidates and
   * its dates, and outside a listener job only the searchable storage knows them. That storage
   * may not hold a task created a moment ago, or any task while the exporter stands still. So the
   * Business Cockpit extension writes an entry without a report in the application's
   * transaction, and builds the report when the entry is dispatched. There it calls
   * {@link #prefilledUserTaskDetails}, which reads the task by its key, and asks again a little
   * later while the storage has not written the task.
   */
  @Override
  public boolean reportsAChangedUserTaskRightAway() {

    return false;

  }

  @Override
  public List<UserTaskReference> userTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final List<String> userTaskIds) {

    final var processes = processesOfTheCase(workflowModuleId, bpmnProcessId);
    if ((userTaskIds == null) || userTaskIds.isEmpty()) {
      final var found = searchUserTasks(
          workflowModuleId, bpmnProcessId, workflowAggregateId, processes, null, true);
      if (found.isEmpty()) {
        sayTheStorageHoldsNoRecord(
            "active user tasks", workflowModuleId, bpmnProcessId, workflowAggregateId);
      }
      return found
          .stream()
          .map(task -> referenceOf(workflowModuleId, workflowAggregateId, processes, task))
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
                        workflowModuleId, bpmnProcessId, workflowAggregateId, processes,
                        userTaskKey)
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
        workflowModuleId, bpmnProcessId, workflowAggregateId,
        processesOfTheCase(workflowModuleId, bpmnProcessId), userTaskKey.get());
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
   * @param processes The processes of the case, see {@link #processesOfTheCase}
   * @param userTaskKey The task's key as the cluster counts it
   * @return The task, or empty where the storage holds no such task of that aggregate
   */
  private Optional<UserTaskReference> searchUserTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Map<String, String> processes,
      final Long userTaskKey) {

    return searchUserTasks(
        workflowModuleId, bpmnProcessId, workflowAggregateId, processes, userTaskKey, false)
        .stream()
        .findFirst()
        .map(task -> referenceOf(workflowModuleId, workflowAggregateId, processes, task));

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
      sayTheStorageHasNotWritten(
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
   * <p>
   * A task of a case sits in the process of the case or in a process the case calls with a call
   * activity. The second kind is how VanillaBP splits a large process into smaller ones: the
   * called process shares the case's workflow aggregate, and the cluster copies the aggregate's
   * id variable into it, so the variable condition finds its tasks as well. So the search names
   * every process which may hold a task of the case. See {@link #processesOfTheCase}.
   * <p>
   * The processes go INTO the search, and the search is not run without them and sorted out
   * afterwards. Three reasons:
   * <ul>
   * <li>Without a process condition the search reads every task of the tenant whose process
   * carries a variable of that name and value. Under <code>name-clash-avoidance: none</code>
   * other workflow modules and other applications share that tenant, and an aggregate id like
   * <code>1</code> is common. Such tasks would come back on every page and push the wanted ones
   * off it.</li>
   * <li>A called process with a workflow aggregate of its own still gets the caller's variables
   * copied into it. Its tasks must not be counted as tasks of the caller's case.</li>
   * <li>The search names each process the way the cluster knows it, which under
   * <code>use-prefix</code> carries the module's prefix. So it asks for exactly what this
   * extension wired, and nothing which only looks like it.</li>
   * </ul>
   * The tenant and the aggregate's id are spelled the way the Camunda 8 adapter spells them for
   * its own searches, so the two never ask different questions about one case.
   *
   * @param processes The processes of the case, see {@link #processesOfTheCase}
   * @param userTaskKey One task's key, or <code>null</code> for all of them
   * @param activeOnly Whether only a task somebody can still work on counts
   */
  private List<UserTask> searchUserTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Map<String, String> processes,
      final Long userTaskKey,
      final boolean activeOnly) {

    final var tenantId = cluster.scope().tenantIdOf(workflowModuleId);
    final var aggregateIdName = aggregateIdNameOf(workflowModuleId, bpmnProcessId);
    return Camunda8UserTaskSearch
        .search(
            cluster.client(),
            processes.keySet(),
            filter -> {
              if (tenantId != null) {
                filter.tenantId(tenantId);
              }
              // the aggregate's id as a variable of the process instance. A task holds no copy
              // of it. It also means a key somebody guessed reads no task of another case
              filter
                  .processInstanceVariables(
                      Map
                          .of(
                              aggregateIdName,
                              Camunda8VariableFilters.aggregateIdSearchValue(workflowAggregateId)));
              // which tasks of that aggregate are meant is this bridge's own question
              if (userTaskKey != null) {
                filter.userTaskKey(userTaskKey);
              }
              if (activeOnly) {
                filter.state(UserTaskState.CREATED);
              }
            })
        .stream()
        // the search asked for these processes only. Checking it costs nothing, and a task this
        // bridge cannot name back would be reported under a process nobody wrote
        .filter(task -> processes.containsKey(task.getBpmnProcessId()))
        .toList();

  }

  /**
   * The BPMN processes a task of one case may sit in: the process of the case and every process
   * which shares its workflow aggregate and which this extension wired on this cluster. That is a
   * process the case calls with a call activity, declared as one of the
   * <code>secondaryBpmnProcesses</code> of the case's workflow service.
   * <p>
   * The set comes from the shared aggregate and not from the call activities of the models. A
   * call activity may name its process by an expression. Which process it reaches is then decided
   * per instance, so no graph of calls holds it. The Camunda 8 adapter answers the same question
   * the same way: a call by expression may reach every process of the module with the same
   * aggregate.
   * <p>
   * A process with a workflow aggregate of its own is left out, although the case may call it.
   * It is a case of its own, and its tasks are reported for its own aggregate.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The process of the case, as the application wrote it
   * @return The plain process id by the id the cluster knows, the case's process first
   */
  private Map<String, String> processesOfTheCase(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var processes = new LinkedHashMap<String, String>();
    // the case's own process even where nothing of this module was wired yet. That is the
    // search this bridge always ran, so an early question is answered no worse than before
    processes.put(cluster.scope().scopedProcessIdOf(workflowModuleId, bpmnProcessId), bpmnProcessId);
    deployments
        .bpmnProcessIdsByScopedIdOf(adapterId(), workflowModuleId)
        .forEach((
            scopedBpmnProcessId,
            wiredBpmnProcessId) -> {
          if (workflowTaskWiring
              .workflowsShareTheWorkflowAggregate(
                  workflowModuleId, bpmnProcessId, wiredBpmnProcessId)) {
            processes.putIfAbsent(scopedBpmnProcessId, wiredBpmnProcessId);
          }
        });
    return processes;

  }

  /**
   * What the cockpit is told about one task found in the searchable storage.
   * <p>
   * <b>The process of the task, not of the case.</b> <code>bpmnProcessId</code>,
   * <code>processVersion</code>, <code>taskDefinition</code> and <code>bpmnTaskId</code> all name
   * the process the task sits in. For a task of a called process that is the CALLED process, not
   * the case's process which this bridge was asked about. The four values belong together, and
   * every reader of them expects the process of the task:
   * <ul>
   * <li>The Business Cockpit extension picks the details provider and builds the platform's
   * handler call by (workflow module, BPMN process). A class serves every process it declares,
   * so a workflow service which declares both the case's process and the called one is found by
   * either id. The wrong id therefore does not show while a provider is bound, and the same goes
   * for the names of the <code>&#64;TaskParam</code> parameters. A provider of a workflow service
   * which declares the called process only, and not the case's process, is not found by the
   * case's id at all.</li>
   * <li>The wrong id breaks whatever VanillaBP keeps per process. A provider's version range is
   * checked against the version of the process it is declared for, per (class, process). The version of the called model paired with the id of the
   * case's process would match a range nobody wrote, so the wrong provider runs, or only one
   * naming no version, or none. The catalogue of version tags, the delivery record and the
   * election's hint are kept per process as well. So is the name of an element, which VanillaBP
   * looks up per (workflow module, process, element): asked with the case's process, it answers
   * no name for an element of a called process, and says nothing about it.</li>
   * <li><code>taskDefinition</code> is turned back into what the application wrote with the
   * process the task belongs to, because <code>use-prefix</code> scopes it by that process.
   * <code>bpmnTaskId</code> is an element id, which exists in the called model only.</li>
   * <li>The cockpit server stores all four, the GUI filters and sorts the task list by them and
   * picks the form by (workflow module, task definition).</li>
   * </ul>
   * The platform's rule is the same: a task and an element are always named by the process which
   * holds them, for a called process by its own id. See decision 29 in the repository's
   * DECISIONS.md. VanillaBP
   * binds a <code>&#64;WorkflowTask</code> of a called process that way on Camunda 8, Camunda 7
   * and the Process-Engine-API alike. So does the listener of this extension, which reads all
   * four values off the job of the task. A task reported first by its listener and later from the
   * storage is therefore served by the same details provider both times.
   * <p>
   * <b>The case, not the process.</b> <code>workflowId</code> is the business case, which is the
   * instance at the top of the call hierarchy (see decision 3 in the repository's DECISIONS.md),
   * the same instance the listener
   * reports. <code>subWorkflowId</code>, filled in by {@link #prefilledUserTaskDetails}, is the
   * instance the task really sits in where that is another one. The case is tied to the
   * aggregate by <code>workflowAggregateId</code> and <code>workflowId</code> and never by the
   * process id.
   *
   * @param workflowModuleId The workflow module
   * @param workflowAggregateId The aggregate the search was about
   * @param processes The processes searched, by the id the cluster knows
   * @param task The task as the storage holds it
   * @return The reference
   */
  private UserTaskReference referenceOf(
      final String workflowModuleId,
      final String workflowAggregateId,
      final Map<String, String> processes,
      final UserTask task) {

    // the search asked for these ids only, so the task's process is one of them
    final var bpmnProcessId = processes.get(task.getBpmnProcessId());
    final var root = cluster.callHierarchy().rootProcessInstanceKeyOf(task);
    final var workflowId = root == null
        ? task.getProcessInstanceKey()
        : root;
    return new UserTaskReference(
        adapterId(), workflowModuleId, bpmnProcessId, processVersionOf(
            task.getProcessDefinitionVersion()), workflowAggregateId, String
                .valueOf(workflowId), String.valueOf(task.getUserTaskKey()), cluster
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
