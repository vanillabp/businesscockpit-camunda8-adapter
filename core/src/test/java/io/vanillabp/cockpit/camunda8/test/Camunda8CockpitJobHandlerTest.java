package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.UserTaskProperties;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitJobHandler;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What one listener job of a Camunda 8 cluster means to the cockpit, and what happens when it
 * means nothing.
 * <p>
 * The cluster is mocked here rather than started: what is under test is the translation from a
 * job into the identifiers a report is made of, and a cluster would only make the same
 * assertions slower. That the listeners really produce these jobs is what the integration tests
 * are for.
 * <p>
 * The drain is real, because the answer to the cluster goes through it: a handler registers its
 * job there and a shutdown is what decides whether a failure is reported at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitJobHandlerTest {

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String AGGREGATE_ID_NAME = "loanId";

  private static final String AGGREGATE_ID = "4711";

  private static final String FORM_REFERENCE = "approve";

  /**
   * The BPMN names the model carries. A job never says them, so they are read out of the model
   * while the process is wired and travel in the wired listener.
   */
  private static final String PROCESS_NAME = "The order process";

  private static final String TASK_NAME = "Approve the order";

  private static final String ASSIGNEE = "bertha";

  /** What only the cluster's searchable storage would answer, and never a listener job. */
  private static final String WHAT_THE_STORAGE_SAYS = "The task as the storage holds it";

  private static final OffsetDateTime DUE_DATE = OffsetDateTime
      .parse("2026-09-17T12:00:00+02:00");

  private static final OffsetDateTime FOLLOW_UP_DATE = OffsetDateTime
      .parse("2026-09-16T08:00:00+02:00");

  private static final String ADAPTER_ID = "c8";

  /** The version of the model the jobs of this test come from, as a cluster counts it. */
  private static final int DEPLOYED_VERSION = 3;

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  private final RecordingPublisher publisher = new RecordingPublisher();

  private final JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);

  /**
   * What the adapter waits for while it shuts down. The handler asks the adapter's factory for
   * it once per job, so the test hands the factory this one.
   */
  private final Camunda8Drain drain = new Camunda8Drain(ADAPTER_ID, MODULE_ID);

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  /** What a bridge asks for the name of the aggregate id variable, which no test here reads. */
  private final WorkflowTaskWiring workflowTaskWiring = mock(WorkflowTaskWiring.class);

  private Camunda8CockpitJobHandler handler;

  @BeforeEach
  public void aWiredWorkflowModule() {

    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(
                Camunda8CockpitListeners
                    .listenerTypeOf(
                        PROCESS_ID), PROCESS_ID, PROCESS_ID, "Started", "The order arrived", PROCESS_NAME, AGGREGATE_ID_NAME));
    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(
                Camunda8CockpitListeners
                    .listenerTypeOf(
                        PROCESS_ID), PROCESS_ID, PROCESS_ID, PROCESS_ID, PROCESS_NAME, PROCESS_NAME, AGGREGATE_ID_NAME));
    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(
                Camunda8CockpitListeners
                    .listenerTypeOf(
                        FORM_REFERENCE), PROCESS_ID, PROCESS_ID, "Approve", TASK_NAME, PROCESS_NAME, AGGREGATE_ID_NAME));
    when(clientFactories.getFactory(ADAPTER_ID).drainOf(MODULE_ID)).thenReturn(drain);
    handler = new Camunda8CockpitJobHandler(
        new Camunda8Clients(clientFactories, null).of(ADAPTER_ID), MODULE_ID, deployments, () -> publisher);

  }

  private ActivatedJob aJob(
      final JobKind kind,
      final ListenerEventType eventType,
      final String jobType,
      final String elementId) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(88L);
    when(job.getKind()).thenReturn(kind);
    when(job.getListenerEventType()).thenReturn(eventType);
    when(job.getType()).thenReturn(jobType);
    when(job.getElementId()).thenReturn(elementId);
    when(job.getBpmnProcessId()).thenReturn(PROCESS_ID);
    // every job names the version of the model it comes from, and that is what picks between
    // details providers serving different versions of it
    when(job.getProcessDefinitionVersion()).thenReturn(DEPLOYED_VERSION);
    // a workflow nobody called. Where a line reads that from differs, see JobsInAHierarchy in
    // the per-line test sources
    JobsInAHierarchy.isItsOwnRoot(clientFactories, ADAPTER_ID, job, 12345L);
    when(job.getVariablesAsMap()).thenReturn(Map.of(AGGREGATE_ID_NAME, AGGREGATE_ID));
    // how long the lock of this job still holds. The answer to the cluster is repeated while
    // the cluster rejects it for being busy, and what bounds that repetition is the lock: an
    // unstubbed deadline is zero, which reads as a job somebody else may already have
    when(job.getDeadline()).thenReturn(Long.valueOf(Instant.now().plusSeconds(60).toEpochMilli()));
    return job;

  }

  private ActivatedJob aUserTaskJob(
      final ListenerEventType eventType) {

    final var job = aJob(
        JobKind.TASK_LISTENER, eventType, Camunda8CockpitListeners.listenerTypeOf(FORM_REFERENCE),
        "Approve");
    final var userTask = mock(UserTaskProperties.class);
    when(userTask.getUserTaskKey()).thenReturn(999L);
    // what the cluster hands out with a task listener's job, which is what a report is built
    // from
    when(userTask.getAssignee()).thenReturn(ASSIGNEE);
    when(userTask.getCandidateUsers()).thenReturn(List.of("carla"));
    when(userTask.getCandidateGroups()).thenReturn(List.of("approvers"));
    when(userTask.getDueDate()).thenReturn(DUE_DATE);
    when(userTask.getFollowUpDate()).thenReturn(FOLLOW_UP_DATE);
    when(job.getUserTask()).thenReturn(userTask);
    return job;

  }

  /**
   * A handler whose reports are answered by a real bridge of the same cluster, which is how the
   * cockpit puts a report together: it is told about the event and asks the BPMS half what the
   * event says, on the thread the job runs on.
   *
   * @return What the publisher recorded, bridge answers included
   */
  private RecordingPublisher aHandlerAskingItsBridge() {

    final var clients = new Camunda8Clients(clientFactories, null);
    final var asked = new RecordingPublisher(
        new Camunda8CockpitBridge(clients.of(ADAPTER_ID), workflowTaskWiring));
    handler = new Camunda8CockpitJobHandler(
        clients.of(ADAPTER_ID), MODULE_ID, deployments, () -> asked);
    return asked;

  }

  @Test
  @DisplayName("The three task-listener events become the three the cockpit distinguishes")
  public void theTaskListenerEventsAreMapped() {

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));
    handler.handle(client, aUserTaskJob(ListenerEventType.CANCELING));
    handler.handle(client, aUserTaskJob(ListenerEventType.COMPLETING));
    handler.handle(client, aUserTaskJob(ListenerEventType.ASSIGNING));
    // Camunda adds listener events inside a release line without calling it breaking, and
    // a client older than the cluster answers UNKNOWN_ENUM_VALUE for one it has no literal
    // for. That is a change of the task and nothing more specific, like every other event
    // this extension did not ask for
    handler.handle(client, aUserTaskJob(ListenerEventType.UNKNOWN_ENUM_VALUE));

    assertEquals(
        List
            .of(
                UserTaskEventKind.CREATED, UserTaskEventKind.CANCELED, UserTaskEventKind.COMPLETED,
                UserTaskEventKind.UPDATED, UserTaskEventKind.UPDATED),
        publisher.userTaskEvents().stream().map(RecordingPublisher.UserTaskEvent::kind).toList());

  }

  @Test
  @DisplayName("A user task is reported with the keys the cockpit addresses it by")
  public void aUserTaskCarriesTheIdentifiersOfItsCase() {

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    final var reported = publisher.userTaskEvents().getFirst();
    assertEquals("c8", reported.userTask().adapterId());
    assertEquals(MODULE_ID, reported.userTask().workflowModuleId());
    assertEquals(PROCESS_ID, reported.userTask().bpmnProcessId());
    assertEquals(AGGREGATE_ID, reported.userTask().workflowAggregateId());
    assertEquals("12345", reported.userTask().workflowId());
    assertEquals("999", reported.userTask().userTaskId());
    assertEquals(FORM_REFERENCE, reported.userTask().taskDefinition());
    assertEquals("Approve", reported.userTask().bpmnTaskId());
    assertEquals(String.valueOf(DEPLOYED_VERSION), reported.userTask().processVersion());
    assertEquals("88", reported.bpmsEventId());
    assertEquals(EventTransaction.NEW, reported.transaction());

  }

  @Test
  @DisplayName("The report of a user task carries what its own job said, and the cluster is not asked")
  public void aUserTaskIsReportedWithTheValuesOfItsEvent() {

    final var asked = aHandlerAskingItsBridge();

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    final var values = asked.userTaskEvents().getFirst().values().orElseThrow();
    assertEquals(ASSIGNEE, values.assignee());
    assertEquals(List.of("carla"), values.candidateUsers());
    assertEquals(List.of("approvers"), values.candidateGroups());
    assertEquals(DUE_DATE, values.dueDate());
    assertEquals(FOLLOW_UP_DATE, values.followUpDate());
    // the two BPMN names are not on a job. They were read out of the model while the process
    // was wired, and they are the title the cockpit falls back to
    assertEquals(TASK_NAME, values.bpmnTaskName());
    assertEquals(PROCESS_NAME, values.bpmnProcessName());
    assertEquals(String.valueOf(DEPLOYED_VERSION), values.bpmnProcessVersion());
    // the case, and no step below it: this task sits in the workflow it is reported under
    assertEquals("12345", values.workflowId());
    assertNull(values.subWorkflowId());

    // nothing of this came from the cluster's searchable storage, which could not answer it: the
    // exporter writes that storage after the transition this job gates
    verify(
        clientFactories.getFactory(ADAPTER_ID).getClient(), never())
        .newUserTaskGetRequest(anyLong());

  }

  @Test
  @DisplayName("The report of a workflow carries what its own job said, and the cluster is not asked")
  public void aWorkflowIsReportedWithTheValuesOfItsEvent() {

    final var asked = aHandlerAskingItsBridge();

    handler
        .handle(
            client,
            aJob(
                JobKind.EXECUTION_LISTENER, ListenerEventType.END,
                Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID), "Started"));

    final var values = asked.workflowEvents().getFirst().values().orElseThrow();
    assertEquals(PROCESS_NAME, values.bpmnProcessName());
    assertEquals(String.valueOf(DEPLOYED_VERSION), values.bpmnProcessVersion());
    // the business key of a VanillaBP workflow is the workflow aggregate's id, on every release
    // line. See decision 8 in the repository's DECISIONS.md
    assertEquals(AGGREGATE_ID, values.businessId());

    verify(
        clientFactories.getFactory(ADAPTER_ID).getClient(), never())
        .newProcessInstanceGetRequest(anyLong());

  }

  @Test
  @DisplayName("A task of a called process is reported under the case, with the called process as the step")
  public void aTaskOfACalledProcessNamesTheCaseAndTheStep() {

    final var asked = aHandlerAskingItsBridge();
    final var job = aUserTaskJob(ListenerEventType.CREATING);
    JobsInAHierarchy.isCalledBy(clientFactories, ADAPTER_ID, job, 777L, 12345L);

    handler.handle(client, job);

    final var values = asked.userTaskEvents().getFirst().values().orElseThrow();
    assertEquals("12345", values.workflowId());
    assertEquals("777", values.subWorkflowId());

  }

  @Test
  @DisplayName("Once a job is done its values are gone, so the next job on that thread reads none of them")
  public void theValuesOfAnEventDoNotOutliveTheirJob() {

    final var clients = new Camunda8Clients(clientFactories, null);
    final var bridge = new Camunda8CockpitBridge(clients.of(ADAPTER_ID), workflowTaskWiring);
    handler = new Camunda8CockpitJobHandler(
        clients.of(ADAPTER_ID), MODULE_ID, deployments, () -> publisher);

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    // what the cluster would answer if it were asked, which is what this test is about
    final var record = mock(UserTask.class, RETURNS_DEEP_STUBS);
    when(record.getName()).thenReturn(WHAT_THE_STORAGE_SAYS);
    when(record.getCandidateUsers()).thenReturn(List.of());
    when(record.getCandidateGroups()).thenReturn(List.of());
    when(
        clientFactories
            .getFactory(ADAPTER_ID)
            .getClient()
            .newUserTaskGetRequest(999L)
            .send()
            .join())
        .thenReturn(record);

    // the same thread asks about the very task it just reported, and is answered by the cluster
    // rather than out of an event which is over
    final var afterwards = bridge
        .prefilledUserTaskDetails(
            new UserTaskReference(
                ADAPTER_ID, MODULE_ID, PROCESS_ID, "1", AGGREGATE_ID, "12345", "999", FORM_REFERENCE, "Approve"))
        .orElseThrow();

    assertEquals(WHAT_THE_STORAGE_SAYS, afterwards.bpmnTaskName());

  }

  @Test
  @DisplayName("An end listener of a start event begins the workflow, one of the process ends it")
  public void theExecutionListenersTellTheWorkflowApart() {

    handler
        .handle(
            client,
            aJob(
                JobKind.EXECUTION_LISTENER, ListenerEventType.END,
                Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID), "Started"));
    handler
        .handle(
            client,
            aJob(
                JobKind.EXECUTION_LISTENER, ListenerEventType.END,
                Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID), PROCESS_ID));

    assertEquals(
        List.of(WorkflowEventKind.CREATED, WorkflowEventKind.COMPLETED),
        publisher.workflowEvents().stream().map(RecordingPublisher.WorkflowEvent::kind).toList());
    assertEquals("12345", publisher.workflowEvents().getFirst().workflow().workflowId());
    assertEquals(
        String.valueOf(DEPLOYED_VERSION),
        publisher.workflowEvents().getFirst().workflow().processVersion());

  }

  @Test
  @DisplayName("The cancel listener of a process closes the case rather than refreshing it")
  public void aCancelledWorkflowIsReportedAsCancelled() {

    assumeTrue(
        JobsOfACancellation.thisLineReportsThem(),
        "a cluster of this release line hands out no job for a cancelled instance");

    final var job = aJob(
        JobKind.EXECUTION_LISTENER, null, Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID),
        PROCESS_ID);
    JobsOfACancellation.reportsACancellation(job);

    handler.handle(client, job);

    // and not UPDATED, which is what every listener event but 'end' used to be read as. An
    // update refreshes a case the cockpit goes on showing as open
    assertEquals(
        List.of(WorkflowEventKind.CANCELLED),
        publisher.workflowEvents().stream().map(RecordingPublisher.WorkflowEvent::kind).toList());
    assertEquals("12345", publisher.workflowEvents().getFirst().workflow().workflowId());

  }

  @Test
  @DisplayName("A cancelled called process reports nothing either: it is a step, not a case")
  public void aCancelledCalledProcessIsNoCaseOfItsOwn() {

    assumeTrue(
        JobsOfACancellation.thisLineReportsThem(),
        "a cluster of this release line hands out no job for a cancelled instance");

    final var job = aJob(
        JobKind.EXECUTION_LISTENER, null, Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID),
        PROCESS_ID);
    JobsOfACancellation.reportsACancellation(job);
    // a cancelled hierarchy gives every instance in it a cancel job of its own, and each of
    // them names its own process instance. Only the one nobody called is the business case
    JobsInAHierarchy.isCalledBy(clientFactories, ADAPTER_ID, job, 777L, 12345L);

    handler.handle(client, job);

    assertTrue(publisher.workflowEvents().isEmpty());
    verify(client.newCompleteCommand(anyLong()).send(), atLeastOnce()).join();

  }

  @Test
  @DisplayName("A job of a called process reports nothing: the case is the workflow above it")
  public void aCalledProcessIsNoCaseOfItsOwn() {

    final var job = aJob(
        JobKind.EXECUTION_LISTENER, ListenerEventType.END,
        Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID), PROCESS_ID);
    JobsInAHierarchy.isCalledBy(clientFactories, ADAPTER_ID, job, 777L, 12345L);

    handler.handle(client, job);

    assertTrue(publisher.workflowEvents().isEmpty());
    verify(client.newCompleteCommand(anyLong()).send(), atLeastOnce()).join();

  }

  @Test
  @DisplayName("A job of a model this workflow module did not wire is completed without a report")
  public void aJobOfSomebodyElsesModelIsNotReported() {

    final var job = aJob(
        JobKind.TASK_LISTENER, ListenerEventType.CREATING,
        Camunda8CockpitListeners.listenerTypeOf("somebody-elses-form"), "Approve");

    handler.handle(client, job);

    assertTrue(publisher.userTaskEvents().isEmpty());
    verify(client.newFailCommand(anyLong()), never()).retries(0);

  }

  @Test
  @DisplayName("A job without the workflow aggregate's id fails, which raises an incident")
  public void aJobWithoutAnAggregateIdFails() {

    final var job = aUserTaskJob(ListenerEventType.CREATING);
    when(job.getVariablesAsMap()).thenReturn(Map.of());

    handler.handle(client, job);

    assertTrue(publisher.userTaskEvents().isEmpty());
    verify(client.newFailCommand(88L)).retries(0);

  }

  @Test
  @DisplayName("A job which is neither a task nor an execution listener fails, naming what it was")
  public void aJobOfAnUnexpectedKindFails() {

    final var job = aJob(
        JobKind.BPMN_ELEMENT, ListenerEventType.END,
        Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID), PROCESS_ID);

    handler.handle(client, job);

    assertTrue(publisher.workflowEvents().isEmpty());
    assertTrue(publisher.userTaskEvents().isEmpty());
    verify(client.newFailCommand(88L)).retries(0);

  }

  @Test
  @DisplayName("A report which produced no entry completes the job like any other")
  public void aReportWithoutAnEntryIsNoFailure() {

    publisher.writesNothing(true);

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    verify(client.newCompleteCommand(88L).send(), atLeastOnce()).join();
    verify(client.newFailCommand(anyLong()), never()).retries(0);

  }

  @Test
  @DisplayName("A reported job is completed only after the report was written")
  public void theJobIsCompletedAfterTheReport() {

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    assertEquals(1, publisher.userTaskEvents().size());
    verify(client.newCompleteCommand(88L).send(), atLeastOnce()).join();

  }

  @Test
  @DisplayName("A completed job carries no variables, which is what a task listener is allowed")
  public void theCompletionCarriesNothing() {

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    verify(client.newCompleteCommand(88L), never()).variables(anyMap());

  }

  @Test
  @DisplayName("A failure while the workflow module shuts down leaves the job to its lock")
  public void aShutdownDoesNotFailTheJob() {

    final var job = aUserTaskJob(ListenerEventType.CREATING);
    when(job.getVariablesAsMap()).thenReturn(Map.of());
    drain.beginShutdown();

    handler.handle(client, job);

    // nobody abandoned this work: the application was asked to stop, so the job keeps its lock
    // and the next instance of the application gets it. Failing it here would raise an incident
    // on every restart which catches a listener job in flight
    verify(client, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("The drain holds the job while its handler runs and is empty when it returns")
  public void theDrainKnowsWhatIsRunning() {

    // what makes a shutdown wait for this handler instead of closing the client under it. The
    // drain is asked while the work is happening, which is what the publisher is asked for too
    final var runningWhileTheWorkHappened = new ArrayList<Camunda8Drain.InFlightJob>();
    final var watchingHandler = new Camunda8CockpitJobHandler(
        new Camunda8Clients(clientFactories, null).of(ADAPTER_ID), MODULE_ID, deployments, () -> {
          runningWhileTheWorkHappened.addAll(drain.getInFlight());
          return publisher;
        });

    watchingHandler.handle(client, aUserTaskJob(ListenerEventType.CREATING));

    assertEquals(
        List.of(Long.valueOf(88L)),
        runningWhileTheWorkHappened
            .stream()
            .map(job -> Long.valueOf(job.jobKey()))
            .distinct()
            .toList());
    assertTrue(drain.getInFlight().isEmpty());

  }

}
