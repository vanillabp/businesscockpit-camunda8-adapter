package io.vanillabp.cockpit.camunda8.test;

import io.camunda.client.api.response.ActivatedJob;

/**
 * How a test says that a job reports the cancellation of its process instance. This is the 8.9
 * variant.
 * <p>
 * It cannot say it. A cluster of this line hands out no job when an instance is terminated, and
 * its client has no <code>CANCEL</code> event type to name one with. The tests which are about
 * such a job therefore do not run here.
 */
final class JobsOfACancellation {

  private JobsOfACancellation() {
  }

  /**
   * @return Whether an instance of this line reports its own cancellation at all
   */
  static boolean thisLineReportsThem() {

    return false;

  }

  /**
   * Never called on this line: a test asks {@link #thisLineReportsThem()} first.
   *
   * @param job The job which would report a cancellation
   */
  static void reportsACancellation(
      final ActivatedJob job) {

    throw new IllegalStateException(
        "Camunda 8.9 hands out no job for a cancelled instance, so no test may build one!");

  }

}
