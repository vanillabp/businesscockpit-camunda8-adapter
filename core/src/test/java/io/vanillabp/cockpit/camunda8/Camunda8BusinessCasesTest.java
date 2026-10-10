package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.ClientHttpException;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.cockpit.camunda8.test.CallChains;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which instance of a call hierarchy is the business case. The answer whether two processes share
 * the workflow aggregate is given by the test, the way the core gives it in the application.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8BusinessCasesTest {

  private static final String ADAPTER_ID = "c8";

  private static final String ORDER = "Order";

  private static final String SHIPPING = "Shipping";

  private static final String PACKING = "Packing";

  private static final String FOREIGN = "SomebodyElses";

  /** The processes this extension wired, by the id the cluster knows them. */
  private static final Map<String, String> WIRED = Map.of(ORDER, ORDER, SHIPPING, SHIPPING, PACKING, PACKING);

  /**
   * Order works on its own aggregate. Shipping works on another one, and Packing is a step of
   * Shipping.
   */
  private static final Set<String> SHIPPING_AGGREGATE = Set.of(SHIPPING, PACKING);

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private Camunda8BusinessCases cases;

  @BeforeEach
  public void aCluster() {

    when(clientFactories.getFactory(ADAPTER_ID).getConfiguration().workflowVisibilityWindow())
        .thenReturn(Duration.ZERO);
    cases = new Camunda8BusinessCases(new Camunda8Clients(clientFactories, null).of(ADAPTER_ID));

  }

  private static boolean share(
      final String calling,
      final String called) {

    return calling.equals(called) || (SHIPPING_AGGREGATE.contains(calling) && SHIPPING_AGGREGATE.contains(called));

  }

  @Test
  @DisplayName("An instance nobody called is its own case, and the cluster is not asked")
  public void anInstanceNobodyCalledIsItsOwnCase() {

    assertEquals(1L, cases.caseOf(1L, PACKING, null, false, WIRED, Camunda8BusinessCasesTest::share));
    verify(clientFactories.getFactory(ADAPTER_ID).getClient(), never())
        .newProcessInstanceGetCallHierarchyRequest(any());

  }

  @Test
  @DisplayName("A called process sharing its aggregate with no other process is its own case, without a request")
  public void aCalledProcessWithItsOwnAggregateIsItsOwnCase() {

    assertEquals(2L, cases.caseOf(2L, ORDER, 1L, true, WIRED, Camunda8BusinessCasesTest::share));
    verify(clientFactories.getFactory(ADAPTER_ID).getClient(), never())
        .newProcessInstanceGetCallHierarchyRequest(any());

  }

  @Test
  @DisplayName("The case is the highest caller reached by calls which share the aggregate")
  public void theWalkStopsAtTheFirstCallerWithAnotherAggregate() {

    // Order calls Shipping, which calls Packing
    final var chain = new LinkedHashMap<Long, String>();
    chain.put(1L, ORDER);
    chain.put(2L, SHIPPING);
    chain.put(3L, PACKING);
    CallChains.chainIs(clientFactories, ADAPTER_ID, chain);

    assertEquals(2L, cases.caseOf(3L, PACKING, 1L, true, WIRED, Camunda8BusinessCasesTest::share));

  }

  @Test
  @DisplayName("A call from a process this module did not wire starts a case of its own")
  public void aCallFromAForeignProcessStartsACase() {

    CallChains.calls(clientFactories, ADAPTER_ID, 1L, FOREIGN, 2L, PACKING);

    assertEquals(2L, cases.caseOf(2L, PACKING, 1L, true, WIRED, Camunda8BusinessCasesTest::share));

  }

  @Test
  @DisplayName("Where the storage does not hold the hierarchy in time, the root is taken")
  public void aStorageBehindTheEngineGivesTheRoot() {

    when(
        clientFactories
            .getFactory(ADAPTER_ID)
            .getClient()
            .newProcessInstanceGetCallHierarchyRequest(3L)
            .send()
            .join())
        .thenThrow(new ClientHttpException(404, "Not Found"));

    assertEquals(1L, cases.caseOf(3L, PACKING, 1L, true, WIRED, Camunda8BusinessCasesTest::share));
    // and on 8.8, where nobody knows the root, the instance is its own case
    assertEquals(3L, cases.caseOf(3L, PACKING, null, true, WIRED, Camunda8BusinessCasesTest::share));

  }

}
