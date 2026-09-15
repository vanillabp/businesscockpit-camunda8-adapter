package io.vanillabp.cockpit.camunda8.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.DockerClientFactory;

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
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitReads;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The Camunda 8 half of the Business Cockpit inside a booted Quarkus application: the extension
 * is enabled, its listeners sit in the deployed model, its workers serve the jobs those
 * listeners produce, and what the workflow does reaches the cockpit server.
 * <p>
 * It runs the same way through as the Spring Boot test of this repository, and it exists
 * because a platform-neutral half being right says nothing about a platform's glue ever calling
 * it.
 * <p>
 * The cluster is started in a static initializer rather than by the Testcontainers extension:
 * the application's configuration needs the mapped ports, and the extension below reads its
 * runtime properties while its field is initialized, which happens before any extension
 * callback runs.
 * <p>
 * That initializer is what makes the Docker check a condition of its own here, where the Spring
 * Boot test says {@code @Testcontainers(disabledWithoutDocker = true)} and is done: a machine
 * without Docker has to reach the skip without a container ever being asked for.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@EnabledIf("dockerIsAvailable")
public class Camunda8CockpitTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "c8-cockpit";

  /** The BPMN process of the file which no workflow aggregate of this application claims. */
  private static final String UNCLAIMED_PROCESS_ID = "RetriedDetailsProcess";

  /**
   * Where the addresses of the cluster are published.
   * <p>
   * This class is initialized twice - once while the application is built and again inside the
   * class loader of the running application - so a cluster started unconditionally would be two
   * clusters, and the copy running the tests would look at the empty one. The first copy starts
   * it and writes its addresses down; the second finds them and touches Testcontainers not at
   * all.
   */
  private static final String REST_ADDRESS_PROPERTY = "businesscockpit.test.cluster.rest";

  /**
   * @see #REST_ADDRESS_PROPERTY
   */
  private static final String GRPC_ADDRESS_PROPERTY = "businesscockpit.test.cluster.grpc";

  /**
   * Whether this machine can run the cluster these tests need. Read by the condition above and
   * by the initializer below, which is why it is a method rather than a constant: a machine
   * without Docker skips the class instead of failing while it is loaded.
   *
   * @return Whether Docker answers
   */
  static boolean dockerIsAvailable() {

    return DockerClientFactory.instance().isDockerAvailable();

  }

  static {
    if (dockerIsAvailable()) {
      startTheClusterUnlessItRuns();
    }
  }

  private static void startTheClusterUnlessItRuns() {

    if (System.getProperty(REST_ADDRESS_PROPERTY) != null) {
      return;
    }
    final var camunda = ClusterUnderTest.cluster();
    camunda.start();
    // stopped by this copy of the class rather than by a test callback: the copy which
    // starts the cluster is the one built with the application, and no test ever runs in it
    Runtime.getRuntime().addShutdownHook(new Thread(camunda::stop));
    System
        .setProperty(
            REST_ADDRESS_PROPERTY,
            "http://%s:%d".formatted(camunda.getHost(), camunda.getMappedPort(8080)));
    System
        .setProperty(
            GRPC_ADDRESS_PROPERTY,
            "http://%s:%d".formatted(camunda.getHost(), camunda.getMappedPort(26500)));

  }


  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit.yaml", "application.yaml")
              .addAsResource("c8-cockpit/processes/cockpit-process.bpmn")
              .addAsResource("c8-cockpit/processes/calling-process.bpmn")
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class)
              .addClass(CallingAggregate.class)
              .addClass(CallingAggregatePersistence.class)
              .addClass(CallingWorkflowService.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl())
      // the fallbacks are what a machine without Docker gets, and nothing ever connects to
      // them: this field is built while the class is loaded, and the class is skipped
      .overrideRuntimeConfigKey(
          "vanillabp.adapters.c8.rest-address",
          System.getProperty(REST_ADDRESS_PROPERTY, "http://localhost:8080"))
      .overrideRuntimeConfigKey(
          "vanillabp.adapters.c8.grpc-address",
          System.getProperty(GRPC_ADDRESS_PROPERTY, "http://localhost:26500"));

  @Inject
  TestWorkflowService workflowService;

  @Inject
  TestAggregatePersistence aggregates;

  @Inject
  CallingWorkflowService callingWorkflowService;

  @Inject
  Camunda8ClientFactoryRegistry clientFactories;

  @Inject
  List<BusinessCockpitBpmsBridge> bridges;

  @Inject
  UserTransaction transaction;

  private CamundaClient client() {

    return clientFactories.getFactory(ADAPTER_ID).getClient();

  }

  private TestAggregate aStartedWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    try {
      final var aggregate = new TestAggregate();
      aggregate.setCustomer(customer);
      final var started = workflowService.processes().startWorkflow(aggregate);
      transaction.commit();
      return started;
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

  }

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

  private BpmnModelInstance deployedModelOf(
      final String scopedProcessId) {

    final var definitionKey = awaitValue(
        () -> client()
            .newProcessDefinitionSearchRequest()
            .filter(filter -> filter.processDefinitionId(scopedProcessId))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .map(definition -> definition.getProcessDefinitionKey())
            .orElse(null),
        "the deployed process definition '%s'".formatted(scopedProcessId));
    final var xml = client().newProcessDefinitionGetXmlRequest(definitionKey).send().join();
    return Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<String> executionListenerTypesOf(
      final BpmnModelInstance model,
      final String elementId) {

    final var element = (BaseElement) model.getModelElementById(elementId);
    final var listeners = element.getSingleExtensionElement(ZeebeExecutionListeners.class);
    return listeners == null
        ? List.of()
        : listeners
            .getExecutionListeners()
            .stream()
            .map(ZeebeExecutionListener::getType)
            .toList();

  }

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

  @Test
  @DisplayName("A called process is a step of the case above it, not a case of its own")
  public void aCalledProcessIsAStepOfTheCaseAboveIt() throws Exception {

    final var started = aStartedCallingWorkflow("Cleo");

    // the user task sits in the CALLED process, and the workflow it is reported under has to be
    // the calling one - see decision 3
    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Cleo\"");
    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Cleo\"");
    assertEquals(callingWorkflowIdOf(started), idOf(workflow, "workflowId"), workflow.body());
    assertEquals(idOf(workflow, "workflowId"), idOf(userTask, "workflowId"), userTask.body());

    // and the called process did not become a case beside it
    CockpitServer.awaitQuiet();
    assertEquals(
        1,
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .filter(request -> request.body().contains("\"customer\":\"Cleo\""))
            .count(),
        "one call, one case");

  }

  private CallingAggregate aStartedCallingWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    try {
      final var aggregate = new CallingAggregate();
      aggregate.setCustomer(customer);
      final var started = callingWorkflowService.processes().startWorkflow(aggregate);
      transaction.commit();
      return started;
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

  }

  /**
   * The process instance of the CALLING workflow of one case. Both instances carry the
   * aggregate's id, because a called process inherits the variables of its caller, so the search
   * says which of the two is meant: the one nobody called.
   */
  private String callingWorkflowIdOf(
      final CallingAggregate aggregate) {

    return awaitValue(
        () -> client()
            .newProcessInstanceSearchRequest()
            .filter(
                filter -> filter
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

  @Test
  @DisplayName("The deployed model carries the cockpit's listeners")
  public void theDeployedModelCarriesTheListeners() {

    final var scopedProcessId = "%s__%s".formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID);
    final var scopedTaskDefinition = "%s__%s__%s"
        .formatted(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION);
    final var model = deployedModelOf(scopedProcessId);

    final var taskListenerTypes = ((UserTask) model
        .getModelElementById(TestWorkflowService.BPMN_TASK_ID))
        .getSingleExtensionElement(ZeebeTaskListeners.class)
        .getTaskListeners()
        .stream()
        .map(ZeebeTaskListener::getType)
        .toList();
    assertTrue(
        taskListenerTypes.contains(Camunda8CockpitListeners.listenerTypeOf(scopedTaskDefinition)),
        taskListenerTypes.toString());
    assertTrue(
        executionListenerTypesOf(model, "Start")
            .contains(Camunda8CockpitListeners.listenerTypeOf(scopedProcessId)),
        "the start event carries no listener of the cockpit");
    assertTrue(
        executionListenerTypesOf(model, scopedProcessId)
            .contains(Camunda8CockpitListeners.listenerTypeOf(scopedProcessId)),
        "the process carries no listener of the cockpit");

  }

  @Test
  @DisplayName("A BPMN process no workflow aggregate claims gets no listeners")
  public void anUnclaimedProcessIsLeftAlone() {

    final var scopedProcessId = "%s__%s".formatted(MODULE_ID, UNCLAIMED_PROCESS_ID);
    final var model = deployedModelOf(scopedProcessId);

    // a listener of this extension carries no retries, so a job nobody serves would stop the
    // workflow where it sits - and nobody serves a process this application knows no case of
    assertEquals(List.of(), executionListenerTypesOf(model, scopedProcessId));
    assertEquals(List.of(), executionListenerTypesOf(model, "RetriedStart"));
    assertFalse(
        Bpmn
            .convertToString(model)
            .contains(
                Camunda8CockpitListeners
                    .listenerTypeOf("%s__%s__retriedApprove".formatted(MODULE_ID, UNCLAIMED_PROCESS_ID))),
        "the unclaimed process carries a task listener of the cockpit");

  }

  @Test
  @DisplayName("One bridge per configured Camunda 8 adapter id is a bean")
  public void oneBridgePerAdapterIdIsABean() {

    assertEquals(
        List.of(ADAPTER_ID), bridges.stream().map(BusinessCockpitBpmsBridge::adapterId).toList());
    assertEquals("camunda8", bridges.getFirst().adapterType());

  }

  @Test
  @DisplayName("A started workflow and its user task reach the cockpit, enriched by the application")
  public void aStartedWorkflowReachesTheCockpit() throws Exception {

    final var started = aStartedWorkflow("Anna");

    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Anna\"");
    assertTrue(
        workflow.body().contains("\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.BPMN_PROCESS_ID)),
        workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Anna\"");
    assertTrue(
        userTask.body().contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"approvers\"]"), userTask.body());

    // the details provider ran on the real aggregate and changed it. What this shows is the
    // provider running and nothing about a database: the persistence of this application is a
    // map which hands out the very object the provider was given
    assertEquals(TestWorkflowService.APPROVE_NOTE, aggregates.byId(started.getId()).getNote());

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates its workflow and its user task")
  public void aggregateChangedUpdatesWhatTheCockpitShows() throws Exception {

    final var started = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(started);

    transaction.begin();
    try {
      final var attached = aggregates.byId(started.getId());
      attached.setCustomer("Cleo the second");
      workflowService.businessCockpit().aggregateChanged(attached);
      workflowService.businessCockpit().aggregateChanged(attached, userTaskId);
    } finally {
      transaction.commit();
    }

    final var workflow = CockpitServer.awaitRequest("/updated", "\"customer\":\"Cleo the second\"");
    assertTrue(workflow.path().contains("/workflow/"), workflow.path());
    CockpitServer.awaitRequest("/usertask/%s/updated".formatted(userTaskId), "Cleo the second");

  }

  @Test
  @DisplayName("A report about something the cluster does not hold asks again instead of failing")
  public void aReportAboutSomethingTheClusterDoesNotHoldAsksAgain() throws Exception {

    // see the test of the same name in the Spring Boot module for what this asks and why it asks
    // it this way: the answer a cluster gives while its exporter is behind is the answer it gives
    // for a key it never handed out
    final var started = aStartedWorkflow("Dora");
    // the keys of a partition are counted up from one number whatever they are handed out for, so
    // a key a million past a real one is neither a user task nor a workflow of this run
    final var unknownKey = String.valueOf(Long.parseLong(userTaskIdOf(started)) + 1_000_000L);
    final var unknownUserTask = new UserTaskReference(
        ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(started
            .getId()), unknownKey, unknownKey, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);
    final var unknownWorkflow = new WorkflowReference(
        ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(started.getId()), unknownKey);

    final var aboutTheUserTask = assertThrows(
        PhaseTwoRetryLater.class,
        () -> bridges.getFirst().prefilledUserTaskDetails(unknownUserTask));
    assertEquals(
        Camunda8CockpitReads.WHILE_THE_EXPORTER_CATCHES_UP,
        aboutTheUserTask.getRetryAfter(),
        "the report does not come back within the window the extension names");

    final var aboutTheWorkflow = assertThrows(
        PhaseTwoRetryLater.class,
        () -> bridges.getFirst().prefilledWorkflowDetails(unknownWorkflow));
    assertEquals(
        Camunda8CockpitReads.WHILE_THE_EXPORTER_CATCHES_UP, aboutTheWorkflow.getRetryAfter());

  }

  @Test
  @DisplayName("An application can read one user task of its own case")
  public void getUserTaskAnswersForItsOwnAggregate() throws Exception {

    final var started = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(started);

    transaction.begin();
    try {
      final var userTask = workflowService
          .businessCockpit()
          .getUserTask(aggregates.byId(started.getId()), userTaskId);
      assertTrue(userTask.isPresent(), "the case's own task was not answered");
      assertEquals(TestWorkflowService.TASK_DEFINITION, userTask.get().getTaskDefinition());
      assertEquals(TestWorkflowService.BPMN_TASK_ID, userTask.get().getBpmnTaskId());
    } finally {
      transaction.commit();
    }

  }

}
