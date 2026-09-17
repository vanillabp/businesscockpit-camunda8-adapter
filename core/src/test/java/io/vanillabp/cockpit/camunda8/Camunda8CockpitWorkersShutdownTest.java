package io.vanillabp.cockpit.camunda8;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What closes the workers of this extension when nothing else does.
 * <p>
 * The ordinary way they stop is the deployment pipeline stopping workflow processing of their
 * workflow module. A shutdown path which never gets that far used to leave them open, and the
 * adapter then closed its client under them: the listener jobs they were serving are cut off and
 * the activation requests they parked at the cluster stay parked, which costs the next instance
 * of the application a whole job timeout before it sees the first job.
 * <p>
 * So the adapter's client factory is told these workers are open, and it closes what is still
 * open before it closes the client. The factory here is the real one, because that promise is
 * what the test is about; only the client it would build is mocked away.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitWorkersShutdownTest {

  private static final String ADAPTER_ID = "c8";

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private static final String LISTENER_TYPE = Camunda8CockpitListeners.listenerTypeOf(PROCESS_ID);

  private final JobWorker openedWorker = mock(JobWorker.class);

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class);

  private Camunda8ClientFactory adapterOfThisCluster;

  private Camunda8CockpitWorkers workers;

  @BeforeEach
  public void aWiredWorkflowModule() {

    // unconfigured on purpose: an adapter with no connection properties builds no client at
    // all, so closing this factory closes nothing but what was registered with it
    adapterOfThisCluster = spy(
        new Camunda8ClientFactory(ADAPTER_ID, new Camunda8AdapterConfiguration()));
    final var clusterClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);
    doReturn(clusterClient).when(adapterOfThisCluster).getClient();
    final var builder = mock(JobWorkerBuilderStep3.class, Answers.RETURNS_SELF);
    when(clusterClient.newWorker().jobType(LISTENER_TYPE).handler(any())).thenReturn(builder);
    when(builder.open()).thenReturn(openedWorker);
    when(clientFactories.getFactory(ADAPTER_ID)).thenReturn(adapterOfThisCluster);

    final var deployments = new Camunda8CockpitDeployments();
    deployments
        .register(
            ADAPTER_ID,
            MODULE_ID,
            new WiredListener(LISTENER_TYPE, PROCESS_ID, PROCESS_ID, PROCESS_ID, null, null, "loanId"));
    workers = new Camunda8CockpitWorkers(
        new Camunda8Clients(clientFactories, null), deployments, (
            workflowModuleId,
            adapterId) -> Duration.ofMinutes(5), Camunda8Metrics.NONE, () -> null);

  }

  @Test
  @DisplayName("The client going down closes the workers this extension left open")
  public void theClientGoingDownClosesTheWorkers() {

    workers.open(ADAPTER_ID, MODULE_ID);

    adapterOfThisCluster.close();

    verify(openedWorker).close();

  }

  @Test
  @DisplayName("Workers which stopped the ordinary way are not closed a second time")
  public void aStoppedModuleLeavesNoHookBehind() {

    workers.open(ADAPTER_ID, MODULE_ID);
    workers.close(ADAPTER_ID, MODULE_ID);

    adapterOfThisCluster.close();

    verify(openedWorker, times(1)).close();

  }

}
