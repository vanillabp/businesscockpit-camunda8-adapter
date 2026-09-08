package io.vanillabp.cockpit.camunda8;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * The Camunda 8 clusters this application talks to, as far as the Business Cockpit needs them.
 * <p>
 * The clients belong to the VanillaBP Camunda 8 adapter and are taken from it, never built
 * here: it owns their authentication, their executors and their shutdown, and a second client
 * beside them would be a second connection nobody configured.
 * <p>
 * <b>Which cluster a call is about</b> is said by the adapter's own processing context, which
 * every step of the deployment pipeline hands over - see decision 2 in the repository's
 * DECISIONS.md.
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
     *         where the stream timeout of a worker of this extension comes from, the one
     *         worker setting the adapter's client does not already carry
     */
    public Camunda8AdapterConfiguration configuration() {

      return clientFactories.getFactory(scope.adapterId()).getConfiguration();

    }

  }

  private final Camunda8ClientFactoryRegistry clientFactories;

  private final NameClashAvoidanceSupport scoping;

  private final Map<String, Cluster> clusters = new ConcurrentHashMap<>();

  /**
   * @param clientFactories The adapter's own registry of clients, one per configured adapter
   *          id
   * @param scoping VanillaBP's name-clash avoidance, or <code>null</code> where the platform
   *          offers none
   */
  public Camunda8Clients(
      final Camunda8ClientFactoryRegistry clientFactories,
      final NameClashAvoidanceSupport scoping) {

    this.clientFactories = clientFactories;
    this.scoping = scoping;

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

}
