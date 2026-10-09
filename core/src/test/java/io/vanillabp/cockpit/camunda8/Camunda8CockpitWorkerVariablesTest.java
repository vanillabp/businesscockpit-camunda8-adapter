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
import io.camunda.zeebe.model.bpmn.Bpmn;
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
 * Which variables a worker of this extension asks the cluster for.
 * <p>
 * A worker names its variables, and a variable it does not name never reaches its jobs. So the
 * list decides what a details provider can read with <code>&#64;TaskParam</code>. It must hold
 * what the providers of the served tasks read, and it must hold nothing a provider of another
 * task reads.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitWorkerVariablesTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String AGGREGATE_ID_NAME = "loanId";

  /** The task whose provider reads a variable. It runs once per signer. */
  private static final String TASK_DEFINITION = "approve";

  private static final String BPMN_TASK_ID = "Approve";

  /** Another task, whose provider reads a variable of its own. */
  private static final String OTHER_TASK_DEFINITION = "archive";

  private static final String OTHER_BPMN_TASK_ID = "Archive";

  private static final String WORKFLOW_LISTENER_TYPE = Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID);

  private static final String USER_TASK_LISTENER_TYPE = Camunda8CockpitListeners.listenerTypeOf(TASK_DEFINITION);

  private static final String OTHER_USER_TASK_LISTENER_TYPE = Camunda8CockpitListeners
      .listenerTypeOf(OTHER_TASK_DEFINITION);

  private final Camunda8AdapterConfiguration configuration = new Camunda8AdapterConfiguration();

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final JobWorkerBuilderStep3 workflowWorker = mock(JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final JobWorkerBuilderStep3 userTaskWorker = mock(JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final JobWorkerBuilderStep3 otherUserTaskWorker = mock(
      JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  /** Which tasks the workers asked the application about. */
  private final List<String> askedAbout = new LinkedList<>();

  /**
   * The application's answer: the provider of {@link #TASK_DEFINITION} reads the customer, the
   * provider of {@link #OTHER_TASK_DEFINITION} reads the archive box.
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

      askedAbout.add(workflowModuleId
          + "/"
          + bpmnProcessId
          + "/"
          + taskDefinition
          + "/"
          + bpmnTaskId);
      return switch (taskDefinition) {
        case TASK_DEFINITION -> List.of("customer");
        case OTHER_TASK_DEFINITION -> List.of("archiveBox");
        default -> List.of();
      };

    }

  };

  /** The multi-instance elements of the model, as the adapter collects them. */
  private final Camunda8MultiInstance.Registry multiInstances = Camunda8MultiInstance
      .chainsOf(
          Bpmn
              .createExecutableProcess(PROCESS_ID)
              .startEvent("Start")
              .userTask(BPMN_TASK_ID)
              .multiInstance(
                  signers -> signers.parallel().zeebeInputCollectionExpression("signers").zeebeInputElement("signer"))
              .userTask(OTHER_BPMN_TASK_ID)
              .endEvent("End")
              .done(),
          PROCESS_ID);

  private Camunda8CockpitWorkers workers;

  @BeforeEach
  public void aWiredWorkflowModule() {

    final var factory = clientFactories.getFactory(ADAPTER_ID);
    when(factory.getConfiguration()).thenReturn(configuration);
    when(factory.getClient().newWorker().jobType(WORKFLOW_LISTENER_TYPE).handler(any())).thenReturn(workflowWorker);
    when(factory.getClient().newWorker().jobType(USER_TASK_LISTENER_TYPE).handler(any())).thenReturn(userTaskWorker);
    when(factory.getClient().newWorker().jobType(OTHER_USER_TASK_LISTENER_TYPE).handler(any()))
        .thenReturn(otherUserTaskWorker);
    when(workflowWorker.open()).thenReturn(mock(JobWorker.class));
    when(userTaskWorker.open()).thenReturn(mock(JobWorker.class));
    when(otherUserTaskWorker.open()).thenReturn(mock(JobWorker.class));

    deployments
        .register(
            ADAPTER_ID, MODULE_ID,
            new WiredListener(WORKFLOW_LISTENER_TYPE, PROCESS_ID, PROCESS_ID, PROCESS_ID, null, null, AGGREGATE_ID_NAME));
    deployments
        .register(
            ADAPTER_ID, MODULE_ID,
            new WiredListener(
                USER_TASK_LISTENER_TYPE, PROCESS_ID, PROCESS_ID, BPMN_TASK_ID, null, null, AGGREGATE_ID_NAME, TASK_DEFINITION));
    deployments
        .register(
            ADAPTER_ID, MODULE_ID,
            new WiredListener(
                OTHER_USER_TASK_LISTENER_TYPE, PROCESS_ID, PROCESS_ID, OTHER_BPMN_TASK_ID, null, null, AGGREGATE_ID_NAME, OTHER_TASK_DEFINITION));

    workers = new Camunda8CockpitWorkers(
        new Camunda8Clients(clientFactories, null), deployments, (
            workflowModuleId,
            adapterId) -> Duration.ofMinutes(5), Camunda8Metrics.NONE, () -> application);

  }

  @Test
  @DisplayName("A user task's worker asks for what its provider reads and for the rounds of its task")
  public void aUserTasksWorkerAsksForWhatItsProviderReads() {

    workers.open(ADAPTER_ID, MODULE_ID, multiInstances);

    final var expected = new TreeSet<String>();
    expected.add(AGGREGATE_ID_NAME);
    // what the details provider of this task reads with @TaskParam
    expected.add("customer");
    // the rounds of the task, in the variables the adapter puts into the model for its own
    // workers
    expected.add(Camunda8MultiInstance.CHAIN_VARIABLE);
    final var rounds = multiInstances.chainOf(PROCESS_ID, BPMN_TASK_ID);
    assertEquals(1, rounds.size(), "the model has one multi-instance element around the task");
    expected.add(rounds.getFirst().indexVariable());
    expected.add(rounds.getFirst().totalVariable());
    expected.add(rounds.getFirst().elementVariable());

    verify(userTaskWorker).fetchVariables(List.copyOf(expected));

  }

  @Test
  @DisplayName("A worker never asks for what only the provider of another task reads")
  public void aWorkerDoesNotAskForWhatAnotherProviderReads() {

    workers.open(ADAPTER_ID, MODULE_ID, multiInstances);

    // the other task runs once, so its worker asks for its own provider's variable and for the
    // name a caller hands rounds down in, and for nothing of the first task
    verify(otherUserTaskWorker)
        .fetchVariables(List.of("archiveBox", AGGREGATE_ID_NAME, Camunda8MultiInstance.CHAIN_VARIABLE));

  }

  @Test
  @DisplayName("A workflow's worker asks for the aggregate's id alone, and the application is not asked")
  public void aWorkflowsWorkerAsksForTheAggregateIdAlone() {

    workers.open(ADAPTER_ID, MODULE_ID, multiInstances);

    // a workflow details provider takes no @TaskParam, so there is nothing else to ask for
    verify(workflowWorker).fetchVariables(List.of(AGGREGATE_ID_NAME));
    assertEquals(
        List
            .of(
                MODULE_ID
                    + "/"
                    + PROCESS_ID
                    + "/"
                    + TASK_DEFINITION
                    + "/"
                    + BPMN_TASK_ID,
                MODULE_ID
                    + "/"
                    + PROCESS_ID
                    + "/"
                    + OTHER_TASK_DEFINITION
                    + "/"
                    + OTHER_BPMN_TASK_ID),
        askedAbout);

  }

}
