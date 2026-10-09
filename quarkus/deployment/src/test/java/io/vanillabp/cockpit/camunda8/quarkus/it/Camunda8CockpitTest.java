package io.vanillabp.cockpit.camunda8.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitListeners;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
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
 * <p>
 * The configuration marks every user task of the processes this application claims with
 * <code>implemented-externally: true</code>. No <code>@WorkflowTask</code> method serves these
 * tasks, because people work them off in the cockpit, and a details provider does not count as
 * serving a task. Without the mark, VanillaBP ends the start. The mark stands at each task by its
 * element id, not at the workflow, because that is the narrowest place for it. The process nobody
 * here claims gets no mark at its tasks, since VanillaBP asks nothing of them. It gets the mark at
 * the workflow instead, because VanillaBP ends the start for a deployed process nobody claims. YAML
 * comments do not survive the formatter, which is why this is written here.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@EnabledIf("dockerIsAvailable")
public class Camunda8CockpitTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "c8-cockpit";

  /**
   * The BPMN process of the file which no workflow aggregate of this application claims. The test
   * configuration marks it as implemented externally, because the start would end otherwise.
   */
  private static final String UNCLAIMED_PROCESS_ID = "IncidentDetailsProcess";

  /**
   * A BPMN process this application never deploys. A test deploys it straight to the cluster,
   * the way another application sharing the cluster would.
   */
  private static final String FOREIGN_PROCESS_ID = "ForeignProcess";

  /**
   * Where the addresses of the cluster are published.
   * <p>
   * This class is initialized twice, once while the application is built and again inside the
   * class loader of the running application. So a cluster started unconditionally would be two
   * clusters, and the copy running the tests would look at the empty one. The first copy starts
   * it and writes its addresses down. The second finds them and touches Testcontainers not at
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
              // names its H2 database 'c8-cockpit-quarkus'. That is safe while this is the only
              // test class booting the application. A second class using the file would share
              // the database without anybody noticing, so it should bring a
              // 'TestApplication.forTestClass' along, the way the Camunda 7 and the Process
              // Engine API adapters of the cockpit give each test class a database of its own
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
            .map(definition -> definition.getProcessDefinitionKey())
            // the newest version, because a test may have deployed one beside the version this
            // application deployed while it started
            .max(Long::compare)
            .orElse(null),
        "the deployed process definition '%s'".formatted(scopedProcessId));
    final var xml = client().newProcessDefinitionGetXmlRequest(definitionKey).send().join();
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

    final var scopedProcessId = "%s__%s"
        .formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID);
    final var model = deployedModelOf(scopedProcessId);
    ((Process) model.getModelElementById(scopedProcessId))
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

  /**
   * @param servedBy What a details provider of the test application writes about itself
   * @return How a report carries it
   */
  private static String servedBy(
      final String servedBy) {

    return "\"%s\":\"%s\"".formatted(TestWorkflowService.SERVED_BY, servedBy);

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

  /**
   * The key of the user task of one case, once the cluster's searchable storage knows it.
   * <p>
   * The process is named as well, and it has to be, for the reason {@link #callingWorkflowIdOf}
   * gives: every workflow aggregate of this application counts its ids for itself, so a case of
   * this workflow and a case of another one share the id 1, and a search by the variable alone
   * finds whichever task the cluster lists first.
   */
  private String userTaskIdOf(
      final TestAggregate aggregate) {

    return awaitValue(
        () -> client()
            .newUserTaskSearchRequest()
            .filter(
                filter -> filter
                    .state(UserTaskState.CREATED)
                    .bpmnProcessId(
                        "%s__%s".formatted(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID))
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

    final var started = aStartedCallingWorkflow("Della");

    // the user task sits in the CALLED process, and the workflow it is reported under has to be
    // the calling one. See decision 3
    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Della\"");
    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Della\"");
    assertEquals(callingWorkflowIdOf(started), idOf(workflow, "workflowId"), workflow.body());
    assertEquals(idOf(workflow, "workflowId"), idOf(userTask, "workflowId"), userTask.body());

    // and the called process did not become a case beside it
    CockpitServer.awaitQuiet();
    assertEquals(
        1,
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .filter(request -> request.body().contains("\"customer\":\"Della\""))
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
   * <p>
   * The process is named as well, because every workflow aggregate of this application counts its
   * ids for itself: a case of this workflow and a case of another one share the id 1.
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
  @DisplayName("A BPMN process marked as implemented externally gets no listeners")
  public void anUnclaimedProcessIsLeftAlone() {

    final var scopedProcessId = "%s__%s".formatted(MODULE_ID, UNCLAIMED_PROCESS_ID);
    final var model = deployedModelOf(scopedProcessId);

    // a listener of this extension carries no retries, so a job nobody serves would stop the
    // workflow where it sits. The line 'implemented-externally' lets the start go on, but it
    // makes nobody here serve the process
    assertEquals(List.of(), executionListenerTypesOf(model, scopedProcessId));
    assertEquals(List.of(), executionListenerTypesOf(model, "IncidentStart"));
    assertFalse(
        Bpmn
            .convertToString(model)
            .contains(
                Camunda8CockpitListeners
                    .listenerTypeOf("%s__%s__incidentApprove".formatted(MODULE_ID, UNCLAIMED_PROCESS_ID))),
        "the unclaimed process carries a task listener of the cockpit");

  }

  @Test
  @DisplayName("A process a claimed process calls and names as its secondary process carries the listeners")
  public void aSecondaryProcessCarriesTheListeners() {

    final var scopedProcessId = "%s__%s"
        .formatted(MODULE_ID, CallingWorkflowService.CALLED_BPMN_PROCESS_ID);
    final var scopedTaskDefinition = "%s__%s__%s"
        .formatted(
            MODULE_ID, CallingWorkflowService.CALLED_BPMN_PROCESS_ID,
            CallingWorkflowService.TASK_DEFINITION);
    final var model = deployedModelOf(scopedProcessId);

    // no workflow service names the called process as its main process. The calling one names
    // it among its secondary processes, and that is what makes VanillaBP count it as claimed
    assertTrue(
        executionListenerTypesOf(model, scopedProcessId)
            .contains(Camunda8CockpitListeners.listenerTypeOf(scopedProcessId)),
        "the called process carries no listener of the cockpit");
    assertTrue(
        Bpmn
            .convertToString(model)
            .contains(Camunda8CockpitListeners.listenerTypeOf(scopedTaskDefinition)),
        "the user task of the called process carries no listener of the cockpit");

  }

  @Test
  @DisplayName("Neither a process nobody here claims nor a process VanillaBP never deployed reports anything")
  public void unclaimedAndForeignProcessesReportNothing() throws Exception {

    final var unclaimedProcessId = "%s__%s".formatted(MODULE_ID, UNCLAIMED_PROCESS_ID);
    aWorkflowStartedInTheCluster(unclaimedProcessId);
    theForeignProcessDeployed();
    aWorkflowStartedInTheCluster(FOREIGN_PROCESS_ID);

    // both workflows wait at a user task now. That is where a listener of the cockpit would
    // have reported the task, and the start event behind them is where it would have reported
    // the workflow
    theUserTaskOf(unclaimedProcessId);
    theUserTaskOf(FOREIGN_PROCESS_ID);

    // a case of a claimed process, started after both, is reported. Once it has arrived and the
    // server is quiet, a report about the other two would have arrived as well
    aStartedWorkflow("Fenna");
    CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Fenna\"");
    CockpitServer.awaitQuiet();

    assertEquals(
        List.of(),
        CockpitServer
            .received()
            .stream()
            .filter(
                request -> request.body().contains(reportedProcess(UNCLAIMED_PROCESS_ID)) || request.body()
                    .contains(reportedProcess(FOREIGN_PROCESS_ID)))
            .map(CockpitServer.Request::path)
            .toList(),
        "the cockpit was told about a process nobody here claims");

  }

  /**
   * How a report names the BPMN process it is about. The test of a claimed process asserts the
   * same words, so a change of the report's shape turns that test red first.
   */
  private static String reportedProcess(
      final String bpmnProcessId) {

    return "\"bpmnProcessId\":\"%s\"".formatted(bpmnProcessId);

  }

  /**
   * Starts a workflow the way a system other than this application would: through the client,
   * with a variable that looks like an aggregate id, and without VanillaBP knowing of it.
   */
  private void aWorkflowStartedInTheCluster(
      final String scopedProcessId) {

    client()
        .newCreateInstanceCommand()
        .bpmnProcessId(scopedProcessId)
        .latestVersion()
        .variables(Map.of("id", "not-an-aggregate"))
        .send()
        .join();

  }

  /**
   * Deploys a process this application does not know, straight to the cluster, the way another
   * application sharing the cluster would. It has a Camunda user task, so a listener of the
   * cockpit would have something to report if it were there.
   */
  private void theForeignProcessDeployed() {

    final var model = Bpmn
        .createExecutableProcess(FOREIGN_PROCESS_ID)
        .startEvent("ForeignStart")
        .userTask("ForeignTask")
        .zeebeUserTask()
        .endEvent("ForeignEnd")
        .done();
    client()
        .newDeployResourceCommand()
        .addProcessModel(model, "%s.bpmn".formatted(FOREIGN_PROCESS_ID))
        .send()
        .join();

  }

  private String theUserTaskOf(
      final String scopedProcessId) {

    return awaitValue(
        () -> client()
            .newUserTaskSearchRequest()
            .filter(
                filter -> filter.state(UserTaskState.CREATED).bpmnProcessId(scopedProcessId))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .map(task -> String.valueOf(task.getUserTaskKey()))
            .orElse(null),
        "a user task of '%s'".formatted(scopedProcessId));

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
        workflow.body().contains(reportedProcess(TestWorkflowService.BPMN_PROCESS_ID)),
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
  @DisplayName("A details provider reads with @TaskParam a process variable no workflow task reads")
  public void aDetailsProviderReadsAProcessVariable() throws Exception {

    aStartedWorkflow("Tilda");

    // the provider wrote what its @TaskParam parameter received. Nothing but the provider names
    // the variable, so it reached the listener job only because the worker asked for it
    CockpitServer
        .awaitRequest(
            "/usertask/created", "\"%s\":\"Tilda\"".formatted(TestWorkflowService.CUSTOMER_VARIABLE));

  }

  @Test
  @DisplayName("A details provider is picked by the version of the deployed process")
  public void aDetailsProviderIsPickedByTheDeployedVersion() throws Exception {

    // version 1 is what this application deployed while it started, and nothing else in this
    // class deploys anything
    aStartedWorkflow("Vera");
    final var firstTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Vera\"");
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
    aStartedWorkflow("Viktor");
    final var secondTask = CockpitServer
        .awaitRequest("/usertask/created", "\"customer\":\"Viktor\"");
    assertTrue(
        secondTask
            .body()
            .contains(servedBy(TestWorkflowService.SERVED_BY_LATER_VERSIONS)),
        secondTask.body());
    assertTrue(secondTask.body().contains("\"bpmnProcessVersion\":\"2\""), secondTask.body());

    // the workflow of that case is chosen the same way, by its own pair of methods
    final var secondWorkflow = CockpitServer
        .awaitRequest("/workflow/created", "\"customer\":\"Viktor\"");
    assertTrue(
        secondWorkflow
            .body()
            .contains(servedBy(TestWorkflowService.SERVED_BY_LATER_VERSIONS)),
        secondWorkflow.body());

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
  @DisplayName("A question about something the cluster does not hold is answered with nothing")
  public void aQuestionAboutSomethingTheClusterDoesNotHoldIsAnsweredWithNothing() throws Exception {

    // the reading side of the bridge, the one which serves BusinessCockpitService: it asks the
    // cluster's searchable storage, and a record that storage holds none of means there is
    // nothing to show. Asking about a key the cluster never handed out provokes that answer
    // without waiting for an exporter to fall behind
    final var started = aStartedWorkflow("Dora");
    // the keys of a partition are counted up from one number whatever they are handed out for, so
    // a key a million past a real one is neither a user task nor a workflow of this run
    final var unknownKey = String.valueOf(Long.parseLong(userTaskIdOf(started)) + 1_000_000L);
    // no process version: these references are about records the cluster does not hold, and a
    // version is something only such a record would name
    final var unknownUserTask = new UserTaskReference(
        ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, null, String.valueOf(started
            .getId()), unknownKey, unknownKey, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);
    final var unknownWorkflow = new WorkflowReference(
        ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, null, String.valueOf(started
            .getId()), unknownKey);

    assertTrue(
        bridges.getFirst().prefilledUserTaskDetails(unknownUserTask).isEmpty(),
        "a task the cluster does not hold was answered with values");
    assertTrue(
        bridges.getFirst().prefilledWorkflowDetails(unknownWorkflow).isEmpty(),
        "a workflow the cluster does not hold was answered with values");

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
