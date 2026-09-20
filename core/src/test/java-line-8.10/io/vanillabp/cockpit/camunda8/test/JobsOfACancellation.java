package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.when;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;

/**
 * How a test says that a job reports the cancellation of its process instance. This is the 8.10
 * variant.
 * <p>
 * The cluster says it with the event type <code>CANCEL</code> of an execution-listener job, a
 * literal the 8.8 and 8.9 clients do not have. So the helper exists once per release line, and
 * a test says what it means rather than which literal the line spells it with.
 */
final class JobsOfACancellation {

  private JobsOfACancellation() {
  }

  /**
   * @return Whether an instance of this line reports its own cancellation at all
   */
  static boolean thisLineReportsThem() {

    return true;

  }

  /**
   * @param job The job under test, which is the cancel listener of its process
   */
  static void reportsACancellation(
      final ActivatedJob job) {

    when(job.getListenerEventType()).thenReturn(ListenerEventType.CANCEL);

  }

}
