package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the bridge answers without asking the cluster at all.
 * <p>
 * Everything else it does is a request to a cluster and is tested against a real one; what stays
 * here is the handful of answers a cluster would only be able to confirm - and the one which
 * must never reach it.
 * <p>
 * The filter a search is narrowed with is asserted here as well, although a cluster runs the
 * search. A condition spelled differently does not fail against a cluster: the search answers
 * nothing, and nothing reads exactly like a workflow which was never started.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitBridgeTest {

  private static final String MODULE_ID = "cockpit-module";

  private static final String PROCESS_ID = "CockpitProcess";

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskWiring workflowTaskWiring = mock(WorkflowTaskWiring.class);

  /**
   * How the cluster of this test spells what the application wrote: the workflow module runs
   * under <code>use-prefix</code> unless a test says otherwise, so the process id the filter
   * carries is not the one the caller asked about.
   */
  private final NameClashAvoidanceSupport scoping = mock(NameClashAvoidanceSupport.class);

  private static final String SCOPED_PROCESS_ID = "cockpit-module-CockpitProcess";

  private static final String AGGREGATE_ID = "4711";

  private static final String AGGREGATE_ID_NAME = "loanId";

  private Camunda8CockpitBridge bridge() {

    final var clients = new Camunda8Clients(clientFactories, null);
    return new Camunda8CockpitBridge(clients.of("c8"), workflowTaskWiring);

  }

  /**
   * A bridge of a cluster which prefixes what it is told, answering the search with nothing.
   *
   * @return The bridge
   */
  private Camunda8CockpitBridge bridgeOfAScopedModule() {

    when(scoping.scopedProcessId(MODULE_ID, PROCESS_ID, "c8")).thenReturn(SCOPED_PROCESS_ID);
    when(workflowTaskWiring.resolveWorkflowAggregateIdName(MODULE_ID, PROCESS_ID))
        .thenReturn(AGGREGATE_ID_NAME);
    final var search = clientFactories
        .getFactory("c8")
        .getClient()
        .newProcessInstanceSearchRequest();
    when(search.filter(any(Consumer.class)).send().join().items()).thenReturn(List.of());
    // the stubbing above called 'filter' itself. What the test asserts is the one call the
    // bridge makes, because a second call would replace the first rather than add to it
    clearInvocations(search);
    final var clients = new Camunda8Clients(clientFactories, scoping);
    return new Camunda8CockpitBridge(clients.of("c8"), workflowTaskWiring);

  }

  /**
   * @return What the search request was narrowed with, run against a filter which records it
   */
  private ProcessInstanceFilter theFilterOfTheSearch() {

    final ArgumentCaptor<Consumer<ProcessInstanceFilter>> narrowing = ArgumentCaptor.captor();
    verify(
        clientFactories.getFactory("c8").getClient().newProcessInstanceSearchRequest())
        .filter(narrowing.capture());
    final var filter = mock(ProcessInstanceFilter.class);
    narrowing.getValue().accept(filter);
    return filter;

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
  @DisplayName("A search for the workflows of an aggregate names the process as the cluster knows it")
  public void theSearchIsScopedTheWayTheClusterStoresIt() {

    bridgeOfAScopedModule().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    final var filter = theFilterOfTheSearch();
    verify(filter).processDefinitionId(SCOPED_PROCESS_ID);
    // quoted, because the cluster stores every variable as JSON and compares against that JSON
    // verbatim - the plain 4711 would match nothing
    verify(filter).variables(Map.of(AGGREGATE_ID_NAME, "\"4711\""));
    // 'use-prefix' puts no workflow module into a tenant, and a tenant nobody uses would
    // narrow the search to workflows which do not exist
    verify(filter, never()).tenantId(anyString());

  }

  @Test
  @DisplayName("A module separated by tenants searches inside its tenant")
  public void theSearchOfATenantCarriesIt() {

    when(scoping.modeFor(MODULE_ID, null, "c8")).thenReturn(NameClashAvoidance.BY_ADAPTER);

    bridgeOfAScopedModule().workflowsOfAggregate(MODULE_ID, PROCESS_ID, AGGREGATE_ID);

    verify(theFilterOfTheSearch()).tenantId(MODULE_ID);

  }

}
