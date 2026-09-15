package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.response.ProcessInstanceCallHierarchyEntryResponse;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;

/**
 * How a test says which workflow a job belongs to. This is the 8.8 variant.
 * <p>
 * An 8.8 job does not carry its root process instance, so the extension asks the cluster for the
 * call hierarchy and this helper is what answers. Saying it this way keeps the tests of the
 * handler about the handler: they say a job is called by another workflow, and each line stubs
 * whatever it reads that from.
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
    answerWith(clientFactories, adapterId, processInstanceKey, List.of(anEntry(processInstanceKey)));

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
    answerWith(
        clientFactories, adapterId, processInstanceKey,
        // the chain runs from the root down to the instance which was asked about
        List.of(anEntry(rootProcessInstanceKey), anEntry(processInstanceKey)));

  }

  private static void answerWith(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final long processInstanceKey,
      final List<ProcessInstanceCallHierarchyEntryResponse> hierarchy) {

    when(
        clientFactories
            .getFactory(adapterId)
            .getClient()
            .newProcessInstanceGetCallHierarchyRequest(processInstanceKey)
            .send()
            .join())
        .thenReturn(hierarchy);

  }

  private static ProcessInstanceCallHierarchyEntryResponse anEntry(
      final long processInstanceKey) {

    final var entry = mock(ProcessInstanceCallHierarchyEntryResponse.class);
    when(entry.getProcessInstanceKey()).thenReturn(processInstanceKey);
    return entry;

  }

}
