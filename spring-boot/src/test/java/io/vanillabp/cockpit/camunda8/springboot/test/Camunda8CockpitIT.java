package io.vanillabp.cockpit.camunda8.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.camunda8.test.support.ClusterUnderTest;
import io.vanillabp.cockpit.camunda8.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Business Cockpit against a real Camunda 8 cluster, from the model this application
 * deploys to the request the cockpit server receives.
 * <p>
 * Nothing on that way is faked but the cockpit server itself: the cluster runs the workflow,
 * hands out the listener jobs the extension put into the model, the extension writes an outbox
 * entry and completes the job, the entry is dispatched afterwards, the cluster is read again,
 * the application's details provider runs and changes the workflow aggregate, and what arrives
 * at the server is asserted.
 * <p>
 * The workflow module runs under <code>use-prefix</code>, which is the harder of the two
 * name-clash modes: every identifier the cluster knows carries the module's prefix while
 * everything the cockpit is told has to be spelled the way the application wrote it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TestApplication.class)
// closed when the class is done: a context outliving its cluster keeps its workers polling an
// address nobody answers
@DirtiesContext
public class Camunda8CockpitIT {

  private static final String MODULE_ID = "c8-cockpit";

  static final Network NETWORK = Network.newNetwork();

  @Container
  static final GenericContainer<?> ELASTICSEARCH = ClusterUnderTest.elasticsearch(NETWORK);

  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster(NETWORK, ELASTICSEARCH);

