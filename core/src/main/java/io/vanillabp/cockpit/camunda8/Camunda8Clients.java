package io.vanillabp.cockpit.camunda8;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * The Camunda 8 clusters this application talks to, as far as the Business Cockpit needs them.
 * <p>
 * The clients belong to the VanillaBP Camunda 8 adapter and are taken from it, never built
 * here: it owns their authentication, their executors and their shutdown, and a second client
 * beside them would be a second connection nobody configured.
 * <p>
 * Which cluster a call is about is said by the adapter's own processing context, which every
 * step of the deployment pipeline hands over. See decision 2 in the repository's DECISIONS.md.
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
     * The adapter's own factory of this cluster, which is the one object the adapter and this
     * extension both hold per adapter id. What the extension asks it for is what a workflow
     * module has in common across the two: the drain a listener job takes part in, and the
     * hook which closes a module's workers if the client goes down before they did.
     *
     * @return The factory of this adapter id
     */
    public Camunda8ClientFactory factory() {

      return clientFactories.getFactory(scope.adapterId());

    }

    /**
     * @return The client of this adapter, which the adapter hands out by identity
     */
    public CamundaClient client() {

      return factory().getClient();

    }

    /**
     * @return What this adapter was configured with, as the adapter resolved it. The stream
     *         timeout of a worker of this extension comes from there, and it is the one worker
     *         setting the adapter's client does not already carry
     */
    public Camunda8AdapterConfiguration configuration() {

      return factory().getConfiguration();

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
