package io.vanillabp.cockpit.camunda8.quarkus;

import java.util.List;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.quarkus.arc.Unremovable;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.quarkus.runtime.VanillaBpCamunda8Properties;
import io.vanillabp.cockpit.camunda8.Camunda8Clients;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitBridge;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitSettings;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitWiring;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitWorkers;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Registers the Camunda 8 half of the Business Cockpit extension on Quarkus. It is the twin of
 * the Spring Boot module's auto-configuration and does the same things with CDI.
 * <p>
 * The producers are <code>&#64;Singleton</code> rather than
 * <code>&#64;ApplicationScoped</code>, because what they produce has no no-argument constructor
 * and is therefore not client-proxyable.
 */
@ApplicationScoped
public class Camunda8CockpitProducer {

  /**
   * @param clientFactories The clients the Camunda 8 adapter built, one per configured adapter
   *          id
   * @param scoping VanillaBP's name-clash avoidance
   * @return The clusters this extension watches
   */
  @Produces
  @Singleton
  @Unremovable
  public Camunda8Clients businessCockpitCamunda8Clients(
      final Camunda8ClientFactoryRegistry clientFactories,
      final NameClashAvoidanceSupport scoping) {

    return new Camunda8Clients(clientFactories, scoping);

  }

  /**
   * @return Where the listeners this extension added are remembered, shared by the wiring
   *         service filling it and by every job which arrives later
   */
  @Produces
  @Singleton
  @Unremovable
  public Camunda8CockpitDeployments businessCockpitCamunda8Deployments() {

    return new Camunda8CockpitDeployments();

  }

  /**
   * @param properties The Camunda 8 adapter's own configuration
   * @return How long a listener job of this extension stays locked
   */
  @Produces
  @Singleton
  @Unremovable
  public Camunda8CockpitSettings businessCockpitCamunda8Settings(
      final VanillaBpCamunda8Properties properties) {

    return new Camunda8QuarkusSettings(properties);

  }

  /**
   * @param clients The clusters
   * @param deployments What was wired
   * @param settings The lock of a listener job
   * @param metrics Where the job counters of these workers go. The adapter produces this bean
   *          where the application brought the Micrometer extension, and an application without
   *          it counts nothing
   * @param publisher Where an observed event is reported, resolved on the first event rather
   *          than now: the workers are opened while the application is still starting
   * @return The workers serving this extension's listeners
   */
  @Produces
  @Singleton
  @Unremovable
  public Camunda8CockpitWorkers businessCockpitCamunda8Workers(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitSettings settings,
      final Instance<Camunda8Metrics> metrics,
      final Instance<BusinessCockpitEventPublisher> publisher) {

    return new Camunda8CockpitWorkers(
        clients, deployments, settings, metrics.isResolvable()
            ? metrics.get()
            : Camunda8Metrics.NONE, publisher::get);

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
  @Produces
  @Singleton
  @Unremovable
  public ExtensionWiringService<BpmnModelInstance, Camunda8ProcessingContext> businessCockpitCamunda8WiringService(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitWorkers workers,
      final WorkflowTaskWiring workflowTaskWiring) {

    return new Camunda8CockpitWiring(clients, deployments, workers, workflowTaskWiring);

  }

  /**
   * One bridge per configured Camunda 8 adapter id, because during a migration each cluster
   * holds workflows of its own and the cockpit addresses a workflow by the cluster holding it.
   * <p>
   * They are produced as one list rather than as one bean each: how many there are is decided
   * by the configuration, which a producer method cannot express. The cockpit's neutral half
   * collects both shapes.
   * <p>
   * WHICH adapter ids those are is {@code MigrationAdapterProperties#adapterIdsOfType}, the same
   * answer the Spring Boot half reads through the platform's registrar support. Filtering the
   * configured types is not that answer. An id named in <code>prioritized-adapters</code> needs
   * no section of its own, and an application which configured nothing at all has the id the
   * classpath derives. Those two are exactly the cases where an extension which answers the
   * question itself registers no bridge while the adapter registers fine.
   *
   * @param clients The clusters
   * @param properties VanillaBP's resolved configuration, which names the configured adapters
   * @param workflowTaskWiring VanillaBP's registry
   * @return One bridge per configured Camunda 8 adapter id
   */
  @Produces
  @Singleton
  @Unremovable
  public List<BusinessCockpitBpmsBridge> businessCockpitCamunda8Bridges(
      final Camunda8Clients clients,
      final MigrationAdapterProperties properties,
      final WorkflowTaskWiring workflowTaskWiring) {

    return properties
        .adapterIdsOfType(Camunda8DeploymentService.ADAPTER_TYPE)
        .stream()
        .<BusinessCockpitBpmsBridge>map(
            adapterId -> new Camunda8CockpitBridge(clients.of(adapterId), workflowTaskWiring))
        .toList();

  }

}
