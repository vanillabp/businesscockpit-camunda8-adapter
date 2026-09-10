package io.vanillabp.cockpit.camunda8.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.cockpit.camunda8.quarkus.Camunda8CockpitProducer;

/**
 * What the Camunda 8 half of the Business Cockpit extension has to say at build time.
 * <p>
 * It produces no VanillaBP build item: an extension announces itself by the beans it produces,
 * unlike a BPMS adapter.
 */
class Camunda8CockpitProcessor {

  private static final String FEATURE = "vanillabp-business-cockpit-camunda8";

  /**
   * @param featureProducer Where the feature is announced, so that a booting application lists
   *          the extension
   * @return The producer class, as a bean nothing may remove
   */
  @BuildStep
  AdditionalBeanBuildItem registerProducer(
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(Camunda8CockpitProducer.class)
        .setUnremovable()
        .build();

  }

}
