package io.vanillabp.cockpit.camunda8.quarkus;

import java.time.Duration;

import io.vanillabp.camunda8.quarkus.runtime.VanillaBpCamunda8Properties;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitSettings;

/**
 * What the Camunda 8 adapter's configuration says on Quarkus.
 */
public class Camunda8QuarkusSettings implements Camunda8CockpitSettings {

  private final VanillaBpCamunda8Properties properties;

  /**
   * @param properties The Camunda 8 adapter's own overlay of the <code>vanillabp</code>
   *          configuration tree
   */
  public Camunda8QuarkusSettings(
      final VanillaBpCamunda8Properties properties) {

    this.properties = properties;

  }

  @Override
  public Duration listenerJobTimeout(
      final String workflowModuleId,
      final String adapterId) {

    // asked at the workflow module rather than per workflow: one worker serves a job type
    // across every process using it, so a lock resolved per process would be several
    return properties.jobTimeoutFor(workflowModuleId, null, null, adapterId);

  }

}
