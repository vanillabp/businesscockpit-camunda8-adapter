package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;

import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.camunda.client.api.worker.JobWorkerMetrics;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a worker this extension opens carries, and what it deliberately does not.
 * <p>
 * It carries two things the client the adapter built cannot pass on: the stream timeout, which
 * the client has no equivalent for, and the job counters, which exist per worker because they
 * carry the job type. An operator therefore reads these workers next to the adapter's own
 * instead of finding a gap where they should be.
 * <p>
 * It carries nothing else. Whether jobs are streamed, how often a worker polls and how long a
 * request may take are set on the client, where an environment variable can still overrule them,
 * and a worker naming any of them again would beat that environment variable without saying so -
 * which nothing in a running application would show. So those four setters are asserted to stay
 * untouched.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitWorkerOptionsTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String LISTENER_TYPE = Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID);

  private final Camunda8AdapterConfiguration configuration = new Camunda8AdapterConfiguration();

  private final JobWorkerBuilderStep3 builder = mock(
      JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final Camunda8CockpitDeployments deployments = new Camunda8CockpitDeployments();

  /** Which worker the adapter's counters were asked for, as "adapter id / job type". */
  private final List<String> countersAskedFor = new LinkedList<>();

  private final JobWorkerMetrics countersOfThisWorker = mock(JobWorkerMetrics.class);

  private final Camunda8Metrics metrics = new Camunda8Metrics() {

    @Override
    public JobWorkerMetrics workerMetrics(
        final String adapterId,
        final String jobType) {

      countersAskedFor.add("%s / %s".formatted(adapterId, jobType));
      return countersOfThisWorker;

    }

  };

  private Camunda8CockpitWorkers workers;

  @BeforeEach
  public void aWiredWorkflowModule() {

    // what the adapter is configured with: the three values below belong to its client, the
    // stream timeout of a single test belongs to the worker
    configuration.setStreamEnabled(Boolean.TRUE);
    configuration.setPollInterval(Duration.ofMillis(250));
    configuration.setRequestTimeout(Duration.ofSeconds(20));

    final var factory = clientFactories.getFactory(ADAPTER_ID);
    when(factory.getConfiguration()).thenReturn(configuration);
    when(factory.getClient().newWorker().jobType(LISTENER_TYPE).handler(any())).thenReturn(builder);
    when(builder.open()).thenReturn(mock(JobWorker.class));

    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(LISTENER_TYPE, PROCESS_ID, PROCESS_ID, PROCESS_ID, null, null, "loanId"));

    workers = new Camunda8CockpitWorkers(
        new Camunda8Clients(clientFactories, null), deployments, (
            workflowModuleId,
            adapterId) -> Duration.ofMinutes(5), metrics, () -> null);

  }

  @Test
  @DisplayName("A worker gets the configured stream-timeout and the counters of its adapter id")
  public void theWorkerCarriesWhatTheClientCannotPassOn() {

    configuration.setStreamTimeout(Duration.ofMinutes(30));

    workers.open(ADAPTER_ID, MODULE_ID);

    verify(builder).streamTimeout(Duration.ofMinutes(30));
    verify(builder).metrics(countersOfThisWorker);
    assertEquals(List.of("c8 / %s".formatted(LISTENER_TYPE)), countersAskedFor);
    whatBelongsToTheClientStayedThere();

  }

  @Test
  @DisplayName("Without a configured stream-timeout the worker is still counted")
  public void withoutAStreamTimeoutOnlyTheCountersArrive() {

    workers.open(ADAPTER_ID, MODULE_ID);

    verify(builder, never()).streamTimeout(any());
    verify(builder).metrics(countersOfThisWorker);
    assertEquals(List.of("c8 / %s".formatted(LISTENER_TYPE)), countersAskedFor);
    whatBelongsToTheClientStayedThere();

  }

  /**
   * The four settings every worker inherits from the client the adapter built. A worker of this
   * extension must not name any of them, or the environment variables which may overrule the
   * client lose against a value nobody sees.
   */
  private void whatBelongsToTheClientStayedThere() {

    verify(builder, never()).streamEnabled(anyBoolean());
    verify(builder, never()).pollInterval(any());
    verify(builder, never()).requestTimeout(any());
    verify(builder, never()).maxJobsActive(anyInt());

  }

}
