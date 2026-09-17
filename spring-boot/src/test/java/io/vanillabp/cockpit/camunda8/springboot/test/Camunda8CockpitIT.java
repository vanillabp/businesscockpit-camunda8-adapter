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
import java.util.regex.Pattern;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Business Cockpit against a real Camunda 8 cluster, from the model this application
 * deploys to the request the cockpit server receives.
 * <p>
 * Nothing on that way is faked but the cockpit server itself: the cluster runs the workflow,
 * hands out the listener jobs the extension put into the model, the extension reads them, runs
 * the application's details provider and writes one outbox entry carrying the finished report,
 * the job is completed, the entry is sent afterwards, and what arrives at the server is
 * asserted.
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
   * How long a wait for the cluster's searchable storage keeps hoping. It is the slowest thing
   * in this class by far.
   */
  private static final Duration WAITING_FOR_THE_CLUSTER = Duration.ofMinutes(4);

  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster();

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
  private IncidentWorkflowService incidentWorkflowService;

  @Autowired
  private CallingWorkflowService callingWorkflowService;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactories;

  @Autowired
  private DetailsProviderGate gate;

  /**
   * What the cockpit's neutral half asks about this cluster. One is registered per configured
   * Camunda 8 adapter id, and this application configures one.
   */
  @Autowired
  private BusinessCockpitBpmsBridge bridge;

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

  private CallingAggregate aStartedCallingWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new CallingAggregate();
          aggregate.setCustomer(customer);
          return callingWorkflowService.processes().startWorkflow(aggregate);
        });

  }

  /**
   * The process instance of the CALLING workflow of one case.
   * <p>
   * Both instances of such a case carry the aggregate's id, because a called process inherits the
   * variables of its caller, so the search says which of the two is meant: the one nobody called.
   * <p>
   * The process is named as well, and it has to be. Every workflow aggregate of this application
   * counts its ids for itself, so a case of this workflow and a case of another one share the id
   * 1, and a search by the variable alone finds whichever of them the cluster lists first.
   */
  private String callingWorkflowIdOf(
      final CallingAggregate aggregate) {

    return awaitValue(
        () -> client()
            .newProcessInstanceSearchRequest()
            .filter(
                filter -> filter
                    .processDefinitionId(
                        "%s__%s".formatted(MODULE_ID, CallingWorkflowService.BPMN_PROCESS_ID))
                    .variables(Map.of("id", "\"%s\"".formatted(aggregate.getId()))))
            .send()
            .join()
            .items()
            .stream()
            .filter(instance -> instance.getParentProcessInstanceKey() == null)
            .findFirst()
            .map(instance -> String.valueOf(instance.getProcessInstanceKey()))
            .orElse(null),
        "the calling workflow of aggregate %s".formatted(aggregate.getId()));

  }

  /**
   * Reads one identifier out of a body the cockpit server received.
   *
   * @param request What arrived
   * @param field The field to read, which carries a key as a string
   * @return Its value
   */
  private static String idOf(
      final CockpitServer.Request request,
      final String field) {

    final var matcher = Pattern
        .compile("\"%s\"\\s*:\\s*\"([^\"]+)\"".formatted(field))
        .matcher(request.body());
    assertTrue(matcher.find(), "no '%s' in %s".formatted(field, request.body()));
    return matcher.group(1);

  }

  /**
   * Starts a workflow whose user-task details provider is held at the gate.
   * <p>
   * The gate is closed while the starting transaction is still open, and that is what makes the
   * test deterministic. A workflow reaches the cluster only after the transaction of its start
   * committed, so nothing is reported and no provider runs before the gate is closed.
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
   * Changes one case in a transaction of its own, the way the application does it.
   * <p>
   * Nothing of the cockpit writes the case, so nothing collides here. A details provider is asked
   * a question and answers it. The version attribute of {@link TestAggregate} is what would turn a
   * second writer into a conflict rather than a silent overwrite.
   *
   * @param aggregateId The case to change
   * @param changeAndReport Changes the attached case, and reports it where a test wants that
   */
  private void changeTheCase(
      final Long aggregateId,
      final Consumer<TestAggregate> changeAndReport) {

    transactions
        .executeWithoutResult(status -> {
          final var attached = aggregates.findById(aggregateId).orElseThrow();
          changeAndReport.accept(attached);
          aggregates.save(attached);
        });

  }

  /**
   * Waits for a report of one kind which carries the value the application wrote.
   * <p>
   * A report carrying the older value has two possible causes, and they need different work. The
   * report read the case too early, or the case itself lost the change. So a failure names what
   * the case carries now as well. See the version attribute of {@link TestAggregate}.
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

    return workflowIdOf(TestWorkflowService.BPMN_PROCESS_ID, aggregate.getId());

  }

  /**
   * The process instance of one case.
   * <p>
   * The process is named as well, and it has to be. Every workflow aggregate of this application
   * counts its ids for itself, so a case of this workflow and a case of another one share the id
   * 1, and a search by the variable alone finds whichever of them the cluster lists first.
   *
   * @param bpmnProcessId The process as the application wrote it
   * @param aggregateId The case
   * @return The instance key
   */
  private String workflowIdOf(
      final String bpmnProcessId,
      final Long aggregateId) {

    return awaitValue(
        () -> client()
            .newProcessInstanceSearchRequest()
            .filter(
                filter -> filter
                    .processDefinitionId("%s__%s".formatted(MODULE_ID, bpmnProcessId))
                    .variables(Map.of("id", "\"%s\"".formatted(aggregateId))))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .map(instance -> String.valueOf(instance.getProcessInstanceKey()))
            .orElse(null),
        "the workflow of aggregate %s of '%s'".formatted(aggregateId, bpmnProcessId));

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
   * @param description What a failure says. It is read only then, so that it may name what the
   *          case and the cockpit server carry by the time the waiting gave up
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

  /**
   * The BPMN process of the test as the cluster spells it. The workflow module runs under
   * <code>use-prefix</code>, so every identifier the cluster knows carries the module's prefix.
   */
  private static String scopedProcessId() {

    return "%s__%s".formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID);

  }

  /**
   * The model of the test as the cluster holds it, listeners and all.
   *
   * @return The newest deployed version of it
   */
  private BpmnModelInstance theDeployedModel() {

    final var definitionKey = awaitValue(
        () -> client()
            .newProcessDefinitionSearchRequest()
            .filter(filter -> filter.processDefinitionId(scopedProcessId()))
            .send()
            .join()
            .items()
            .stream()
            .map(definition -> definition.getProcessDefinitionKey())
            .max(Long::compare)
            .orElse(null),
        "the deployed process definition");

    final var xml = client()
        .newProcessDefinitionGetXmlRequest(definitionKey)
        .send()
        .join();
    return Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * Deploys the model the cluster already holds a second time, with one attribute changed.
   * <p>
   * This is how a second version of one BPMN process gets into a test which has only one
   * application: the application deploys version 1 while it starts, and it deploys nothing
   * afterwards. Deploying the same bytes again would leave the cluster on version 1, because a
   * cluster counts a version per set of bytes. So the process gets another name, which is a
   * change no test reads and no listener depends on. Everything else stays what the extension
   * wrote into version 1, so the jobs of version 2 carry the very job types this workflow module
   * wired.
   *
   * @return The version the cluster assigned
   */
  private int aSecondVersionOfTheDeployedModel() {

    final var model = theDeployedModel();
    ((Process) model.getModelElementById(scopedProcessId()))
        .setName("The cockpit process, deployed a second time");
    return client()
        .newDeployResourceCommand()
        .addProcessModel(model, "%s.bpmn".formatted(TestWorkflowService.BPMN_PROCESS_ID))
        .send()
        .join()
        .getProcesses()
        .getFirst()
        .getVersion();

  }

  @Test
  @DisplayName("The deployed model carries the cockpit's listeners, under the identifiers the cluster knows")
  public void theDeployedModelCarriesTheListeners() {

    final var model = theDeployedModel();

    final var scopedProcessId = scopedProcessId();
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
    // shows that the provider ran on the real aggregate. It wrote nothing onto it, which is what
    // a details provider is: a question somebody answers.

  }

  @Test
  @DisplayName("A details provider is picked by the version of the deployed process")
  public void aDetailsProviderIsPickedByTheDeployedVersion() {

    // version 1 is what this application deployed while it started, and nothing else in this
    // class deploys anything
    final var onVersionOne = aStartedWorkflow("Vera");
    final var firstTask = awaitReportCarrying("/usertask/created", "\"customer\":\"Vera\"", onVersionOne);
    assertTrue(
        firstTask
            .body()
            .contains(servedBy(TestWorkflowService.SERVED_BY_VERSION_ONE)),
        firstTask.body());
    // and the version the cluster reported travelled all the way into the report
    assertTrue(firstTask.body().contains("\"bpmnProcessVersion\":\"1\""), firstTask.body());

    assertEquals(2, aSecondVersionOfTheDeployedModel(), "the cluster counted no second version");

    // a workflow started now runs on the version the cluster deployed last, so the method
    // serving '>1' is the one which has to run, and the method serving '1' is the one which
    // must not
    final var onVersionTwo = aStartedWorkflow("Viktor");
    final var secondTask = awaitReportCarrying(
        "/usertask/created", "\"customer\":\"Viktor\"", onVersionTwo);
    assertTrue(
        secondTask
            .body()
            .contains(servedBy(TestWorkflowService.SERVED_BY_LATER_VERSIONS)),
        secondTask.body());
    assertTrue(secondTask.body().contains("\"bpmnProcessVersion\":\"2\""), secondTask.body());

    // the workflow of that case is chosen the same way, by its own pair of methods
    final var secondWorkflow = awaitReportCarrying(
        "/workflow/created", "\"customer\":\"Viktor\"", onVersionTwo);
    assertTrue(
        secondWorkflow
            .body()
            .contains(servedBy(TestWorkflowService.SERVED_BY_LATER_VERSIONS)),
        secondWorkflow.body());

  }

  /**
   * @param servedBy What a details provider of the test application writes about itself
   * @return How a report carries it
   */
  private static String servedBy(
      final String servedBy) {

    return "\"%s\":\"%s\"".formatted(TestWorkflowService.SERVED_BY, servedBy);

  }

  @Test
  @DisplayName("A called process is a step of the case above it, not a case of its own")
  public void aCalledProcessIsAStepOfTheCaseAboveIt() {

    final var aggregate = aStartedCallingWorkflow("Della");

    // The user task sits in the CALLED process, and the workflow it is reported under has to be
    // the calling one. Which that is the job says only from 8.9 on; on 8.8 the extension asks the
    // cluster for the call hierarchy, and that answer comes from the searchable storage, so this
    // is also the test of whether the answer is there by the time a listener runs.
    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Della\"");
    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Della\"");
    assertEquals(
        callingWorkflowIdOf(aggregate), idOf(workflow, "workflowId"), workflow.body());
    assertEquals(
        idOf(workflow, "workflowId"), idOf(userTask, "workflowId"), userTask.body());

    // and the called process did not become a case beside it
    CockpitServer.awaitQuiet();
    assertEquals(
        1,
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .filter(request -> request.body().contains("\"customer\":\"Della\""))
            .count(),
        "one call, one case: "
            + CockpitServer.received().stream().map(CockpitServer.Request::path).toList());

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

    // and what the cockpit does NOT hear about is the workflow. The 'end' listener of a
    // process does not run when the instance is cancelled, and Camunda 8 has no listener for
    // a cancellation before 8.10. See decision 3 in the repository's DECISIONS.md
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

    // the start of such a workflow is reported by the listener the MESSAGE start event carries,
    // because every start event of a process gets one. It knows the case because the aggregate
    // id travelled with the message
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
    // provider holds the case over this transaction. The version attribute of TestAggregate is
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
  @DisplayName("A report carries the state its case had at the moment of the event")
  public void aReportCarriesTheStateOfItsEvent() {

    final var aggregate = aStartedWorkflowWhoseDetailsProviderIsHeld("Nora");
    // the details provider of the user task now waits inside the listener job of that task: it
    // holds the case as the event left it, and the transition waits with it
    gate.awaitTheHeldCall();
    CockpitServer.forgetRequests();

    // the application changes the very same case while the event is being reported, and commits
    changeTheCase(aggregate.getId(), attached -> attached.setCustomer("Nora the second"));

    gate.letTheHeldCallFinish();

    // the report is the report of its own event, so it says what the case said when the task was
    // created. Built at the dispatch, as it used to be, it would say "Nora the second" - a state
    // the task never had while it was coming into being
    final var created = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Nora");
    assertTrue(created.body().contains("\"customer\":\"Nora\""), created.body());
    // and the change of the application is still there: nothing of the report wrote the case back
    assertEquals("Nora the second", storedCustomerOf(aggregate));

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
  @DisplayName("A details provider which fails raises an incident, and the transition waits")
  public void aFailingDetailsProviderRaisesAnIncident() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new IncidentAggregate();
          fresh.setCustomer("Jonas");
          return incidentWorkflowService.processes().startWorkflow(fresh);
        });

    // the report is built inside the listener job of the user task, so a details provider which
    // throws fails that job. The listeners of this extension carry no retries, so the cluster
    // raises the incident at once instead of repeating quietly. Only reading happens on this way,
    // but what is read has to be right. See decision 8 in the repository's DECISIONS.md
    final var workflowId = workflowIdOf(
        IncidentWorkflowService.BPMN_PROCESS_ID, aggregate.getId());
    awaitValue(
        () -> client()
            .newProcessInstanceGetRequest(Long.parseLong(workflowId))
            .send()
            .join()
            .getHasIncident()
                ? Boolean.TRUE
                : null,
        "an incident on workflow %s, which its details provider was supposed to cause"
            .formatted(workflowId));

    // and nothing about that task reached the cockpit: the entry is written when the report is
    // complete, and this one never was
    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer
            .matching("/usertask/created")
            .stream()
            .filter(
                request -> request.body().contains(IncidentWorkflowService.TASK_DEFINITION))
            .toList(),
        "a task whose details provider failed was reported anyway");
    assertTrue(
        incidentWorkflowService.attempts() > 0,
        "the details provider was never called");

  }

  /**
   * A key of the shape the cluster hands out which it cannot hold. The keys of a partition are
   * counted up from one number, whatever they are handed out for, so a key a million past one the
   * cluster really gave is neither a user task nor a workflow of this run. The shape matters. A
   * number the gateway rejects as invalid is answered differently from one it simply does not
   * hold, and it is the second answer this is for.
   *
   * @param keyItHandedOut A key the cluster is known to hold
   * @return A key it does not
   */
  private static String aKeyTheClusterDoesNotHold(
      final String keyItHandedOut) {

    return String.valueOf(Long.parseLong(keyItHandedOut) + 1_000_000L);

  }

  @Test
  @DisplayName("A question about something the cluster does not hold is answered with nothing")
  public void aQuestionAboutSomethingTheClusterDoesNotHoldIsAnsweredWithNothing() {

    // This is the reading side of the bridge, the one which serves BusinessCockpitService. It
    // asks the cluster's searchable storage, and a record that storage holds none of means there
    // is nothing to show. Asking about a key the cluster never handed out is how that answer is
    // provoked without waiting for an exporter to fall behind. The reporting side does not come
    // here at all: it is answered out of the listener job, which is what
    // aReportCarriesTheStateOfItsEvent shows.
    final var aggregate = aStartedWorkflow("Dora");
    final var unknownWorkflowId = aKeyTheClusterDoesNotHold(workflowIdOf(aggregate));
    final var unknownUserTaskId = aKeyTheClusterDoesNotHold(userTaskIdOf(aggregate));
    // no process version: these references are about records the cluster does not hold, and a
    // version is something only such a record would name
    final var unknownUserTask = new UserTaskReference(
        "c8", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, null, String
            .valueOf(aggregate
                .getId()), unknownWorkflowId, unknownUserTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);
    final var unknownWorkflow = new WorkflowReference(
        "c8", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, null, String
            .valueOf(aggregate.getId()), unknownWorkflowId);

    assertTrue(
        bridge.prefilledUserTaskDetails(unknownUserTask).isEmpty(),
        "a task the cluster does not hold was answered with values");
    assertTrue(
        bridge.prefilledWorkflowDetails(unknownWorkflow).isEmpty(),
        "a workflow the cluster does not hold was answered with values");

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();

    assertTrue(registration.body().contains(MODULE_ID), registration.body());
    assertTrue(registration.body().contains("approvers"), registration.body());

  }

}
