package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.when;

import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;

/**
 * How a test says which workflow a job belongs to. This is the 8.10 variant.
 * <p>
 * Since 8.9 the job carries its root process instance, so saying it is stubbing the job. The 8.8
 * variant has to stub a cluster request instead, which is why this helper exists once per
 * release line and why a test says what it means rather than how the line finds it out.
 */
final class JobsInAHierarchy {

  private JobsInAHierarchy() {
  }

  /**
   * @param job The job under test, which is of a workflow nobody called
   * @param processInstanceKey The instance the job sits in
   */
  static void isItsOwnRoot(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final ActivatedJob job,
      final long processInstanceKey) {

    when(job.getProcessInstanceKey()).thenReturn(processInstanceKey);
    when(job.getRootProcessInstanceKey()).thenReturn(processInstanceKey);

  }

  /**
   * @param job The job under test, which is of a called process
   * @param processInstanceKey The instance the job sits in
   * @param rootProcessInstanceKey The instance the whole hierarchy hangs below
   */
  static void isCalledBy(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final ActivatedJob job,
      final long processInstanceKey,
      final long rootProcessInstanceKey) {

    when(job.getProcessInstanceKey()).thenReturn(processInstanceKey);
    when(job.getRootProcessInstanceKey()).thenReturn(rootProcessInstanceKey);

  }

}
