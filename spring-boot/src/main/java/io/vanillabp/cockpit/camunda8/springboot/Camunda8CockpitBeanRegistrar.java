package io.vanillabp.cockpit.camunda8.springboot;

import java.util.TreeSet;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * Registers one Business Cockpit bridge per configured Camunda 8 adapter id.
 * <p>
 * The cockpit addresses a workflow by the BPMS holding it, and during a migration that is a
 * different BPMS per workflow, so two configured Camunda 8 clusters are two bridges and the
 * platform-neutral half picks the one an event's adapter id names. How many there are is
 * decided by the configuration, which is why the beans are registered programmatically; they
 * are element beans and never a bean of type <code>List</code>, because that is how the
 * cockpit's neutral half collects them on Spring Boot.
 */
public class Camunda8CockpitBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    camunda8AdapterIds(environment)
        .forEach(
            adapterId -> registry
                .registerBean(
                    "BusinessCockpit_Camunda8_Bridge_%s".formatted(adapterId),
                    BusinessCockpitBpmsBridge.class,
                    spec -> spec
                        .supplier(
                            supplierContext -> new Camunda8CockpitBridge(
                                supplierContext
                                    .bean(Camunda8Clients.class)
                                    .of(adapterId), supplierContext.bean(WorkflowTaskWiring.class)))));

  }

  /**
   * The adapter ids always come from the platform's own configuration rather than from the
   * Camunda 8 adapter's overlay map, the same rule the adapter itself follows: an environment
   * variable can materialize an overlay entry for an adapter nobody configured.
   */
  private static Iterable<String> camunda8AdapterIds(
      final Environment environment) {

    final var properties = Binder
        .get(environment)
        .bind(MigrationAdapterProperties.PREFIX, Bindable.of(MigrationAdapterProperties.class))
        .orElseGet(MigrationAdapterProperties::new);

    final var adapterIds = new TreeSet<String>();
    properties
        .adapterTypes()
        .forEach((
            adapterId,
            adapterType) -> {
          if (Camunda8DeploymentService.ADAPTER_TYPE.equals(adapterType)) {
            adapterIds.add(adapterId);
          }
        });
    return adapterIds;

  }

}
