package io.vanillabp.cockpit.camunda8.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * Registers one Business Cockpit bridge per configured Camunda 8 adapter id.
 * <p>
 * The cockpit addresses a workflow by the BPMS holding it, and during a migration that is a
 * different BPMS per workflow. So two configured Camunda 8 clusters are two bridges, and the
 * platform-neutral half picks the one an event's adapter id names. How many there are is decided
 * by the configuration, which is why the beans are registered programmatically. They are element
 * beans and never a bean of type <code>List</code>, because that is how the cockpit's neutral
 * half collects them on Spring Boot.
 * <p>
 * WHICH adapter ids those are is the platform's answer
 * ({@code AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}), the same one the Camunda 8
 * adapter registers its own beans for. Filtering the configured types is not that answer. An id
 * named in <code>prioritized-adapters</code> needs no section of its own, and an application
 * which configured nothing at all has the id the classpath derives. Those two are exactly the
 * cases where an extension which answers the question itself registers no bridge while the
 * adapter registers fine.
 */
public class Camunda8CockpitBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    AdapterBeanRegistrarSupport
        .forEachConfiguredAdapterId(
            environment,
            Camunda8DeploymentService.ADAPTER_TYPE,
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

}
