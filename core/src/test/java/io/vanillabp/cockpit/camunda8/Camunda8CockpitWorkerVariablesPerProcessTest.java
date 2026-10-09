package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;

import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two BPMN processes of one workflow module use the same element id for a user task, and each
 * process has its own details provider for it. A worker asks only about the processes it serves.
 * So its jobs carry what the provider of their own process reads, and not what the provider of
 * the other process reads under the same element id.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitWorkerVariablesPerProcessTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "cockpit-module";

  private static final String AGGREGATE_ID_NAME = "orderId";

  private static final String SHIPPING = "Shipping";

  private static final String BILLING = "Billing";

  /** The element id both processes use for their user task. */
  private static final String BPMN_TASK_ID = "Review";

  private static final String SHIPPING_TASK_DEFINITION = "reviewShipping";

  private static final String BILLING_TASK_DEFINITION = "reviewBilling";

  /** A task definition both processes use, so that one worker serves both. */
  private static final String SHARED_TASK_DEFINITION = "review";

  private static final String SHIPPING_LISTENER_TYPE = Camunda8CockpitListeners
      .listenerTypeOf(SHIPPING_TASK_DEFINITION);

  private static final String BILLING_LISTENER_TYPE = Camunda8CockpitListeners.listenerTypeOf(BILLING_TASK_DEFINITION);

  private static final String SHARED_LISTENER_TYPE = Camunda8CockpitListeners.listenerTypeOf(SHARED_TASK_DEFINITION);

  private final Camunda8AdapterConfiguration configuration = new Camunda8AdapterConfiguration();

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final JobWorkerBuilderStep3 shippingWorker = mock(JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final JobWorkerBuilderStep3 billingWorker = mock(JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final JobWorkerBuilderStep3 sharedWorker = mock(JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  /** The questions the workers asked, as "process/task definition/element id". */
  private final List<String> askedAbout = new LinkedList<>();

  /**
   * The application's answer, by BPMN process only: the provider of the shipping process reads
   * the courier, the one of the billing process reads the auditor. Both serve the same element
   * id.
   */
  private final BusinessCockpitEventPublisher application = new BusinessCockpitEventPublisher() {

    @Override
    public boolean publishUserTaskEvent(
        final UserTaskReference userTask,
        final UserTaskEventKind kind,
        final String bpmsEventId,
        final OffsetDateTime timestamp,
        final EventTransaction transaction) {

      return false;

    }

    @Override
    public boolean publishWorkflowEvent(
        final WorkflowReference workflow,
        final WorkflowEventKind kind,
        final String bpmsEventId,
        final OffsetDateTime timestamp,
        final EventTransaction transaction) {

      return false;

    }

    @Override
    public List<String> variablesTheDetailsProvidersRead(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition,
        final String bpmnTaskId) {

      assertEquals(MODULE_ID, workflowModuleId);
      askedAbout.add(bpmnProcessId
          + "/"
          + taskDefinition
          + "/"
          + bpmnTaskId);
      return switch (bpmnProcessId) {
        case SHIPPING -> List.of("courier");
        case BILLING -> List.of("auditor");
        default -> List.of();
      };

    }

  };

  private Camunda8CockpitWorkers workers;

  @BeforeEach
  public void aCluster() {

    final var factory = clientFactories.getFactory(ADAPTER_ID);
    when(factory.getConfiguration()).thenReturn(configuration);
    when(factory.getClient().newWorker().jobType(SHIPPING_LISTENER_TYPE).handler(any())).thenReturn(shippingWorker);
    when(factory.getClient().newWorker().jobType(BILLING_LISTENER_TYPE).handler(any())).thenReturn(billingWorker);
    when(factory.getClient().newWorker().jobType(SHARED_LISTENER_TYPE).handler(any())).thenReturn(sharedWorker);
    when(shippingWorker.open()).thenReturn(mock(JobWorker.class));
    when(billingWorker.open()).thenReturn(mock(JobWorker.class));
    when(sharedWorker.open()).thenReturn(mock(JobWorker.class));

    workers = new Camunda8CockpitWorkers(
        new Camunda8Clients(clientFactories, null), deployments, (
            workflowModuleId,
            adapterId) -> Duration.ofMinutes(5), Camunda8Metrics.NONE, () -> application);

  }

  @Test
  @DisplayName("Each worker asks about its own process and fetches only what that process's provider reads")
  public void eachWorkerAsksAboutItsOwnProcess() {

    registerUserTask(SHIPPING_LISTENER_TYPE, SHIPPING, SHIPPING_TASK_DEFINITION);
    registerUserTask(BILLING_LISTENER_TYPE, BILLING, BILLING_TASK_DEFINITION);

    workers.open(ADAPTER_ID, MODULE_ID, new Camunda8MultiInstance.Registry());

    verify(shippingWorker).fetchVariables(variables("courier"));
    verify(billingWorker).fetchVariables(variables("auditor"));
    assertEquals(
        List
            .of(
                SHIPPING
                    + "/"
                    + SHIPPING_TASK_DEFINITION
                    + "/"
                    + BPMN_TASK_ID,
                BILLING
                    + "/"
                    + BILLING_TASK_DEFINITION
                    + "/"
                    + BPMN_TASK_ID),
        askedAbout);

  }

  @Test
  @DisplayName("A worker serving the task in both processes asks about each process and fetches both answers")
  public void aWorkerOfBothProcessesFetchesBothAnswers() {

    registerUserTask(SHARED_LISTENER_TYPE, SHIPPING, SHARED_TASK_DEFINITION);
    registerUserTask(SHARED_LISTENER_TYPE, BILLING, SHARED_TASK_DEFINITION);

    workers.open(ADAPTER_ID, MODULE_ID, new Camunda8MultiInstance.Registry());

    verify(sharedWorker).fetchVariables(variables("auditor", "courier"));
    assertEquals(
        List
            .of(
                SHIPPING
                    + "/"
                    + SHARED_TASK_DEFINITION
                    + "/"
                    + BPMN_TASK_ID,
                BILLING
                    + "/"
                    + SHARED_TASK_DEFINITION
                    + "/"
                    + BPMN_TASK_ID),
        askedAbout);

  }

  private void registerUserTask(
      final String listenerType,
      final String bpmnProcessId,
      final String taskDefinition) {

    deployments
        .register(
            ADAPTER_ID, MODULE_ID,
            new WiredListener(
                listenerType, bpmnProcessId, bpmnProcessId, BPMN_TASK_ID, null, null, AGGREGATE_ID_NAME, taskDefinition));

  }

  /**
   * What a worker of a user task outside any multi-instance element fetches: the aggregate's
   * id, the name a caller hands rounds down in, and what the providers read.
   */
  private static List<String> variables(
      final String... providerNames) {

    final var expected = new TreeSet<>(List.of(providerNames));
    expected.add(AGGREGATE_ID_NAME);
    expected.add(Camunda8MultiInstance.CHAIN_VARIABLE);
    return List.copyOf(expected);

  }

}
