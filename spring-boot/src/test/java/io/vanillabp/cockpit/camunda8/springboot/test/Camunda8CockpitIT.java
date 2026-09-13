package io.vanillabp.cockpit.camunda8.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
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

  /**
   * How often the application repeats a transaction which read a conflict. Two would do for the
   * one other writer there is; the third is there so that a repetition which itself meets the
   * next report does not end the test.
   */
  private static final int ATTEMPTS_OF_THE_APPLICATION = 3;

  /**
   * How long a wait for the cluster's searchable storage keeps hoping. It is the slowest thing
   * in this class by far.
   */
  private static final Duration WAITING_FOR_THE_CLUSTER = Duration.ofMinutes(4);

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

  @Autowired
  private DetailsProviderGate gate;

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
   * Starts a workflow whose user-task details provider is held at the gate.
   * <p>
   * The gate is closed while the starting transaction is still open, and that is what makes the
   * test deterministic: a workflow reaches the cluster only after the transaction of its start
   * committed, so nothing can be reported - and no provider can run - before the gate is closed.
   *
   * @param customer What the case is about
   * @return The started case
   */
  private TestAggregate aStartedWorkflowWhoseDetailsProviderIsHeld(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          final var started = workflowService.processes().startWorkflow(aggregate);
          gate.holdTheNextCallFor(started.getId());
          return started;
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

  /**
   * What the case carries now: a report carrying an old value while the case carries the new one
   * was read too early, and a case carrying the old value too was written over by somebody else.
   * <p>
   * Read through the repository alone rather than inside a transaction of the test. A read-write
   * transaction would flush the aggregate when it commits and would make the test one more writer
   * of the case it is watching, which is exactly what the version attribute answers with a
   * conflict.
   */
  private String storedCustomerOf(
      final TestAggregate aggregate) {

    return aggregates.findById(aggregate.getId()).orElseThrow().getCustomer();

  }

  /**
   * Changes one case and reports the change, the way an application whose workflow aggregate
   * carries a version attribute has to do it: in a transaction which is repeated where somebody
   * else wrote the same case in between.
   * <p>
   * That somebody is the Business Cockpit itself. Its details provider reads the case while a
   * report is dispatched and writes it back when that dispatch commits, so the two transactions
   * overlap whenever an application changes a case it has just reported. Without the version
   * attribute the later of the two writers wins silently; with it, one of them reads a conflict
   * and repeats, which is why this loop is here rather than a single transaction.
   *
   * @param aggregateId The case to change
   * @param changeAndReport Changes the attached case and reports it to the cockpit
   */
  private void changeTheCase(
      final Long aggregateId,
      final Consumer<TestAggregate> changeAndReport) {

    for (var attempt = 1;; attempt++) {
      try {
        transactions
            .executeWithoutResult(status -> {
              final var attached = aggregates.findById(aggregateId).orElseThrow();
              changeAndReport.accept(attached);
              aggregates.save(attached);
            });
        return;
      } catch (final OptimisticLockingFailureException e) {
        if (attempt >= ATTEMPTS_OF_THE_APPLICATION) {
          throw new AssertionError(
              "The application gave up after %d attempts at changing case %s"
                  .formatted(attempt, aggregateId), e);
        }
      }
    }

  }

  /**
   * Waits for a report of one kind which carries the value the application wrote.
   * <p>
   * A report carrying the older value has two possible causes and they need different work: the
   * report read the case too early, or the case itself lost the change. So a failure names what
   * the case carries now as well - see the version attribute of {@link TestAggregate}.
   *
   * @param pathSuffix What the report's path has to end with
   * @param expected What its body has to carry
   * @param aggregate The case the report is about
   * @return The report
   */
  private CockpitServer.Request awaitReportCarrying(
      final String pathSuffix,
      final String expected,
      final TestAggregate aggregate) {

    try {
      return CockpitServer.awaitRequest(pathSuffix, expected);
    } catch (final AssertionError e) {
      throw new AssertionError(
          "%s And the stored case now carries the customer '%s'."
              .formatted(e.getMessage(), storedCustomerOf(aggregate)), e);
    }

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

    return awaitValue(value, () -> description, WAITING_FOR_THE_CLUSTER);

  }

  /**
   * @param value What is waited for
   * @param description What a failure says, read only then - so that it may name what the case
   *          and the cockpit server carry by the time the waiting gave up
   * @param <T> What is waited for
   * @return The value
   * @see #awaitValue(Supplier, String)
   */
  private static <T> T awaitValue(
      final Supplier<T> value,
      final Supplier<String> description) {

    return awaitValue(value, description, WAITING_FOR_THE_CLUSTER);

  }

  /**
   * @param value What is waited for
   * @param description What a failure says
   * @param waitingAtMost How long to keep hoping
   * @param <T> What is waited for
   * @return The value
   * @see #awaitValue(Supplier, String)
   */
  private static <T> T awaitValue(
      final Supplier<T> value,
      final Supplier<String> description,
      final Duration waitingAtMost) {

    final var deadline = System.currentTimeMillis() + waitingAtMost.toMillis();
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
            + description.get(), e);
      }
    }
    throw new AssertionError("Timed out waiting for "
        + description.get());

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

    // The customer in that body is what the details provider read off the case, so the report
    // shows that the provider ran on the real aggregate. Whether the case KEEPS what that
    // provider wrote into it is not asserted, on purpose. The write rides the transaction which
    // dispatches the report, and the report is sent before that transaction commits. So the
    // commit can still be refused: by another writer of the same case, or by a first dispatch
    // attempt the cluster answered too early, which leaves the whole transaction unable to
    // commit. The report stands and the write is gone with the transaction. When the entry is
    // dispatched again is nobody's promise, so waiting for that write is waiting for something
    // which may never come. What such a write does to a case somebody else is changing at the
    // same time is the subject of aChangeMadeWhileADetailsProviderHoldsTheCaseSurvives.

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

    changeTheCase(
        aggregate.getId(),
        attached -> {
          attached.setCustomer("Emil the second");
          workflowService.businessCockpit().aggregateChanged(attached);
        });

    // the report carries what the application wrote, not what the case said before it. The
    // report of the user task may still be dispatching while this runs, and its details
    // provider holds the case over this transaction - the version attribute of TestAggregate is
    // what keeps that dispatch from writing the older reading back
    awaitReportCarrying(
        "/workflow/%s/updated".formatted(workflowId), "Emil the second", aggregate);

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the named user task")
  public void aggregateChangedUpdatesTheNamedUserTask() {

    final var aggregate = aStartedWorkflow("Frida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    changeTheCase(
        aggregate.getId(),
        attached -> {
          attached.setCustomer("Frida the second");
          workflowService.businessCockpit().aggregateChanged(attached, userTaskId);
        });

    awaitReportCarrying(
        "/usertask/%s/updated".formatted(userTaskId), "Frida the second", aggregate);

  }

  @Test
  @DisplayName("A change made while a details provider holds the case is not written over")
  public void aChangeMadeWhileADetailsProviderHoldsTheCaseSurvives() {

    final var aggregate = aStartedWorkflowWhoseDetailsProviderIsHeld("Nora");
    final var userTaskId = userTaskIdOf(aggregate);
    // the details provider of the user task now waits inside the dispatch of the CREATED
    // report: it has read the case and has not written it back yet
    gate.awaitTheHeldCall();
    CockpitServer.forgetRequests();

    changeTheCase(
        aggregate.getId(),
        attached -> {
          attached.setCustomer("Nora the second");
          workflowService.businessCockpit().aggregateChanged(attached, userTaskId);
        });

    gate.letTheHeldCallFinish();

    // the held dispatch now writes the case back with the reading it took before the change, and
    // the version attribute turns that into a conflict, so the write is refused. Without it the
    // dispatch would win, the case would read "Nora" again and the report of the change would
    // read it too - which is the failure this test is here to catch
    awaitReportCarrying(
        "/usertask/%s/updated".formatted(userTaskId), "Nora the second", aggregate);
    assertEquals("Nora the second", storedCustomerOf(aggregate));

    // What happens to the report of the held dispatch is not asserted: it was sent before its
    // transaction tried to commit, so it carries the older reading either way, and whether the
    // outbox sends it a second time is the outbox's business. The note that provider wrote is
    // not asserted either. A write made in a details provider lives and dies with the
    // transaction of the dispatch it ran in.

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
