package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.camunda.client.api.search.filter.UserTaskVariableFilter;
import io.camunda.client.api.search.filter.builder.StringProperty;
import io.camunda.client.api.search.request.UserTaskEffectiveVariableSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the bridge answers without asking the cluster at all.
 * <p>
 * Everything else it does is a request to a cluster and is tested against a real one. What stays
 * here is the handful of answers a cluster would only be able to confirm, and the one which must
 * never reach it.
 * <p>
 * The filter a search is narrowed with is asserted here as well, although a cluster runs the
 * search. A condition spelled differently does not fail against a cluster. The search answers
 * nothing, and nothing reads exactly like a workflow which was never started.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitBridgeTest {

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskWiring workflowTaskWiring = mock(WorkflowTaskWiring.class);

  /**
   * What VanillaBP wrote down when it started a workflow. Unless a test says otherwise it knows
   * nothing, which is the answer for every workflow started before VanillaBP wrote such notes.
   */
  private final WorkflowElection election = mock(WorkflowElection.class);

  /**
   * The Business Cockpit extension, asked which variables the details providers of a task read.
   * Unless a test says otherwise nobody reads any, which is what a mock of the interface answers.
   */
  private final BusinessCockpitEventPublisher publisher = mock(BusinessCockpitEventPublisher.class);

  /** What this extension read out of the models of this test while it wired them. */
  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  /**
   * How the cluster of this test spells what the application wrote: the workflow module runs
   * under <code>use-prefix</code> unless a test says otherwise, so the process id the filter
   * carries is not the one the caller asked about.
   */
  private final NameClashAvoidanceSupport scoping = mock(NameClashAvoidanceSupport.class);

  private static final String SCOPED_PROCESS_ID = "cockpit-module-CockpitProcess";

  private static final String AGGREGATE_ID = "4711";

  private static final String AGGREGATE_ID_NAME = "loanId";

  /** The workflow a case is, which a call activity of it started the instance below. */
  private static final String CALLING_INSTANCE = "2251799813685331";

  /** The instance the called process runs in, which is a step of that case and no case itself. */
  private static final Long CALLED_INSTANCE = 2251799813685341L;

  private static final String USER_TASK_ID = "2251799813685350";

  /**
   * What the bridge wrote while a test ran.
   * <p>
   * An empty answer of the searchable storage is answered with a line in the log and nothing else,
   * which decision 8 asks for, so the log is the only place a test can read that answer from.
   */
  private final ListAppender<ILoggingEvent> linesTheBridgeWrote = new ListAppender<>();

  @BeforeEach
  public void listenToTheBridge() {

    linesTheBridgeWrote.start();
    theLoggerOfTheBridge().addAppender(linesTheBridgeWrote);

  }

  @AfterEach
  public void stopListening() {

    theLoggerOfTheBridge().detachAppender(linesTheBridgeWrote);
    linesTheBridgeWrote.stop();

  }

  private static Logger theLoggerOfTheBridge() {

    return (Logger) LoggerFactory.getLogger(Camunda8CockpitBridge.class);

  }

  /**
   * @param level Which kind of line is meant
   * @return What the bridge wrote at that level while this test ran
   */
  private List<String> whatTheBridgeSaid(
      final Level level) {

    return linesTheBridgeWrote.list
        .stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  private Camunda8CockpitBridge bridge() {

    final var clients = new Camunda8Clients(clientFactories, null);
    return new Camunda8CockpitBridge(
        clients.of("c8"), workflowTaskWiring, election, deployments, () -> publisher);

  }

  /**
   * A bridge of a cluster which prefixes what it is told, answering the search with nothing.
   *
   * @return The bridge
   */
  private Camunda8CockpitBridge bridgeOfAScopedModule() {

    return bridgeOfAScopedModule(List.of());

  }

  /**
   * A bridge of a cluster which prefixes what it is told.
   *
   * @param found What the cluster answers the workflow search with
   * @return The bridge
   */
  private Camunda8CockpitBridge bridgeOfAScopedModule(
      final List<ProcessInstance> found) {

    when(scoping.scopedProcessId(MODULE_ID, PROCESS_ID, "c8")).thenReturn(SCOPED_PROCESS_ID);
    when(workflowTaskWiring.resolveWorkflowAggregateIdName(MODULE_ID, PROCESS_ID))
        .thenReturn(AGGREGATE_ID_NAME);
    final var search = clientFactories
        .getFactory("c8")
        .getClient()
        .newProcessInstanceSearchRequest();
    // a search request answers itself, so what the cluster answers is stubbed once instead of
    // through the deep stub of one particular call of 'filter' - a deep stub of a call with
    // another argument is another mock, and that one answers an empty list
    when(search.filter(any(Consumer.class))).thenReturn(search);
    when(search.send().join().items()).thenReturn(found);
    // the stubbing above called 'filter' itself. What the test asserts is the one call the
    // bridge makes, because a second call would replace the first rather than add to it
    clearInvocations(search);
    final var clients = new Camunda8Clients(clientFactories, scoping);
    return new Camunda8CockpitBridge(
        clients.of("c8"), workflowTaskWiring, election, deployments, () -> publisher);

  }

  /**
   * A bridge of a cluster whose searchable storage answers the user-task search with what a test
   * says, and nothing else.
   *
   * @param found The tasks the search answers
   * @return The bridge
   */
  private Camunda8CockpitBridge bridgeSearchingUserTasks(
      final List<UserTask> found) {

    when(scoping.scopedProcessId(MODULE_ID, PROCESS_ID, "c8")).thenReturn(SCOPED_PROCESS_ID);
    when(workflowTaskWiring.resolveWorkflowAggregateIdName(MODULE_ID, PROCESS_ID))
        .thenReturn(AGGREGATE_ID_NAME);
    final var search = clientFactories.getFactory("c8").getClient().newUserTaskSearchRequest();
    // a search request answers itself, like the workflow search above, so what the cluster
    // answers is stubbed once instead of through the deep stub of one particular call
    when(search.filter(any(Consumer.class))).thenReturn(search);
    when(search.send().join().items()).thenReturn(found);
    clearInvocations(search);
    final var clients = new Camunda8Clients(clientFactories, scoping);
    return new Camunda8CockpitBridge(
        clients.of("c8"), workflowTaskWiring, election, deployments, () -> publisher);

  }

  /**
   * One workflow the cluster's searchable storage holds.
   *
   * @param version The version of the model it runs on, or <code>null</code> where the storage
   *          names none
   * @return The record
   */
  private static ProcessInstance aWorkflowOnVersion(
      final Integer version) {

    final var instance = mock(ProcessInstance.class);
    when(instance.getProcessInstanceKey()).thenReturn(Long.parseLong(CALLING_INSTANCE));
    when(instance.getProcessDefinitionVersion()).thenReturn(version);
    // nobody called this workflow, which is how a business case looks. An unstubbed answer
    // would be zero rather than nothing, and zero reads as a workflow which was called
    when(instance.getParentProcessInstanceKey()).thenReturn(null);
    return instance;

  }

  /**
   * @return What the search request was narrowed with, run against a filter which records it
   */
  private ProcessInstanceFilter theFilterOfTheSearch() {

    final ArgumentCaptor<Consumer<ProcessInstanceFilter>> narrowing = ArgumentCaptor.captor();
    verify(
        clientFactories.getFactory("c8").getClient().newProcessInstanceSearchRequest())
        .filter(narrowing.capture());
    final var filter = mock(ProcessInstanceFilter.class);
    narrowing.getValue().accept(filter);
    return filter;

  }

  @Test
  @DisplayName("The bridge says which configured adapter it serves and of which BPMS")
  public void theBridgeNamesItsAdapter() {

    assertEquals("c8", bridge().adapterId());
    assertEquals("camunda8", bridge().adapterType());

  }

  @Test
  @DisplayName("A user-task id no Camunda 8 cluster ever handed out is answered without asking one")
  public void anIdOfAnotherBpmsIsNotLookedUp() {

    // during a migration an application still holds ids the other BPMS gave it, and none of
    // them is a task of this cluster. Asking would spend a request to be told so
    final var found = bridge()
        .userTaskOfAggregate(MODULE_ID, PROCESS_ID, "4711", "a-camunda-7-task-id");

    assertTrue(found.isEmpty());
    verifyNoInteractions(workflowTaskWiring);

  }

  @Test
  @DisplayName("The task of a called process is prefilled with the case above it")
  public void aTaskOfACalledProcessKeepsItsCase() {

    final var task = mock(UserTask.class, RETURNS_DEEP_STUBS);
    // what the cluster says: the task sits in the instance the call activity started
    when(task.getProcessInstanceKey()).thenReturn(CALLED_INSTANCE);
    when(task.getProcessDefinitionVersion()).thenReturn(1);
    // the prefill copies both lists, and a cluster answers with empty ones rather than none
    when(task.getCandidateUsers()).thenReturn(List.of());
    when(task.getCandidateGroups()).thenReturn(List.of());
    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newUserTaskGetRequest(Long.parseLong(USER_TASK_ID))
            .send()
            .join())
        .thenReturn(task);

    final var prefill = bridge()
        .prefilledUserTaskDetails(
            new UserTaskReference(
                "c8", MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, CALLING_INSTANCE, USER_TASK_ID, "handle", "Handle"))
        .orElseThrow();

    // the case is the calling workflow, and the instance the task sits in is the step below it
    assertEquals(CALLING_INSTANCE, prefill.workflowId());
    assertEquals(String.valueOf(CALLED_INSTANCE), prefill.subWorkflowId());

  }

  @Test
  @DisplayName("A workflow read from the storage is named by the workflow aggregate's id")
  public void aWorkflowIsNamedByItsAggregateId() {

    theModelIsNamed("The cockpit process");
    final var instance = mock(ProcessInstance.class, RETURNS_DEEP_STUBS);
    when(instance.getProcessDefinitionVersion()).thenReturn(2);
    when(instance.getProcessDefinitionName()).thenReturn("The name the storage holds");
    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newProcessInstanceGetRequest(Long.parseLong(CALLING_INSTANCE))
            .send()
            .join())
        .thenReturn(instance);

    // a reference without a version, so the storage is read for it
    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, null, AGGREGATE_ID, CALLING_INSTANCE))
        .orElseThrow();

    // to VanillaBP a business key is a business key only where it says what the aggregate's @Id
    // attribute says. So the report names that id, whatever a cluster holds beside it, and it
    // names the same one whether it was built from a listener job or read here
    assertEquals(AGGREGATE_ID, prefill.businessId());
    assertEquals("2", prefill.bpmnProcessVersion());
    // the name of the model this extension wired, on every way, so that no way needs the storage
    // for it
    assertEquals("The cockpit process", prefill.bpmnProcessName());

  }

  /** The model of the process of this test, as this extension read it while wiring it. */
  private void theModelIsNamed(
      final String name) {

    deployments
        .register(
            "c8",
            MODULE_ID,
            new WiredListener("listener", SCOPED_PROCESS_ID, PROCESS_ID, PROCESS_ID, name, name, AGGREGATE_ID_NAME));

  }

  @Test
  @DisplayName("A workflow reference with its version is prefilled without asking the cluster")
  public void aReferenceWithItsVersionIsPrefilledWithoutTheStorage() {

    theModelIsNamed("The cockpit process");

    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, "2", AGGREGATE_ID, CALLING_INSTANCE))
        .orElseThrow();

    assertEquals("2", prefill.bpmnProcessVersion());
    assertEquals(AGGREGATE_ID, prefill.businessId());
    assertEquals("The cockpit process", prefill.bpmnProcessName());
    verifyNoInteractions(client());

  }

  @Test
  @DisplayName("A task the searchable storage holds no record of is answered with nothing")
  public void aTaskTheStorageDoesNotHoldIsAnsweredWithNothing() {

    // asked outside an event, which is what the dispatch of a changed user task and
    // BusinessCockpitService.getUserTask do. The empty answer is what each of them works with
    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newUserTaskGetRequest(Long.parseLong(USER_TASK_ID))
            .send()
            .join())
        .thenThrow(new ClientHttpException(404, "Not Found"));

    final var prefill = bridge()
        .prefilledUserTaskDetails(
            new UserTaskReference(
                "c8", MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, CALLING_INSTANCE, USER_TASK_ID, "handle", "Handle"));

    assertTrue(prefill.isEmpty());
    // the dispatch of a changed user task asks this way and asks again a little later, and the
    // extension says so in its own log. getUserTask reads a task by its key only after a search
    // found it, and an empty search warns itself. So a warning here would only repeat one
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());
    final var said = whatTheBridgeSaid(Level.DEBUG);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains(USER_TASK_ID), said.getFirst());

  }

  /**
   * A task as the storage holds it, read by its key.
   *
   * @return The task
   */
  private UserTask aTaskTheStorageHolds() {

    final var task = mock(UserTask.class, RETURNS_DEEP_STUBS);
    when(task.getUserTaskKey()).thenReturn(Long.parseLong(USER_TASK_ID));
    when(task.getProcessInstanceKey()).thenReturn(Long.parseLong(CALLING_INSTANCE));
    when(task.getBpmnProcessId()).thenReturn(SCOPED_PROCESS_ID);
    when(task.getElementId()).thenReturn("Handle");
    when(task.getProcessDefinitionVersion()).thenReturn(1);
    when(task.getCandidateUsers()).thenReturn(List.of());
    when(task.getCandidateGroups()).thenReturn(List.of());
    when(client().newUserTaskGetRequest(Long.parseLong(USER_TASK_ID)).send().join()).thenReturn(task);
    return task;

  }

  private static UserTaskReference theTaskOfTheCase() {

    return new UserTaskReference(
        "c8", MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, CALLING_INSTANCE, USER_TASK_ID, "handle", "Handle");

  }

  @Test
  @DisplayName("A task read from the storage carries the variables its details providers read")
  public void aTaskReadFromTheStorageCarriesTheVariablesItsProvidersRead() {

    aTaskTheStorageHolds();
    when(publisher.variablesTheDetailsProvidersRead(MODULE_ID, PROCESS_ID, "handle", "Handle"))
        .thenReturn(List.of("customer"));
    final var search = client().newUserTaskEffectiveVariableSearchRequest(Long.parseLong(USER_TASK_ID));
    // a search request answers itself, see bridgeOfAScopedModule
    when(search.filter(any(Consumer.class))).thenReturn(search);
    when(search.withFullValues()).thenReturn(search);
    final var customer = mock(Variable.class);
    when(customer.getName()).thenReturn("customer");
    when(customer.getValue()).thenReturn("\"Tilda\"");
    when(search.send().join().items()).thenReturn(List.of(customer));
    when(client().getConfiguration().getJsonMapper().fromJson("\"Tilda\"", Object.class))
        .thenReturn("Tilda");
    clearInvocations(search);

    final var prefill = bridge().prefilledUserTaskDetails(theTaskOfTheCase()).orElseThrow();

    // the same value a listener job of that task carries, so a later report of the task does not
    // overwrite what the first one said
    assertEquals(Map.of("customer", "Tilda"), prefill.variables());
    // the long values in full, as a job carries them
    verify(search).withFullValues();

  }

  @Test
  @DisplayName("A task whose details providers read no variable costs no second request")
  public void aTaskNobodyReadsAVariableOfCostsNoSecondRequest() {

    aTaskTheStorageHolds();

    final var prefill = bridge().prefilledUserTaskDetails(theTaskOfTheCase()).orElseThrow();

    assertTrue(prefill.variables().isEmpty(), prefill.variables().toString());
    verify(client(), never()).newUserTaskEffectiveVariableSearchRequest(anyLong());

  }

  @Test
  @DisplayName("A task whose variables the storage holds no record of is answered with nothing")
  public void aTaskWhoseVariablesTheStorageDoesNotHoldIsAnsweredWithNothing() {

    aTaskTheStorageHolds();
    when(publisher.variablesTheDetailsProvidersRead(MODULE_ID, PROCESS_ID, "handle", "Handle"))
        .thenReturn(List.of("customer"));
    final var search = client().newUserTaskEffectiveVariableSearchRequest(Long.parseLong(USER_TASK_ID));
    when(search.filter(any(Consumer.class))).thenReturn(search);
    when(search.withFullValues()).thenReturn(search);
    when(search.send().join()).thenThrow(new ClientHttpException(404, "Not Found"));

    final var prefill = bridge().prefilledUserTaskDetails(theTaskOfTheCase());

    // a report without the value would overwrite what the first report said. So it waits, as it
    // waits for a task the storage has not written yet, and the dispatch asks again later
    assertTrue(prefill.isEmpty());
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());
    final var said = whatTheBridgeSaid(Level.DEBUG);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains("variables of user task"), said.getFirst());

  }

  @Test
  @DisplayName("A task of a process called by an expression reads the rounds its caller handed down")
  public void aTaskOfAProcessCalledByAnExpressionReadsTheRoundsHandedDown() {

    aTaskTheStorageHolds();
    // a caller of the module names this process by an expression. Nobody reads a variable, and
    // the task sits in no multi-instance element of its own process, so only the caller's
    // rounds make the variables worth asking for
    final var callerProcessId = "cockpit-module-CallingProcess";
    final var multiInstances = new Camunda8MultiInstance.Registry();
    multiInstances.registerCallByExpression(callerProcessId, SCOPED_PROCESS_ID);
    deployments.rememberMultiInstances("c8", MODULE_ID, multiInstances);
    final var search = client().newUserTaskEffectiveVariableSearchRequest(Long.parseLong(USER_TASK_ID));
    when(search.filter(any(Consumer.class))).thenReturn(search);
    when(search.withFullValues()).thenReturn(search);
    final var handedDown = mock(Variable.class);
    when(handedDown.getName()).thenReturn(Camunda8MultiInstance.CHAIN_VARIABLE);
    when(handedDown.getValue()).thenReturn("the chain as JSON");
    when(search.send().join().items()).thenReturn(List.of(handedDown));
    // the second round of three, as the cluster counts it: from one
    when(client().getConfiguration().getJsonMapper().fromJson("the chain as JSON", Object.class))
        .thenReturn(
            List
                .of(
                    Map
                        .of(
                            "process", callerProcessId, "levels", List
                                .of(Map.of("element", "CallInRounds", "index", 2, "total", 3, "item", "south")))));
    clearInvocations(search);

    final var prefill = bridge().prefilledUserTaskDetails(theTaskOfTheCase()).orElseThrow();

    // the round a listener job of the task reports as well
    final var round = prefill.multiInstances().get("CallInRounds");
    assertEquals("south", round.element(), prefill.multiInstances().toString());
    assertEquals(1, round.index(), prefill.multiInstances().toString());
    assertEquals(3, round.total(), prefill.multiInstances().toString());
    assertEquals(List.of(Camunda8MultiInstance.CHAIN_VARIABLE), theNamesAskedFor(search));

  }

  /**
   * @param search The search for the variables of a task
   * @return The names the search was narrowed to
   */
  @SuppressWarnings("unchecked")
  private static List<String> theNamesAskedFor(
      final UserTaskEffectiveVariableSearchRequest search) {

    final ArgumentCaptor<Consumer<UserTaskVariableFilter>> narrowing = ArgumentCaptor.captor();
    verify(search).filter(narrowing.capture());
    final var filter = mock(UserTaskVariableFilter.class);
    narrowing.getValue().accept(filter);
    final ArgumentCaptor<Consumer<StringProperty>> byName = ArgumentCaptor.captor();
    verify(filter).name(byName.capture());
    final var name = mock(StringProperty.class);
    byName.getValue().accept(name);
    final ArgumentCaptor<List<String>> names = ArgumentCaptor.captor();
    verify(name).in(names.capture());
    return names.getValue();

  }

  @Test
  @DisplayName("A changed user task is left to the dispatch, because only the storage knows its assignee")
  public void aChangedUserTaskIsLeftToTheDispatch() {

    assertFalse(bridge().reportsAChangedUserTaskRightAway());
    verifyNoInteractions(client());

  }

  @Test
  @DisplayName("A workflow the searchable storage holds no record of is answered with nothing")
  public void aWorkflowTheStorageDoesNotHoldIsAnsweredWithNothing() {

    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newProcessInstanceGetRequest(Long.parseLong(CALLING_INSTANCE))
            .send()
            .join())
        .thenThrow(new ClientHttpException(404, "Not Found"));

    // a reference which names no version, which is what a search of the storage or a note of a
    // start without a version gives
    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, null, AGGREGATE_ID, CALLING_INSTANCE));

    assertTrue(prefill.isEmpty());
    // only the dispatch of a changed aggregate asks this way, and the extension asks again and
    // says so in its own log. A warning per attempt here would only repeat it
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());
    assertEquals(1, whatTheBridgeSaid(Level.DEBUG).size(), whatTheBridgeSaid(Level.DEBUG).toString());

  }

  @Test
  @DisplayName("A cluster which is unreachable is not mistaken for a cluster holding nothing")
  public void anOutageIsNoEmptyResult() {

    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newUserTaskGetRequest(Long.parseLong(USER_TASK_ID))
            .send()
            .join())
        .thenThrow(new ClientHttpException(503, "Service Unavailable"));

    // an outage answered with nothing would make the cockpit quietly stop showing what is there
    assertThrows(
        ClientHttpException.class,
        () -> bridge()
            .prefilledUserTaskDetails(
                new UserTaskReference(
                    "c8", MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, CALLING_INSTANCE, USER_TASK_ID, "handle", "Handle")));

  }

  @Test
  @DisplayName("A search for the workflows of an aggregate names the process as the cluster knows it")
  public void theSearchIsScopedTheWayTheClusterStoresIt() {

    bridgeOfAScopedModule().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    final var filter = theFilterOfTheSearch();
    verify(filter).processDefinitionId(SCOPED_PROCESS_ID);
    // quoted, because the cluster stores every variable as JSON and compares against that JSON
    // word for word. The plain 4711 would match nothing
    verify(filter).variables(Map.of(AGGREGATE_ID_NAME, "\"4711\""));
    // 'use-prefix' puts no workflow module into a tenant, and a tenant nobody uses would
    // narrow the search to workflows which do not exist
    verify(filter, never()).tenantId(anyString());

  }

  @Test
  @DisplayName("The reference of a workflow names the version of the model it runs on")
  public void aWorkflowReferenceCarriesItsProcessVersion() {

    final var found = bridgeOfAScopedModule(List.of(aWorkflowOnVersion(3)))
        .workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    // the number the cluster counted up when the model was deployed, which is what picks
    // between details providers serving different versions of it
    assertEquals("3", found.getFirst().processVersion());

  }

  @Test
  @DisplayName("A workflow the cluster names no version for is referenced without one")
  public void aWorkflowWithoutAVersionIsReferencedWithoutOne() {

    final var found = bridgeOfAScopedModule(List.of(aWorkflowOnVersion(null)))
        .workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    // no version, rather than the text 'null'. Both lose against every version range, and only
    // one of them reads like a version somebody deployed
    assertNull(found.getFirst().processVersion());

  }

  @Test
  @DisplayName("A read of one task the storage holds no record of names both readings of it")
  public void aReadOfATaskTheStorageDoesNotHoldIsExplained() {

    final var found = bridgeSearchingUserTasks(List.of())
        .userTaskOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, USER_TASK_ID);

    assertTrue(found.isEmpty());
    // the answer is empty and the log is where it is explained, which is what decision 8 asks
    // for. A caller may not read the end of the task into it, so both readings are named
    final var said = whatTheBridgeSaid(Level.WARN);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains("two readings"), said.getFirst());
    assertTrue(said.getFirst().contains("exporter"), said.getFirst());
    assertTrue(said.getFirst().contains(USER_TASK_ID), said.getFirst());

  }

  @Test
  @DisplayName("The active tasks of an aggregate the storage holds none of are explained as well")
  public void anEmptyListOfActiveTasksIsExplained() {

    final var found = bridgeSearchingUserTasks(List.of())
        .userTasksOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, List.of());

    assertTrue(found.isEmpty());
    final var said = whatTheBridgeSaid(Level.WARN);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains("two readings"), said.getFirst());

  }

  @Test
  @DisplayName("A task named by its id and not held is explained once, not twice")
  public void aNamedTaskWhichIsMissingIsExplainedOnce() {

    final var found = bridgeSearchingUserTasks(List.of())
        .userTasksOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, List.of(USER_TASK_ID));

    assertTrue(found.isEmpty());
    // this branch searches instead of going through userTaskOfAggregate, which says the same
    // thing itself. Going through it would explain every missing task twice
    assertEquals(1, whatTheBridgeSaid(Level.WARN).size(), whatTheBridgeSaid(Level.WARN).toString());

  }

  @Test
  @DisplayName("An id no Camunda 8 cluster handed out is a clear answer and gets no warning")
  public void anIdOfAnotherBpmsIsNoSilence() {

    final var found = bridge()
        .userTasksOfAggregate(
            MODULE_ID, PROCESS_ID, AGGREGATE_ID, List.of("a-camunda-7-task-id"));

    assertTrue(found.isEmpty());
    // nothing was searched and nothing is ambiguous here, so there is nothing to warn about
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());
    verifyNoInteractions(workflowTaskWiring);

  }

  /** A process the case's process calls, which works on the same workflow aggregate. */
  private static final String CALLED_PROCESS_ID = "CalledProcess";

  private static final String SCOPED_CALLED_PROCESS_ID = "cockpit-module-CalledProcess";

  /** A process the case may call as well, which has a workflow aggregate of its own. */
  private static final String OWN_CASE_PROCESS_ID = "OwnCaseProcess";

  private static final String SCOPED_OWN_CASE_PROCESS_ID = "cockpit-module-OwnCaseProcess";

  /**
   * The module of this test wired three processes on this cluster: the case's process, a process
   * it calls on the same aggregate and a process with an aggregate of its own.
   */
  private void threeProcessesWereWired() {

    deployments
        .register(
            "c8", MODULE_ID,
            new WiredListener(
                "type-a", SCOPED_PROCESS_ID, PROCESS_ID, "Approve", "Approve", "Cockpit", AGGREGATE_ID_NAME, "approve"));
    deployments
        .register(
            "c8", MODULE_ID,
            new WiredListener(
                "type-b", SCOPED_CALLED_PROCESS_ID, CALLED_PROCESS_ID, "Handle", "Handle", "Called", AGGREGATE_ID_NAME, "handle"));
    deployments
        .register(
            "c8", MODULE_ID,
            new WiredListener(
                "type-c", SCOPED_OWN_CASE_PROCESS_ID, OWN_CASE_PROCESS_ID, "Check", "Check", "Own case", AGGREGATE_ID_NAME, "check"));
    when(workflowTaskWiring.workflowsShareTheWorkflowAggregate(MODULE_ID, PROCESS_ID, PROCESS_ID))
        .thenReturn(true);
    when(workflowTaskWiring.workflowsShareTheWorkflowAggregate(MODULE_ID, PROCESS_ID, CALLED_PROCESS_ID))
        .thenReturn(true);
    when(workflowTaskWiring.workflowsShareTheWorkflowAggregate(MODULE_ID, PROCESS_ID, OWN_CASE_PROCESS_ID))
        .thenReturn(false);

  }

  @Test
  @DisplayName("The task search names the case's process and every wired process sharing its aggregate")
  public void theTaskSearchNamesTheProcessesOfTheCase() {

    threeProcessesWereWired();

    bridgeSearchingUserTasks(List.of()).userTasksOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, List.of());

    // as the cluster knows them, so under use-prefix with the module's prefix. The process with
    // an aggregate of its own is a case of its own and is not asked for
    assertEquals(
        Set.of(SCOPED_PROCESS_ID, SCOPED_CALLED_PROCESS_ID),
        ProcessesSearched.byTheUserTaskSearch(clientFactories, "c8"));

  }

  @Test
  @DisplayName("A module wired nothing yet is searched in the case's process, as before")
  public void aModuleWiredNothingYetIsSearchedInTheCasesProcess() {

    bridgeSearchingUserTasks(List.of()).userTaskOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, USER_TASK_ID);

    assertEquals(Set.of(SCOPED_PROCESS_ID), ProcessesSearched.byTheUserTaskSearch(clientFactories, "c8"));

  }

  /**
   * One task the searchable storage holds.
   *
   * @param scopedBpmnProcessId The process it sits in, as the cluster knows it
   * @param scopedTaskDefinition Its external form reference, as the cluster knows it
   * @return The record
   */
  private static UserTask aStoredTask(
      final String scopedBpmnProcessId,
      final String scopedTaskDefinition) {

    final var task = mock(UserTask.class);
    when(task.getUserTaskKey()).thenReturn(Long.parseLong(USER_TASK_ID));
    when(task.getBpmnProcessId()).thenReturn(scopedBpmnProcessId);
    when(task.getProcessDefinitionVersion()).thenReturn(3);
    when(task.getElementId()).thenReturn("Handle");
    when(task.getExternalFormReference()).thenReturn(scopedTaskDefinition);
    return task;

  }

  @Test
  @DisplayName("A task of a called process is named by its own process and filed under the case")
  public void aTaskOfACalledProcessIsNamedByItsOwnProcess() {

    threeProcessesWereWired();
    final var task = aStoredTask(SCOPED_CALLED_PROCESS_ID, "cockpit-module-CalledProcess-handle");
    TasksInAHierarchy
        .isCalledBy(
            clientFactories, "c8", task, CALLED_INSTANCE, Long.parseLong(CALLING_INSTANCE));
    when(
        scoping
            .plainTaskDefinition(
                MODULE_ID, CALLED_PROCESS_ID, "cockpit-module-CalledProcess-handle", "c8"))
        .thenReturn("handle");

    final var found = bridgeSearchingUserTasks(List.of(task))
        .userTaskOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, USER_TASK_ID)
        .orElseThrow();

    // all four values name the process the task sits in, written the way the application wrote
    // it, so the listener's report and this one pick the same details provider
    assertEquals(CALLED_PROCESS_ID, found.bpmnProcessId());
    assertEquals("3", found.processVersion());
    assertEquals("handle", found.taskDefinition());
    assertEquals("Handle", found.bpmnTaskId());
    // and the workflow is the case at the top, as the listener reports it (decision 3)
    assertEquals(CALLING_INSTANCE, found.workflowId());
    assertEquals(AGGREGATE_ID, found.workflowAggregateId());

  }

  @Test
  @DisplayName("A task of the case's own process is filed under its own instance")
  public void aTaskOfTheCasesProcessIsFiledUnderItsOwnInstance() {

    threeProcessesWereWired();
    final var task = aStoredTask(SCOPED_PROCESS_ID, "approve");
    TasksInAHierarchy.isItsOwnRoot(clientFactories, "c8", task, Long.parseLong(CALLING_INSTANCE));

    final var found = bridgeSearchingUserTasks(List.of(task))
        .userTasksOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, List.of());

    assertEquals(1, found.size(), found.toString());
    assertEquals(PROCESS_ID, found.getFirst().bpmnProcessId());
    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());

  }

  @Test
  @DisplayName("A task of a process the search did not ask for is not reported")
  public void aTaskOfAnotherProcessIsNotReported() {

    threeProcessesWereWired();
    final var task = aStoredTask(SCOPED_OWN_CASE_PROCESS_ID, "check");
    TasksInAHierarchy
        .isCalledBy(
            clientFactories, "c8", task, CALLED_INSTANCE, Long.parseLong(CALLING_INSTANCE));

    final var found = bridgeSearchingUserTasks(List.of(task))
        .userTasksOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID, List.of());

    // the cluster would not answer it, as the search names other processes. Were it answered all
    // the same, there would be no plain id to report it by
    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("A module separated by tenants searches inside its tenant")
  public void theSearchOfATenantCarriesIt() {

    when(scoping.modeFor(MODULE_ID, null, "c8")).thenReturn(NameClashAvoidance.BY_ADAPTER);

    bridgeOfAScopedModule().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    verify(theFilterOfTheSearch()).tenantId(MODULE_ID);

  }

  /**
   * The cluster's searchable storage, asked by key for the workflow of this test, holds no record
   * of it yet. That is how a workflow looks a moment after it started.
   */
  private void theStorageHasNotWrittenTheWorkflowYet() {

    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newProcessInstanceGetRequest(Long.parseLong(CALLING_INSTANCE))
            .send()
            .join())
        .thenThrow(new ClientHttpException(404, "Not Found"));

  }

  /**
   * VanillaBP wrote down the key of the workflow of this test when it started it, and no version
   * yet. The adapter reports versions, so one may still come.
   */
  private void vanillaBpStartedTheWorkflow(
      final String workflowId) {

    vanillaBpStartedTheWorkflow(new WorkflowStart("c8", workflowId, null, true));

  }

  /** VanillaBP wrote down this start of the workflow of this test. */
  private void vanillaBpStartedTheWorkflow(
      final WorkflowStart start) {

    when(election.workflowStartOf(MODULE_ID, PROCESS_ID, AGGREGATE_ID)).thenReturn(Optional.of(start));

  }

  /** The client of the cluster of this test. */
  private io.camunda.client.CamundaClient client() {

    return clientFactories.getFactory("c8").getClient();

  }

  @Test
  @DisplayName("A workflow VanillaBP started without its version is left to the dispatch, which reads the storage")
  public void aWorkflowStartedAMomentAgoIsNotReportedWithoutItsVersion() {

    vanillaBpStartedTheWorkflow(CALLING_INSTANCE);
    theStorageHasNotWrittenTheWorkflowYet();
    final var bridge = bridge();
    clearInvocations(client());

    // what BusinessCockpitService.aggregateChanged asks in the application's transaction, for
    // instance from the first service task of the workflow: the key alone names no version, so
    // nothing is read there and the change is resolved when its entry is dispatched
    assertTrue(bridge.workflowsOfAggregateRightAway(MODULE_ID, PROCESS_ID, AGGREGATE_ID).isEmpty());
    verifyNoInteractions(client());

    // the dispatch, while the exporter has not written that workflow yet. The cluster answers 404
    final var found = bridge.workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    // a reference without the version would pick the wrong details provider, or none, and the
    // cockpit would replace what it shows with that report. So there is no reference, and the
    // extension asks again a little later
    assertTrue(found.isEmpty(), found.toString());
    // the workflow is read by the key VanillaBP wrote down, and not searched for
    verify(clientFactories.getFactory("c8").getClient(), never()).newProcessInstanceSearchRequest();
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());
    final var said = whatTheBridgeSaid(Level.DEBUG);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains(CALLING_INSTANCE), said.getFirst());

  }

  @Test
  @DisplayName("A workflow VanillaBP wrote down with its version is named in the application's transaction")
  public void aWorkflowWrittenDownWithItsVersionIsNamedRightAway() {

    vanillaBpStartedTheWorkflow(new WorkflowStart("c8", CALLING_INSTANCE, "5", true));

    final var found = bridge()
        .workflowsOfAggregateRightAway(MODULE_ID, PROCESS_ID, AGGREGATE_ID)
        .orElseThrow();

    assertEquals(1, found.size(), found.toString());
    assertEquals("5", found.getFirst().processVersion());
    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());
    verifyNoInteractions(client());

  }

  @Test
  @DisplayName("A workflow VanillaBP holds no note of is left to the dispatch, and nothing is searched in the application's transaction")
  public void aWorkflowWithoutANoteIsLeftToTheDispatch() {

    assertTrue(bridge().workflowsOfAggregateRightAway(MODULE_ID, PROCESS_ID, AGGREGATE_ID).isEmpty());
    verifyNoInteractions(client());

  }

  @Test
  @DisplayName("A workflow VanillaBP started is referenced with its version where the storage holds it")
  public void aWorkflowVanillaBpStartedCarriesItsVersion() {

    vanillaBpStartedTheWorkflow(CALLING_INSTANCE);
    final var instance = aWorkflowOnVersion(3);
    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newProcessInstanceGetRequest(Long.parseLong(CALLING_INSTANCE))
            .send()
            .join())
        .thenReturn(instance);

    final var found = bridge().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    // read by key, so the version still picks between details providers the way a search did
    assertEquals("3", found.getFirst().processVersion());
    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());
    verify(clientFactories.getFactory("c8").getClient(), never()).newProcessInstanceSearchRequest();

  }

  @Test
  @DisplayName("Where VanillaBP knows no key, the storage is searched and its silence is explained as before")
  public void anUnknownKeyFallsBackToTheSearch() {

    // the election's mock answers empty: never started, started before VanillaBP wrote such
    // notes, or the note is too old. Nobody can tell these apart, so the search decides
    final var found = bridgeOfAScopedModule()
        .workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    assertTrue(found.isEmpty());
    verify(theFilterOfTheSearch()).processDefinitionId(SCOPED_PROCESS_ID);
    // the dispatch of the change asks again, and the extension's log says so
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());
    assertEquals(1, whatTheBridgeSaid(Level.DEBUG).size(), whatTheBridgeSaid(Level.DEBUG).toString());

  }

  @Test
  @DisplayName("A key no Camunda 8 cluster handed out is not taken, the storage is searched instead")
  public void aKeyOfAnotherBpmsFallsBackToTheSearch() {

    vanillaBpStartedTheWorkflow("a-camunda-7-process-instance-id");

    final var found = bridgeOfAScopedModule(List.of(aWorkflowOnVersion(2)))
        .workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());
    verify(theFilterOfTheSearch()).processDefinitionId(SCOPED_PROCESS_ID);

  }

  @Test
  @DisplayName("A workflow VanillaBP wrote down with its version is referenced without asking the cluster")
  public void aWorkflowWrittenDownWithItsVersionIsReferencedAsItIs() {

    vanillaBpStartedTheWorkflow(new WorkflowStart("c8", CALLING_INSTANCE, "5", true));

    // what BusinessCockpitService.aggregateChanged asks right after the start, while the exporter
    // has not written the workflow yet. The note answers all of it
    final var found = bridge().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    assertEquals(1, found.size(), found.toString());
    assertEquals("5", found.getFirst().processVersion());
    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());
    assertEquals("c8", found.getFirst().adapterId());
    verifyNoInteractions(client());
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());

  }

  @Test
  @DisplayName("A workflow written down without a version, by an adapter which reports none, is read from the storage")
  public void aWorkflowOfAnAdapterReportingNoVersionsIsReadByItsKey() {

    // "never": on Camunda 8 that is an adapter which did not say it reports versions, and the
    // cluster still counts one for every workflow. So the storage is asked, as for "not yet"
    vanillaBpStartedTheWorkflow(new WorkflowStart("c8", CALLING_INSTANCE, null, false));
    final var instance = aWorkflowOnVersion(4);
    when(client().newProcessInstanceGetRequest(Long.parseLong(CALLING_INSTANCE)).send().join())
        .thenReturn(instance);

    final var found = bridge().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    assertEquals("4", found.getFirst().processVersion());
    verify(client(), never()).newProcessInstanceSearchRequest();

  }

  @Test
  @DisplayName("A workflow written down without a version, by an adapter which reports none, is not reported while the storage lacks it")
  public void aWorkflowOfAnAdapterReportingNoVersionsIsNotReportedWithoutOne() {

    vanillaBpStartedTheWorkflow(new WorkflowStart("c8", CALLING_INSTANCE, null, false));
    theStorageHasNotWrittenTheWorkflowYet();

    final var found = bridge().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    // a report without the version would empty the details the cockpit shows, and an application
    // on Camunda 8 may name a version in every details provider
    assertTrue(found.isEmpty(), found.toString());
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());

  }

  @Test
  @DisplayName("A note of a start on another adapter is not read on this cluster, the storage is searched instead")
  public void aNoteOfAnotherAdapterFallsBackToTheSearch() {

    vanillaBpStartedTheWorkflow(new WorkflowStart("another-c8", "2251799813699999", "7", true));

    final var found = bridgeOfAScopedModule(List.of(aWorkflowOnVersion(2)))
        .workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());
    assertEquals("2", found.getFirst().processVersion());
    verify(theFilterOfTheSearch()).processDefinitionId(SCOPED_PROCESS_ID);

  }

  @Test
  @DisplayName("A report of a workflow the storage has not written yet is built from the version VanillaBP wrote down")
  public void aReportOfAWorkflowNotWrittenYetIsBuiltFromTheReference() {

    deployments
        .register(
            "c8",
            MODULE_ID,
            new WiredListener(
                "listener", SCOPED_PROCESS_ID, PROCESS_ID, PROCESS_ID, "The cockpit process", "The cockpit process", AGGREGATE_ID_NAME));
    theStorageHasNotWrittenTheWorkflowYet();

    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, "5", AGGREGATE_ID, CALLING_INSTANCE))
        .orElseThrow();

    // the version that picked the details provider is the one the report shows
    assertEquals("5", prefill.bpmnProcessVersion());
    assertEquals(AGGREGATE_ID, prefill.businessId());
    assertEquals("The cockpit process", prefill.bpmnProcessName());
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());

  }

  @Test
  @DisplayName("A change after the end of a workflow is reported to the cockpit, and the cluster gets no command")
  public void aChangeAfterTheEndIsReportedAndSendsTheClusterNothing() {

    // since the election reads the note of the start, aggregateChanged finds this bridge after
    // the workflow ended as well, for as long as the note lives
    vanillaBpStartedTheWorkflow(new WorkflowStart("c8", CALLING_INSTANCE, "3", true));
    theModelIsNamed("The cockpit process");

    final var bridge = bridge();
    final var found = bridge
        .workflowsOfAggregateRightAway(MODULE_ID, PROCESS_ID, AGGREGATE_ID)
        .orElseThrow();
    final var prefill = bridge.prefilledWorkflowDetails(found.getFirst()).orElseThrow();

    // the note of the start and the model say all a report needs
    assertEquals("3", prefill.bpmnProcessVersion());
    assertEquals("The cockpit process", prefill.bpmnProcessName());
    // no read, and no command: the cockpit hears of the change, the cluster does not
    verifyNoInteractions(client());

  }

}
