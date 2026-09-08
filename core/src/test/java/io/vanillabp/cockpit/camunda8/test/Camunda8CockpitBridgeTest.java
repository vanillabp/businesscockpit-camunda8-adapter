package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the bridge answers without asking the cluster at all.
 * <p>
 * Everything else it does is a request to a cluster and is tested against a real one; what stays
 * here is the handful of answers a cluster would only be able to confirm - and the one which
 * must never reach it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitBridgeTest {

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskWiring workflowTaskWiring = mock(WorkflowTaskWiring.class);

  private Camunda8CockpitBridge bridge() {

    final var clients = new Camunda8Clients(
        clientFactories, null, Map.of("c8", "camunda8", "c7", "camunda7"));
    return new Camunda8CockpitBridge(clients.of("c8"), workflowTaskWiring);

  }

  @Test
  @DisplayName("The bridge says which configured adapter it serves and of which BPMS")
  public void theBridgeNamesItsAdapter() {

    assertEquals("c8", bridge().adapterId());
    assertEquals("camunda8", bridge().adapterType());

  }

  @Test
  @DisplayName("A user-task id no Camunda 8 cluster ever handed out is answered without asking one")
  public void anIdOfAnotherBpmsIsNotLookedUp() {

    // during a migration an application still holds ids the other BPMS gave it, and none of
    // them is a task of this cluster - asking would spend a request to be told so
    final var found = bridge()
        .userTaskOfAggregate(MODULE_ID, PROCESS_ID, "4711", "a-camunda-7-task-id");

    assertTrue(found.isEmpty());
    verifyNoInteractions(workflowTaskWiring);

  }

  @Test
  @DisplayName("Only the adapters of this BPMS get a bridge")
  public void onlyCamunda8AdapterIdsAreServed() {

    assertEquals(
        List.of("c8"),
        new Camunda8Clients(clientFactories, null, Map.of("c8", "camunda8", "c7", "camunda7"))
            .adapterIds());

  }

}
