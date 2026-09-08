package io.vanillabp.cockpit.camunda8;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * The Camunda 8 clusters this application talks to, as far as the Business Cockpit needs them.
 * <p>
 * The clients belong to the VanillaBP Camunda 8 adapter and are taken from it, never built
 * here: it owns their authentication, their executors and their shutdown, and a second client
 * beside them would be a second connection nobody configured.
 * <p>
 * <b>Which cluster holds which workflow module</b> is asked of the adapter as well. VanillaBP's
 * deployment pipeline tells an extension that a workflow module is starting, but not which of
 * the configured adapters it is starting for - so the answer comes from the adapters
 * themselves, each of which knows the workflow modules it opened. See decision 2 in the
 * repository's DECISIONS.md.
 */
public class Camunda8Clients {

  /**
   * One configured Camunda 8 adapter, with the cluster behind it.
   */
  public class Cluster {

    private final Camunda8Scope scope;

    private Cluster(
        final Camunda8Scope scope) {

      this.scope = scope;

    }

    /**
     * @return How this cluster names the things the extension asks it about
     */
    public Camunda8Scope scope() {

      return scope;

    }

    /**
     * @return The client of this adapter, which the adapter hands out by identity
     */
    public CamundaClient client() {

      return clientFactories.getFactory(scope.adapterId()).getClient();

    }

    /**
     * @return What this adapter was configured with, as the adapter resolved it - which is
     *         where the settings a worker of this extension shares with the adapter's own
     *         workers come from
     */
    public Camunda8AdapterConfiguration configuration() {

      return clientFactories.getFactory(scope.adapterId()).getConfiguration();

    }

  }

  private final Camunda8ClientFactoryRegistry clientFactories;

  private final NameClashAvoidanceSupport scoping;

  private final List<String> adapterIds;

  private final Map<String, Cluster> clusters = new ConcurrentHashMap<>();

  /**
   * @param clientFactories The adapter's own registry of clients, one per configured adapter
   *          id
   * @param scoping VanillaBP's name-clash avoidance, or <code>null</code> where the platform
   *          offers none
   * @param adapterTypes Which adapter id is of which type, as the platform resolved it
   */
  public Camunda8Clients(
      final Camunda8ClientFactoryRegistry clientFactories,
      final NameClashAvoidanceSupport scoping,
      final Map<String, String> adapterTypes) {

    this.clientFactories = clientFactories;
    this.scoping = scoping;
    final var camunda8 = new TreeSet<String>();
    adapterTypes
        .forEach((
            adapterId,
            adapterType) -> {
          if (Camunda8DeploymentService.ADAPTER_TYPE.equals(adapterType)) {
            camunda8.add(adapterId);
          }
        });
    this.adapterIds = List.copyOf(camunda8);

  }

  /**
   * @return Every configured adapter id of type <code>camunda8</code>, sorted
   */
  public List<String> adapterIds() {

    return adapterIds;

  }

  /**
   * @param adapterId A configured adapter id of type <code>camunda8</code>
   * @return Its cluster
   */
  public Cluster of(
      final String adapterId) {

    return clusters
        .computeIfAbsent(
            adapterId,
            id -> new Cluster(
                new Camunda8Scope(
                    id, scoping, clientFactories.getFactory(id).getConfiguration().getTenantId())));

  }

  /**
   * The clusters a workflow module is running on right now.
   * <p>
   * Each adapter registers its workflow module with its client while it starts, and the
   * pipeline starts every adapter of a module before it starts any extension of it, so by the
   * time this is asked the answer is complete. It leaves out an adapter whose deployment
   * failed and which the application was configured to start without.
   *
   * @param workflowModuleId The workflow module
   * @return The clusters holding it, in the order of their adapter ids
   */
  public List<Cluster> clustersHolding(
      final String workflowModuleId) {

    return adapterIds
        .stream()
        .filter(
            adapterId -> clientFactories
                .getFactory(adapterId)
                .getOpenWorkflowModules()
                .contains(workflowModuleId))
        .map(this::of)
        .toList();

  }

}
