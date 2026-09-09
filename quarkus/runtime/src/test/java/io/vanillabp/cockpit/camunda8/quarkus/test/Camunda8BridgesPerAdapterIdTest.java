package io.vanillabp.cockpit.camunda8.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.quarkus.Camunda8CockpitProducer;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which Camunda 8 adapter ids get a bridge.
 * <p>
 * The answer is VanillaBP's ({@code MigrationAdapterProperties#adapterIdsOfType}), and the two
 * cases below are the ones a hand-rolled filter over the configured adapter TYPES misses: an id
 * which is only named in <code>prioritized-adapters</code>, which is what a migration writes for
 * the engine it is moving away from, and an application which configured nothing at all, whose
 * single adapter dependency IS the configuration. In both the adapter builds its client and this
 * half used to build no bridge, so every event of that cluster was reported to nobody.
 * <p>
 * The Spring Boot half asks the same thing through the platform's registrar support, which needs
 * a bound environment; this is the shape which can be asked without booting anything.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8BridgesPerAdapterIdTest {

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskWiring workflowTaskWiring = mock(WorkflowTaskWiring.class);

  private List<String> bridgedAdapterIds(
      final MigrationAdapterProperties properties) {

    return new Camunda8CockpitProducer()
        .businessCockpitCamunda8Bridges(
            new Camunda8Clients(clientFactories, null), properties, workflowTaskWiring)
        .stream()
        .map(bridge -> bridge.adapterId())
        .toList();

  }

  @Test
  @DisplayName("An adapter id which only stands in prioritized-adapters gets a bridge")
  public void aPrioritizedAdapterIdIsBridged() {

    final var configured = new AdapterConfigProperties();
    configured.setType(Camunda8DeploymentService.ADAPTER_TYPE);
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c8-new", configured));
    properties
        .setPrioritizedAdapters(
            List.of("c8-new", Camunda8DeploymentService.ADAPTER_TYPE));

    // sorted, so a restart registers the same beans under the same names again
    assertEquals(
        List.of("c8-new", Camunda8DeploymentService.ADAPTER_TYPE), bridgedAdapterIds(properties));

  }

  @Test
  @DisplayName("An application which configured nothing gets the bridge of the derived adapter id")
  public void theDerivedAdapterIdIsBridged() {

    assertEquals(
        List.of(Camunda8DeploymentService.ADAPTER_TYPE),
        bridgedAdapterIds(new MigrationAdapterProperties()));

  }

  @Test
  @DisplayName("An adapter of another BPMS gets no bridge from this half")
  public void anotherBpmsIsNotBridged() {

    final var camunda7 = new AdapterConfigProperties();
    camunda7.setType("camunda7");
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c7", camunda7));

    assertEquals(List.of(), bridgedAdapterIds(properties));

  }

}
