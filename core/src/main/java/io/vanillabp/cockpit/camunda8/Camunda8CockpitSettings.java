package io.vanillabp.cockpit.camunda8;

import java.time.Duration;

/**
 * What the extension has to know about a configured Camunda 8 adapter and cannot read from the
 * cluster.
 * <p>
 * There is one such question, and the platform modules of this repository answer it rather than
 * a key here. The adapter's configuration overlay (<code>vanillabp.adapters.&lt;id&gt;.*</code>)
 * is bound differently on Spring Boot than on Quarkus, and this extension deliberately adds no
 * lock setting of its own. A workflow module which raised the adapter's job timeout raised it
 * for the cockpit's listeners as well.
 */
public interface Camunda8CockpitSettings {

  /**
   * How long a listener job of this extension stays locked for its worker.
   *
   * @param workflowModuleId The workflow module the worker was opened for
   * @param adapterId The configured adapter id
   * @return What <code>job-timeout</code> resolves to at the workflow module, or at the
   *         adapter, or the adapter's own default where neither says anything
   */
  Duration listenerJobTimeout(
      String workflowModuleId,
      String adapterId);

}
