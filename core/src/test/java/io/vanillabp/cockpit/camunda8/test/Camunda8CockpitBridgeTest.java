package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
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

  /** What the wiring read out of the models, which names the process for a report. */
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
    return new Camunda8CockpitBridge(clients.of("c8"), workflowTaskWiring, election, deployments);

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
    return new Camunda8CockpitBridge(clients.of("c8"), workflowTaskWiring, election, deployments);

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
    return new Camunda8CockpitBridge(clients.of("c8"), workflowTaskWiring, election, deployments);

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

    final var instance = mock(ProcessInstance.class, RETURNS_DEEP_STUBS);
    when(instance.getProcessDefinitionVersion()).thenReturn(2);
    when(instance.getProcessDefinitionName()).thenReturn("The cockpit process");
    when(
        clientFactories
            .getFactory("c8")
            .getClient()
            .newProcessInstanceGetRequest(Long.parseLong(CALLING_INSTANCE))
            .send()
            .join())
        .thenReturn(instance);

    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, "2", AGGREGATE_ID, CALLING_INSTANCE))
        .orElseThrow();

    // to VanillaBP a business key is a business key only where it says what the aggregate's @Id
    // attribute says. So the report names that id, whatever a cluster holds beside it, and it
    // names the same one whether it was built from a listener job or read here
    assertEquals(AGGREGATE_ID, prefill.businessId());
    assertEquals("2", prefill.bpmnProcessVersion());
    assertEquals("The cockpit process", prefill.bpmnProcessName());

  }

  @Test
  @DisplayName("A task the searchable storage holds no record of is answered with nothing")
  public void aTaskTheStorageDoesNotHoldIsAnsweredWithNothing() {

    // asked outside an event, which is what BusinessCockpitService.getUserTask does. There is
    // no entry to hand back and nothing to wait for, so an empty answer is what the caller can
    // work with: the cockpit keeps what it stored before, and the log says why
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

    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, CALLING_INSTANCE));

    assertTrue(prefill.isEmpty());

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

  /** VanillaBP wrote down the key of the workflow of this test when it started it. */
  private void vanillaBpStartedTheWorkflow(
      final String workflowId) {

    when(election.workflowIdOf(MODULE_ID, PROCESS_ID, AGGREGATE_ID)).thenReturn(Optional.of(workflowId));

  }

  @Test
  @DisplayName("A workflow the storage has not written yet is reported by the key VanillaBP wrote down, with no search and no warning")
  public void aWorkflowStartedAMomentAgoReachesTheCockpit() {

    vanillaBpStartedTheWorkflow(CALLING_INSTANCE);
    theStorageHasNotWrittenTheWorkflowYet();
    deployments
        .register(
            "c8", MODULE_ID, new WiredListener(
                "cockpit-listener", SCOPED_PROCESS_ID, PROCESS_ID, SCOPED_PROCESS_ID, "The cockpit process", "The cockpit process", AGGREGATE_ID_NAME));
    final var bridge = bridge();

    // what BusinessCockpitService.aggregateChanged asks, for instance from the first service task
    // of the workflow, while the exporter has not written that workflow yet
    final var found = bridge.workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    assertEquals(1, found.size(), found.toString());
    assertEquals(CALLING_INSTANCE, found.getFirst().workflowId());
    // only the record says which version the workflow runs on
    assertNull(found.getFirst().processVersion());
    verify(clientFactories.getFactory("c8").getClient(), never()).newProcessInstanceSearchRequest();

    // and what the cockpit asks next, while it builds the report
    final var prefill = bridge.prefilledWorkflowDetails(found.getFirst()).orElseThrow();

    assertEquals(AGGREGATE_ID, prefill.businessId());
    assertEquals("The cockpit process", prefill.bpmnProcessName());
    assertNull(prefill.bpmnProcessVersion());
    assertTrue(whatTheBridgeSaid(Level.WARN).isEmpty(), whatTheBridgeSaid(Level.WARN).toString());

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
    final var said = whatTheBridgeSaid(Level.WARN);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains("two readings"), said.getFirst());

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
  @DisplayName("The silence of the storage about another workflow than the one written down is explained as before")
  public void aKeyOfAnotherWorkflowDoesNotHideTheSilence() {

    vanillaBpStartedTheWorkflow("2251799813685999");
    theStorageHasNotWrittenTheWorkflowYet();

    final var prefill = bridge()
        .prefilledWorkflowDetails(
            new WorkflowReference(
                "c8", MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, CALLING_INSTANCE));

    assertTrue(prefill.isEmpty());
    final var said = whatTheBridgeSaid(Level.WARN);
    assertEquals(1, said.size(), said.toString());
    assertTrue(said.getFirst().contains("two readings"), said.getFirst());

  }

}
