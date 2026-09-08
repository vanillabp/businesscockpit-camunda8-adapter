package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitJobHandler;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.camunda8.Camunda8Scope;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What one listener job of a Camunda 8 cluster means to the cockpit, and what happens when it
 * means nothing.
 * <p>
 * The cluster is mocked here rather than started: what is under test is the translation from a
 * job into the identifiers a report is made of, and a cluster would only make the same
 * assertions slower. That the listeners really produce these jobs is what the integration tests
 * are for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitJobHandlerTest {

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String AGGREGATE_ID_NAME = "loanId";

  private static final String AGGREGATE_ID = "4711";

  private static final String FORM_REFERENCE = "approve";

  private static final String ADAPTER_ID = "c8";

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  private final RecordingPublisher publisher = new RecordingPublisher();

  private final JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private Camunda8CockpitJobHandler handler;

  @BeforeEach
  public void aWiredWorkflowModule() {

    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(
                Camunda8CockpitListeners
                    .listenerTypeOf(PROCESS_ID), PROCESS_ID, PROCESS_ID, "Started", AGGREGATE_ID_NAME));
    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(
                Camunda8CockpitListeners
                    .listenerTypeOf(PROCESS_ID), PROCESS_ID, PROCESS_ID, PROCESS_ID, AGGREGATE_ID_NAME));
    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(
                Camunda8CockpitListeners
                    .listenerTypeOf(FORM_REFERENCE), PROCESS_ID, PROCESS_ID, "Approve", AGGREGATE_ID_NAME));
    handler = new Camunda8CockpitJobHandler(
        new Camunda8Scope(ADAPTER_ID, null, null), MODULE_ID, deployments, () -> publisher);

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
    when(job.getProcessInstanceKey()).thenReturn(12345L);
    // what the cluster reports for a top-level instance: the instance is its own root
    when(job.getRootProcessInstanceKey()).thenReturn(12345L);
    when(job.getVariablesAsMap()).thenReturn(Map.of(AGGREGATE_ID_NAME, AGGREGATE_ID));
    return job;

  }

  private ActivatedJob aUserTaskJob(
      final ListenerEventType eventType) {

    final var job = aJob(
        JobKind.TASK_LISTENER, eventType, Camunda8CockpitListeners.listenerTypeOf(FORM_REFERENCE),
        "Approve");
    final var userTask = mock(UserTaskProperties.class);
    when(userTask.getUserTaskKey()).thenReturn(999L);
    when(job.getUserTask()).thenReturn(userTask);
    return job;

  }

  @Test
  @DisplayName("The three task-listener events become the three the cockpit distinguishes")
  public void theTaskListenerEventsAreMapped() {

    handler.handle(client, aUserTaskJob(ListenerEventType.CREATING));
    handler.handle(client, aUserTaskJob(ListenerEventType.CANCELING));
    handler.handle(client, aUserTaskJob(ListenerEventType.COMPLETING));
    handler.handle(client, aUserTaskJob(ListenerEventType.ASSIGNING));

    assertEquals(
        List
            .of(
                UserTaskEventKind.CREATED, UserTaskEventKind.CANCELED, UserTaskEventKind.COMPLETED,
                UserTaskEventKind.UPDATED),
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
    assertEquals("88", reported.bpmsEventId());
    assertEquals(EventTransaction.NEW, reported.transaction());

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

  }

  @Test
  @DisplayName("A job of a called process reports nothing: the case is the workflow above it")
  public void aCalledProcessIsNoCaseOfItsOwn() {

    final var job = aJob(
        JobKind.EXECUTION_LISTENER, ListenerEventType.END,
        Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID), PROCESS_ID);
    when(job.getProcessInstanceKey()).thenReturn(777L);
    when(job.getRootProcessInstanceKey()).thenReturn(12345L);

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
  @DisplayName("A report collapsing into one already waiting completes the job like any other")
  public void aCollapsedReportIsNoFailure() {

    publisher.collapsesReports(true);

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

}