  @DynamicPropertySource
  static void theClusterAndTheCockpitServer(
      final DynamicPropertyRegistry registry) {

    registry
        .add(
            "vanillabp.adapters.c8.rest-address",
            () -> "http://"
                + CAMUNDA.getHost()
                + ":"
                + CAMUNDA.getMappedPort(8080));
    registry
        .add(
            "vanillabp.adapters.c8.grpc-address",
            () -> "http://"
                + CAMUNDA.getHost()
                + ":"
                + CAMUNDA.getMappedPort(26500));
    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestWorkflowService workflowService;

  @Autowired
  private TestAggregateRepository aggregates;

  @Autowired
  private RetriedWorkflowService retriedWorkflowService;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactories;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  private CamundaClient client() {

    return clientFactories.getFactory("c8").getClient();

  }

  private TestAggregate aStartedWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          return workflowService.processes().startWorkflow(aggregate);
        });

  }

  /**
   * The key of the user task of one case, once the cluster's searchable storage knows it.
   */
  private String userTaskIdOf(
      final TestAggregate aggregate) {

    return awaitValue(
        () -> client()
            .newUserTaskSearchRequest()
            .filter(
                filter -> filter
                    .state(UserTaskState.CREATED)
                    .processInstanceVariables(
                        Map.of("id", "\"%s\"".formatted(aggregate.getId()))))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .map(task -> String.valueOf(task.getUserTaskKey()))
            .orElse(null),
        "the user task of aggregate %s".formatted(aggregate.getId()));

  }

  private String workflowIdOf(
      final TestAggregate aggregate) {

    return workflowIdOf(aggregate.getId());

  }

  private String workflowIdOf(
      final Long aggregateId) {

    return awaitValue(
        () -> client()
            .newProcessInstanceSearchRequest()
            .filter(
                filter -> filter
                    .variables(Map.of("id", "\"%s\"".formatted(aggregateId))))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .map(instance -> String.valueOf(instance.getProcessInstanceKey()))
            .orElse(null),
        "the workflow of aggregate %s".formatted(aggregateId));

  }

  /**
   * Waits for the cluster's searchable storage to catch up, which is the slowest part of every
   * test here.
   */
  private static <T> T awaitValue(
      final Supplier<T> value,
      final String description) {

    final var deadline = System.currentTimeMillis() + 240_000;
    while (System.currentTimeMillis() < deadline) {
      final var found = value.get();
      if (found != null) {
        return found;
      }
      try {
        Thread.sleep(250);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for "
            + description, e);
      }
    }
    throw new AssertionError("Timed out waiting for "
        + description);

  }

  private static List<ZeebeExecutionListener> executionListenersOf(
      final BpmnModelInstance model,
      final String elementId) {

    final var element = (BaseElement) model.getModelElementById(elementId);
    final var listeners = element.getSingleExtensionElement(ZeebeExecutionListeners.class);
    return listeners == null
        ? List.of()
        : List.copyOf(listeners.getExecutionListeners());

  }

  @Test
  @DisplayName("The deployed model carries the cockpit's listeners, under the identifiers the cluster knows")
  public void theDeployedModelCarriesTheListeners() {

    final var definitionKey = awaitValue(
        () -> client()
            .newProcessDefinitionSearchRequest()
            .filter(filter -> filter
                .processDefinitionId("%s__%s".formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID)))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .map(definition -> definition.getProcessDefinitionKey())
            .orElse(null),
        "the deployed process definition");

    final var xml = client()
        .newProcessDefinitionGetXmlRequest(definitionKey)
        .send()
        .join();
    final var model = Bpmn
        .readModelFromStream(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    final var scopedProcessId = "%s__%s".formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID);
    final var scopedTaskDefinition = "%s__%s__%s"
        .formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION);

    final var userTask = (UserTask) model.getModelElementById(TestWorkflowService.BPMN_TASK_ID);
    final var taskListenerTypes = userTask
        .getSingleExtensionElement(ZeebeTaskListeners.class)
        .getTaskListeners()
        .stream()
        .map(ZeebeTaskListener::getType)
        .toList();
    assertTrue(
        taskListenerTypes.contains(Camunda8CockpitListeners.listenerTypeOf(scopedTaskDefinition)),
        taskListenerTypes.toString());

    final var startEventListeners = executionListenersOf(model, "Start");
    assertTrue(
        startEventListeners
            .stream()
            .anyMatch(
                listener -> Camunda8CockpitListeners
                    .listenerTypeOf(scopedProcessId)
                    .equals(listener.getType())),
        "the start event carries no listener of the cockpit");

    final var processListeners = executionListenersOf(model, scopedProcessId);
    assertTrue(
        processListeners
            .stream()
            .anyMatch(
                listener -> Camunda8CockpitListeners
                    .listenerTypeOf(scopedProcessId)
                    .equals(listener.getType())),
        "the process carries no listener of the cockpit");

  }

  @Test
  @DisplayName("A started workflow and its user task reach the cockpit, enriched by the application")
  public void aStartedWorkflowReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Anna");

    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Anna\"");
    // the plain process id, although the cluster knows the prefixed one
    assertTrue(
        workflow.body().contains("\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.BPMN_PROCESS_ID)),
        workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Anna\"");
    assertTrue(userTask.body().contains("\"event\":\"CREATED\""), userTask.body());
    // the plain task definition, which is what a details provider is matched by
    assertTrue(
        userTask.body().contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"approvers\"]"), userTask.body());
    // the BPMN name is what the cockpit falls back to when nothing else produced a title
    assertTrue(userTask.body().contains("Approve the order"), userTask.body());

    // the details provider ran on the real aggregate and its change was saved
    assertEquals(
        TestWorkflowService.APPROVE_NOTE,
        aggregates.findById(aggregate.getId()).orElseThrow().getNote());

  }

  @Test
  @DisplayName("Completing the user task reports the task and the workflow as completed")
  public void completingTheUserTaskIsReported() {

    final var aggregate = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");

    transactions
        .executeWithoutResult(
            status -> workflowService
                .processes()
                .completeUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));

    assertNotNull(CockpitServer.awaitRequest("/usertask/%s/completed".formatted(userTaskId)));
    assertNotNull(CockpitServer.awaitRequest("/workflow/%s/completed".formatted(workflowId)));

  }

  @Test
  @DisplayName("Cancelling the workflow reports its user task as cancelled and the workflow not at all")
  public void cancellingTheWorkflowIsReportedAsFarAsCamundaSaysIt() {

    final var aggregate = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");

    // nothing of VanillaBP is involved here: this is what an operator does, and the cockpit
    // has to hear about it
    client().newCancelInstanceCommand(Long.parseLong(workflowId)).send().join();

    assertNotNull(CockpitServer.awaitRequest("/usertask/%s/cancelled".formatted(userTaskId)));

    // and what the cockpit does NOT hear about is the workflow: the 'end' listener of a
    // process does not run when the instance is cancelled, and Camunda 8 has no listener for
    // a cancellation before 8.10 - see decision 3 in the repository's DECISIONS.md
    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer.matching("/workflow/%s/completed".formatted(workflowId)),
        "the cancelled workflow was reported as completed");

  }

  @Test
  @DisplayName("A workflow started by a message reports its start with the case it is about")
  public void aWorkflowStartedByMessageIsReported() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer("Klara");
          return workflowService
              .processes()
              .startWorkflowByMessage(fresh, TestWorkflowService.START_MESSAGE);
        });

    // the start of such a workflow is reported by the listener the MESSAGE start event
    // carries - every start event of a process gets one - and it knows the case because the
    // aggregate id travelled with the message
    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Klara\"");
    assertTrue(
        workflow.body().contains("\"aggregateId\":\"%s\"".formatted(aggregate.getId())),
        workflow.body());

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates its workflow")
  public void aggregateChangedUpdatesTheWorkflow() {

    final var aggregate = aStartedWorkflow("Emil");
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.awaitRequest("/workflow/created");
    CockpitServer.forgetRequests();

    transactions
        .executeWithoutResult(status -> {
          final var attached = aggregates.findById(aggregate.getId()).orElseThrow();
          attached.setCustomer("Emil the second");
          aggregates.save(attached);
          workflowService.businessCockpit().aggregateChanged(attached);
        });

    final var updated = CockpitServer.awaitRequest("/workflow/%s/updated".formatted(workflowId));
    assertTrue(updated.body().contains("Emil the second"), updated.body());

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the named user task")
  public void aggregateChangedUpdatesTheNamedUserTask() {

    final var aggregate = aStartedWorkflow("Frida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    transactions
        .executeWithoutResult(status -> {
          final var attached = aggregates.findById(aggregate.getId()).orElseThrow();
          attached.setCustomer("Frida the second");
          aggregates.save(attached);
          workflowService.businessCockpit().aggregateChanged(attached, userTaskId);
        });

    final var updated = CockpitServer.awaitRequest("/usertask/%s/updated".formatted(userTaskId));
    assertTrue(updated.body().contains("Frida the second"), updated.body());

  }

  @Test
  @DisplayName("An application can read one user task of its own case, and no other")
  public void getUserTaskAnswersOnlyForItsOwnAggregate() {

    final var aggregate = aStartedWorkflow("Gustl");
    final var userTaskId = userTaskIdOf(aggregate);
    final var otherAggregate = aStartedWorkflow("Heidi");
    userTaskIdOf(otherAggregate);

    final var userTask = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));
    assertTrue(userTask.isPresent(), "the case's own task was not answered");
    assertEquals(userTaskId, userTask.get().getId());
    assertEquals(TestWorkflowService.BPMN_TASK_ID, userTask.get().getBpmnTaskId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTask.get().getTaskDefinition());

    final var foreign = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(
                    aggregates.findById(otherAggregate.getId()).orElseThrow(), userTaskId));
    assertTrue(foreign.isEmpty(), "a task of another case was answered");

  }

  @Test
  @DisplayName("Reading a user task reports nothing to the cockpit")
  public void readingAUserTaskReportsNothing() {

    final var aggregate = aStartedWorkflow("Ida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));

    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer.matching("/usertask/%s/updated".formatted(userTaskId)),
        "reading a task reported it as changed");

  }

  @Test
  @DisplayName("A details provider which fails costs a repetition of the report and no incident")
  public void aFailingDetailsProviderIsRetriedRatherThanRaisingAnIncident() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new RetriedAggregate();
          fresh.setCustomer("Jonas");
          return retriedWorkflowService.processes().startWorkflow(fresh);
        });

    // the report arrives although the provider threw on its first attempts: the entry is
    // dispatched again, and the workflow never noticed
    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", RetriedWorkflowService.TASK_DEFINITION);
    assertTrue(
        retriedWorkflowService.attempts() > RetriedWorkflowService.FAILURES_BEFORE_THE_PROVIDER_ANSWERS,
        "the details provider was not called again after it failed");

    // and the cluster is untouched by it: the listener job was completed when the report was
    // written, long before the provider ran
    final var workflowId = workflowIdOf(aggregate.getId());
    assertEquals(
        Boolean.FALSE,
        client()
            .newProcessInstanceGetRequest(Long.parseLong(workflowId))
            .send()
            .join()
            .getHasIncident(),
        "the workflow carries an incident although only a report failed");

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();

    assertTrue(registration.body().contains(MODULE_ID), registration.body());
    assertTrue(registration.body().contains("approvers"), registration.body());

  }

}
