package io.vanillabp.cockpit.camunda8.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

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
 * The same way through as {@link Camunda8CockpitIT}, on the name-clash avoidance an application
 * gets when it configures none.
 * <p>
 * That default is <code>by-adapter</code>, which on Camunda 8 means a TENANT named after the
 * workflow module - Version 1's behaviour, and therefore the mode an application upgrading runs
 * under unless it decides otherwise. What it costs the test is a cluster which asks for
 * credentials: Camunda refuses to start with multi-tenancy on and its API unprotected.
 * <p>
 * What is asserted here and nowhere else: the identifiers stay PLAIN in the model and in every
 * report, and the listener jobs still reach the extension although they are handed out for a
 * tenant - a worker which had not subscribed for it would never see them.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TestApplication.class, properties = "spring.config.name=by-adapter")
// closed when the class is done: a context outliving its cluster keeps its workers polling an
// address nobody answers
@DirtiesContext
public class Camunda8CockpitByAdapterIT {

  private static final String MODULE_ID = "c8-cockpit";

  static final Network NETWORK = Network.newNetwork();

  @Container
  static final GenericContainer<?> ELASTICSEARCH = ClusterUnderTest.elasticsearch(NETWORK);

  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.clusterWithTenants(NETWORK, ELASTICSEARCH);

  @DynamicPropertySource
  static void theClusterAndTheCockpitServer(
      final DynamicPropertyRegistry registry) {

    final var restAddress = "http://"
        + CAMUNDA.getHost()
        + ":"
        + CAMUNDA.getMappedPort(8080);
    // before the application boots: the adapter looks the tenant up before it deploys into it,
    // and a tenant which does not exist ends that boot naming the mode which needs none
    ClusterUnderTest.createTenant(restAddress, MODULE_ID);
    registry.add("vanillabp.adapters.c8.rest-address", () -> restAddress);
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
  private TransactionTemplate transactions;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactories;

  private CamundaClient client() {

    return clientFactories.getFactory("c8").getClient();

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

  @Test
  @DisplayName("The models are deployed into the tenant the workflow module is named after, with plain identifiers")
  public void theModelsAreDeployedIntoTheModulesTenant() {

    final var definition = awaitValue(
        () -> client()
            .newProcessDefinitionSearchRequest()
            .filter(filter -> filter.processDefinitionId(TestWorkflowService.BPMN_PROCESS_ID))
            .send()
            .join()
            .items()
            .stream()
            .findFirst()
            .orElse(null),
        "the deployed process definition");

    // no configured tenant, so the tenant IS the workflow module id
    assertEquals(MODULE_ID, definition.getTenantId());

    final var xml = client()
        .newProcessDefinitionGetXmlRequest(definition.getProcessDefinitionKey())
        .send()
        .join();
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    // nothing is prefixed under this mode: the tenant is what keeps the modules apart, so the
    // listener types of the cockpit are the ones Version 1 wrote, character for character
    final var taskListenerTypes = ((UserTask) model
        .getModelElementById(TestWorkflowService.BPMN_TASK_ID))
        .getSingleExtensionElement(ZeebeTaskListeners.class)
        .getTaskListeners()
        .stream()
        .map(ZeebeTaskListener::getType)
        .toList();
    assertTrue(
        taskListenerTypes
            .contains(Camunda8CockpitListeners.listenerTypeOf(TestWorkflowService.TASK_DEFINITION)),
        taskListenerTypes.toString());
    assertTrue(
        executionListenerTypesOf(model, "Start")
            .contains(Camunda8CockpitListeners.listenerTypeOf(TestWorkflowService.BPMN_PROCESS_ID)),
        "the start event carries no listener of the cockpit");
    assertTrue(
        executionListenerTypesOf(model, TestWorkflowService.BPMN_PROCESS_ID)
            .contains(Camunda8CockpitListeners.listenerTypeOf(TestWorkflowService.BPMN_PROCESS_ID)),
        "the process carries no listener of the cockpit");

  }

  @Test
  @DisplayName("A workflow of the tenant reports its start and its user task to the cockpit")
  public void aWorkflowOfTheTenantReachesTheCockpit() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer("Nora");
          return workflowService.processes().startWorkflow(fresh);
        });

    final var workflow = CockpitServer.awaitRequest("/workflow/created", "\"customer\":\"Nora\"");
    assertTrue(
        workflow.body().contains("\"aggregateId\":\"%s\"".formatted(aggregate.getId())),
        workflow.body());
    assertTrue(
        workflow.body().contains("\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.BPMN_PROCESS_ID)),
        workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"customer\":\"Nora\"");
    assertTrue(
        userTask.body().contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());

  }

}
