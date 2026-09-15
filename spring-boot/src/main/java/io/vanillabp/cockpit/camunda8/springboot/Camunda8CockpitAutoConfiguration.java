package io.vanillabp.cockpit.camunda8.springboot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.springboot.client.VanillaBpCamunda8Properties;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitSettings;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitWiring;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitWorkers;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * Registers the Camunda 8 half of the Business Cockpit extension on Spring Boot.
 * <p>
 * Nothing here decides anything. What the extension does with a cluster is decided in the
 * platform-neutral module of this repository. This class does what only Spring can do: find the
 * beans, and put the extension's own where VanillaBP and the Camunda 8 adapter collect them.
 * <p>
 * It runs after VanillaBP's own auto-configuration, named rather than referenced, because an
 * extension does not compile against a platform integration.
 */
@AutoConfiguration(afterName = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration")
@ConditionalOnBean(MigrationAdapterProperties.class)
@EnableConfigurationProperties(VanillaBpCamunda8Properties.class)
@Import(Camunda8CockpitBeanRegistrar.class)
public class Camunda8CockpitAutoConfiguration {

  /**
   * @param clientFactories The clients the Camunda 8 adapter built, one per configured adapter
   *          id
   * @param scoping VanillaBP's name-clash avoidance
   * @return The clusters this extension watches
   */
  @Bean
  public Camunda8Clients businessCockpitCamunda8Clients(
      final Camunda8ClientFactoryRegistry clientFactories,
      final NameClashAvoidanceSupport scoping) {

    return new Camunda8Clients(clientFactories, scoping);

  }

  /**
   * @return Where the listeners this extension added are remembered, shared by the wiring
   *         service filling it and by every job which arrives later
   */
  @Bean
  public Camunda8CockpitDeployments businessCockpitCamunda8Deployments() {

    return new Camunda8CockpitDeployments();

  }

  /**
   * @param properties The Camunda 8 adapter's own configuration
   * @return How long a listener job of this extension stays locked
   */
  @Bean
  public Camunda8CockpitSettings businessCockpitCamunda8Settings(
      final VanillaBpCamunda8Properties properties) {

    return new Camunda8SpringSettings(properties);

  }

  /**
   * @param clients The clusters
   * @param deployments What was wired
   * @param settings The lock of a listener job
   * @param metrics Where the job counters of these workers go. The adapter registers this bean
   *          where the application brought Micrometer, and an application without it counts
   *          nothing
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: the workers are opened while the application is still starting
   * @return The workers serving this extension's listeners
   */
  @Bean
  public Camunda8CockpitWorkers businessCockpitCamunda8Workers(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitSettings settings,
      final ObjectProvider<Camunda8Metrics> metrics,
      final ObjectProvider<BusinessCockpitEventPublisher> publisher) {

    return new Camunda8CockpitWorkers(
        clients, deployments, settings, metrics.getIfAvailable(() -> Camunda8Metrics.NONE), publisher::getObject);

  }

  /**
   * @param clients The clusters
   * @param deployments Where the listeners are remembered
   * @param workers The workers serving them
   * @param workflowTaskWiring VanillaBP's registry, which names the workflow aggregate's id
   *          variable of a BPMN process
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on Camunda 8 and for no other
   */
  @Bean
  public ExtensionWiringService<BpmnModelInstance, Camunda8ProcessingContext> businessCockpitCamunda8WiringService(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitWorkers workers,
      final WorkflowTaskWiring workflowTaskWiring) {

    return new Camunda8CockpitWiring(clients, deployments, workers, workflowTaskWiring);

  }

}
