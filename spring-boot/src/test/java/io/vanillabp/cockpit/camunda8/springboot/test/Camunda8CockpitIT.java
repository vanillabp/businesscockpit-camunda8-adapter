package io.vanillabp.cockpit.camunda8.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.api.Tag;
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
import io.camunda.client.api.response.ActivatedJob;
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
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.camunda8.wiring.Camunda8CancelListeners;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.CapturedOutput;
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
   * Tests which need a user task the cluster really created. The preview line hands none out, so
   * they are left out there.
   * <p>
   * The REST gateway of the 8.10 alpha drops a whole activate-jobs batch as soon as it meets a
   * task listener job whose event carries no user task action in its headers. The two events
   * without one are <code>creating</code> and <code>canceling</code>. So the
   * <code>creating</code> listener VanillaBP writes never reaches a worker, the task is never
   * finished being created, and it never reaches the state {@link UserTaskState#CREATED} which
   * {@link #userTaskIdOf(TestAggregate)} searches for. The bug is camunda/camunda#58193, and gap
   * 4 of GAPS.md tells it from the cockpit's side.
   * <p>
   * The other events are untouched. An <code>assigning</code> job triggered by an assign command,
   * an <code>updating</code> job and a <code>completing</code> job carry the action and reach
   * their worker on the alpha as fast as on a GA line.
   * <p>
   * The <code>line-8.10</code> profile of the parent POM excludes this tag, and the tag carries
   * the same name and the same meaning in vanillabp/camunda8-adapter, so a reader of both
   * repositories reads one thing. Everything else of the line passes, and that is what the
   * exclusion buys: a line which is always red says nothing on the day something else breaks in
   * it. When the pin moves to a newer alpha, measure rather than assume: deploy a user task with
   * a <code>creating</code> listener, start an instance and see whether the job arrives. Once one
   * does, the tag and the exclusion go away together.
   */
  private static final String USER_TASK_LISTENER_JOBS = "user-task-listener-jobs";

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
  private WaitingWorkflowService waitingWorkflowService;

  @Autowired
  private InterruptedWorkflowService interruptedWorkflowService;

  @Autowired
  private InterruptedAggregateRepository interruptedAggregates;

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
   * <p>
   * The process is named as well, and it has to be, for the reason
   * {@link #workflowIdOf(String, Long)} gives: every workflow aggregate of this application
   * counts its ids for itself, so a case of this workflow and a case of another one share the
   * id 1, and a search by the variable alone finds whichever task the cluster lists first.
   */
  private String userTaskIdOf(
      final TestAggregate aggregate) {

    return awaitValue(
        () -> client()
            .newUserTaskSearchRequest()
            .filter(
                filter -> filter
                    .state(UserTaskState.CREATED)
                    .bpmnProcessId(scopedProcessId())
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

    final var cockpitListenersAtTheProcess = executionListenersOf(model, scopedProcessId)
        .stream()
        .filter(
            listener -> Camunda8CockpitListeners
                .listenerTypeOf(scopedProcessId)
                .equals(listener.getType()))
        .toList();

    // Which of them the process carries is a question of the release line: the 'end' listener
    // reports a completed instance everywhere, and from 8.10 on a 'cancel' listener beside it
    // reports a terminated one. The event type which says 'cancel' has no name on the older
    // clients, so the helper of the line says which types the model has to carry.
    assertEquals(
        ProcessListenersOfTheLine.theEventTypesTheCockpitWrites(),
        cockpitListenersAtTheProcess
            .stream()
            .map(ZeebeExecutionListener::getEventType)
            .toList(),
        "the process carries other listeners of the cockpit than this release line writes");

  }

  @Tag(USER_TASK_LISTENER_JOBS)
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

  @Tag(USER_TASK_LISTENER_JOBS)
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

  @Tag(USER_TASK_LISTENER_JOBS)
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

  @Tag(USER_TASK_LISTENER_JOBS)
  @Test
  @DisplayName("Completing the user task reports the task and the workflow as completed")
  public void completingTheUserTaskIsReported() {

    final var aggregate = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Bert\"");

    transactions
        .executeWithoutResult(
            status -> workflowService
                .processes()
                .completeUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));

    assertNotNull(CockpitServer.awaitAnyRequest("/usertask/%s/completed".formatted(userTaskId)));
    assertNotNull(CockpitServer.awaitAnyRequest("/workflow/%s/completed".formatted(workflowId)));

  }

  @Tag(USER_TASK_LISTENER_JOBS)
  @Test
  @DisplayName("Cancelling a workflow which holds a user task reports that task, and the case as far as the line says it")
  public void cancellingTheWorkflowIsReportedAsFarAsCamundaSaysIt() {

    final var aggregate = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Cleo\"");

    // nothing of VanillaBP is involved here: this is what an operator does, and the cockpit
    // has to hear about it
    client().newCancelInstanceCommand(Long.parseLong(workflowId)).send().join();

    // the task listener of the task runs on every line, so this half is the same everywhere
    assertNotNull(CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId)));
    assertTheCaseWasReportedAsCancelledWhereTheLineSaysIt(workflowId);

    // never as completed, on any line. The 'end' listener of a process does not run when the
    // instance is cancelled
    assertEquals(
        List.of(),
        CockpitServer.matching("/workflow/%s/completed".formatted(workflowId)),
        "the cancelled workflow was reported as completed");

  }

  @Test
  @DisplayName("Cancelling a workflow which holds no user task reports the case as far as the line says it")
  public void cancellingAWaitingWorkflowIsReportedAsFarAsCamundaSaysIt() {

    final var aggregate = aStartedWaitingWorkflow("Cornelius");
    CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Cornelius\"");
    final var workflowId = workflowIdOf(WaitingWorkflowService.BPMN_PROCESS_ID, aggregate.getId());

    client().newCancelInstanceCommand(Long.parseLong(workflowId)).send().join();

    assertTheCaseWasReportedAsCancelledWhereTheLineSaysIt(workflowId);

  }

  @Tag(USER_TASK_LISTENER_JOBS)
  @Test
  @DisplayName("A user task taken away by an interrupting event is reported as cancelled while its case runs on")
  public void anInterruptedUserTaskIsReportedAsCancelled() {

    final var aggregate = aStartedInterruptedWorkflow("Ines");
    final var created = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Ines\"");
    final var userTaskId = idOf(created, "userTaskId");
    final var workflowId = idOf(created, "workflowId");

    // nothing about the CASE ends here. The message fires the interrupting boundary event of
    // the task, so the cluster cancels the task and the workflow walks on to an end event of
    // its own
    transactions
        .executeWithoutResult(
            status -> interruptedWorkflowService
                .processes()
                .correlateMessage(
                    interruptedAggregates.findById(aggregate.getId()).orElseThrow(),
                    InterruptedWorkflowService.INTERRUPT_MESSAGE));

    // the 'canceling' task listener runs on every release line, and this is the one way a
    // cluster cancels a user task without cancelling the case it belongs to
    assertNotNull(CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId)));
    assertNotNull(CockpitServer.awaitAnyRequest("/workflow/%s/completed".formatted(workflowId)));

    // the task went away rather than being done, so nobody may read it as done
    assertEquals(
        List.of(),
        CockpitServer.matching("/usertask/%s/completed".formatted(userTaskId)),
        "the interrupted user task was reported as completed");

  }

  private InterruptedAggregate aStartedInterruptedWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var fresh = new InterruptedAggregate();
          fresh.setCustomer(customer);
          return interruptedWorkflowService.processes().startWorkflow(fresh);
        });

  }

  @Test
  @DisplayName("A cancel listener which cannot answer leaves the cancellation with an incident")
  public void aFailingCancelListenerLeavesAnIncident() {

    assumeTrue(
        Camunda8CancelListeners.theProcessCanReportItsCancellation(),
        "a cluster of this release line hands out no job when an instance is cancelled");

    final var aggregate = aStartedWaitingWorkflow("Cassandra");
    CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Cassandra\"");
    final var workflowId = workflowIdOf(WaitingWorkflowService.BPMN_PROCESS_ID, aggregate.getId());

    // what a cancel listener with no retries costs the instance it runs in is nowhere in
    // Camunda's documentation, so it is measured here. See decision 11 in the repository's
    // DECISIONS.md
    waitingWorkflowService.theDetailsProviderFails(true);
    try {
      client().newCancelInstanceCommand(Long.parseLong(workflowId)).send().join();

      awaitValue(
          () -> client()
              .newProcessInstanceGetRequest(Long.parseLong(workflowId))
              .send()
              .join()
              .getHasIncident()
                  ? Boolean.TRUE
                  : null,
          "an incident on workflow %s, which its failing cancel listener was supposed to cause"
              .formatted(workflowId));
    } finally {
      waitingWorkflowService.theDetailsProviderFails(false);
    }

    // and nothing about the case reached the cockpit: the entry is written when the report is
    // complete, and this one never was
    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer.matching("/workflow/%s/cancelled".formatted(workflowId)),
        "a case whose cancel listener failed was reported anyway");

  }

  @Test
  @DisplayName("The answer of a run whose lock expired is refused, and the case is reported once")
  public void theAnswerOfTheRunWhoseLockExpiredIsRefused(
      final CapturedOutput output) {

    assumeTrue(
        theAdapterLeasesItsJobs(),
        "this build activates its jobs without a lease, so the cluster takes the answer of whichever run sends one first");

    waitingWorkflowService.forgetWhatWasReported();
    final var aggregate = aStartedWaitingWorkflowWhoseReportIsHeld("Leonie");
    // the report of the started case is inside the details provider now, and the lock of its
    // listener job is running out under it
    waitingWorkflowService.awaitTheHeldReport();

    // the second activation is this test's own rather than a redelivery. What is under test is
    // the ORDER of the two answers, and a redelivery cannot carry that order. On the preview
    // line this test runs on, the held run sits in the handler of the very worker the job came
    // from, which is the one place the job does not show up again; a repaired client does hand
    // it back there, but then the moment belongs to the cluster (see decision 13). The cluster
    // hands the job out again about a second after the lock ran out, measured by story 1346.
    // So the test takes the job the way another worker would
    final var takenOver = theListenerJobHandedOutAgain();
    assertNotNull(
        Camunda8JobLease.tokenOf(takenOver),
        "the activation which holds the listener job now carries a token of its own");

    waitingWorkflowService.letTheHeldReportAnswer();

    // the held run answers a job somebody else holds now. The lease is what makes the cluster
    // say so, and the adapter's protocol drops that answer instead of failing the job
    awaitValue(
        () -> aLineAboutTheJobSays(output, takenOver.getKey(), "another activation holds the job")
            ? Boolean.TRUE
            : null,
        () -> "the cluster to refuse the answer of the run whose lock had expired. The log so far: "
            + logOf(output));

    // the one thing which must not happen: the refused answer turning into a failure. The
    // listeners of this extension carry no retries, so a failure IS the incident, and it would
    // be an incident about a report which was written and is fine. See decision 13 in the
    // repository's DECISIONS.md
    assertFalse(
        aLineAboutTheJobSays(output, takenOver.getKey(), "failing the job"),
        "the refused answer was reported to the cluster as a failure of the job");

    // and the report itself went out. It was written before the answer was sent, so the case
    // reaches the cockpit although the cluster refused the run which reported it
    final var workflowId = workflowIdOf(WaitingWorkflowService.BPMN_PROCESS_ID, aggregate.getId());
    assertNotNull(
        CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Leonie\""));
    assertEquals(
        1,
        waitingWorkflowService.reportsBuilt(),
        "the report was built by the held run and by nobody else in this test");

    // the activation which holds the job answers, and that is what the workflow goes on with
    Camunda8JobLease
        .withToken(
            client().newCompleteCommand(takenOver.getKey()),
            Camunda8JobLease.tokenOf(takenOver))
        .send()
        .join();
    CockpitServer.awaitQuiet();
    assertFalse(
        client()
            .newProcessInstanceGetRequest(Long.parseLong(workflowId))
            .send()
            .join()
            .getHasIncident(),
        "the workflow whose listener job was answered twice carries an incident");

  }

  /**
   * The listener job of the started waiting case, activated once more and with a lease.
   * <p>
   * With a lease, because a job which was leased once is handed to nobody who does not lease.
   * With a long timeout, so that the run holding it keeps it until this test answers.
   *
   * @return The job, as another worker would receive it
   */
  private ActivatedJob theListenerJobHandedOutAgain() {

    final var jobType = Camunda8CockpitListeners
        .listenerTypeOf(
            "%s__%s".formatted(MODULE_ID, WaitingWorkflowService.BPMN_PROCESS_ID));
    return awaitValue(
        () -> Camunda8JobLease
            .leaseTheActivation(
                client()
                    .newActivateJobsCommand()
                    .jobType(jobType)
                    .maxJobsToActivate(1)
                    .timeout(Duration.ofMinutes(5))
                    .workerName("the-pod-which-took-over"))
            .requestTimeout(Duration.ofSeconds(2))
            .send()
            .join()
            .getJobs()
            .stream()
            .findFirst()
            .orElse(null),
        () -> "the listener job of type '%s' to be handed out a second time, which happens once the lock of the first run ran out"
            .formatted(jobType),
        // shorter than the usual wait on this cluster, and it has to be: the run holding the
        // report gives up after three minutes, and this test has to answer before that
        Duration.ofMinutes(2));

  }

  /**
   * Whether the jobs of this build are activated with a lease. It is the adapter's answer, and
   * it is false wherever the client of the release line has no lease, whatever is configured.
   */
  private boolean theAdapterLeasesItsJobs() {

    return clientFactories.getFactory("c8").getConfiguration().leasesItsJobs();

  }

  /**
   * Starts a waiting workflow whose report of the start is held inside its listener job.
   * <p>
   * The hold is armed while the starting transaction is still open, for the reason
   * {@link #aStartedWorkflowWhoseDetailsProviderIsHeld(String)} gives: the workflow reaches the
   * cluster after that transaction committed, so no listener job exists before the hold does.
   *
   * @param customer What the case is about
   * @return The started case
   */
  private WaitingAggregate aStartedWaitingWorkflowWhoseReportIsHeld(
      final String customer) {

    return transactions
        .execute(status -> {
          final var fresh = new WaitingAggregate();
          fresh.setCustomer(customer);
          final var started = waitingWorkflowService.processes().startWorkflow(fresh);
          waitingWorkflowService.holdTheNextReportOf(started.getId());
          return started;
        });

  }

  private static String logOf(
      final CapturedOutput output) {

    return output.getOut() + output.getErr();

  }

  /**
   * Whether the log holds a line about this job which carries the given phrase.
   * <p>
   * A reader of the log has to name the job it asks about. What a test is handed is
   * everything the class printed since it started, not only what its own test printed. So a
   * sentence another test provoked about its own job reads like a sentence about this one.
   * {@link #aFailingCancelListenerLeavesAnIncident()} writes 'failing the job' about a job of
   * its own. That sentence failed
   * {@link #theAnswerOfTheRunWhoseLockExpiredIsRefused(CapturedOutput)} the first night both
   * tests ran. They run on this release line alone, so the two had never met before.
   * <p>
   * The key alone, not the words around it. The adapter writes it as <code>job 123</code> in
   * the line about a refused answer and as <code>job '123'</code> in the line about a
   * failure, and a test must not depend on which of the two it reads. A job key is unique in
   * a cluster, so a line carrying it is a line about this job.
   *
   * @param output What the class has printed so far
   * @param jobKey The job this test asks about
   * @param phrase What the line has to say about it
   * @return Whether such a line was printed
   */
  private static boolean aLineAboutTheJobSays(
      final CapturedOutput output,
      final long jobKey,
      final String phrase) {

    final var key = Long.toString(jobKey);
    return logOf(output)
        .lines()
        .anyMatch(line -> line.contains(phrase) && line.contains(key));

  }

  private WaitingAggregate aStartedWaitingWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var fresh = new WaitingAggregate();
          fresh.setCustomer(customer);
          return waitingWorkflowService.processes().startWorkflow(fresh);
        });

  }

  /**
   * What a cancelled case reaches the cockpit as, which is a question of the release line.
   * <p>
   * From 8.10 on the process carries a <code>cancel</code> execution listener, so the case is
   * closed. Before that the cluster hands out no job when an instance is terminated, so nothing
   * about the case arrives at all and the cockpit keeps showing it as it last heard about it.
   * See decision 11 in the repository's DECISIONS.md.
   *
   * @param workflowId The instance which was cancelled
   */
  private static void assertTheCaseWasReportedAsCancelledWhereTheLineSaysIt(
      final String workflowId) {

    if (Camunda8CancelListeners.theProcessCanReportItsCancellation()) {
      assertNotNull(CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId)));
      return;
    }
    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer.matching("/workflow/%s/cancelled".formatted(workflowId)),
        "this release line reported a cancelled workflow, which no cluster of it can say");

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
    CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Emil\"");
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

  @Tag(USER_TASK_LISTENER_JOBS)
  @Test
  @DisplayName("An application reporting a changed aggregate updates the named user task")
  public void aggregateChangedUpdatesTheNamedUserTask() {

    final var aggregate = aStartedWorkflow("Frida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Frida\"");
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

  @Tag(USER_TASK_LISTENER_JOBS)
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

  @Tag(USER_TASK_LISTENER_JOBS)
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

  @Tag(USER_TASK_LISTENER_JOBS)
  @Test
  @DisplayName("Reading a user task reports nothing to the cockpit")
  public void readingAUserTaskReportsNothing() {

    final var aggregate = aStartedWorkflow("Ida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Ida\"");
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

  @Tag(USER_TASK_LISTENER_JOBS)
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

  @Tag(USER_TASK_LISTENER_JOBS)
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
