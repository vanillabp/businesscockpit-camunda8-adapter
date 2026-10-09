package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.camunda.client.api.search.response.ProcessInstanceCallHierarchyEntryResponse;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;

/**
 * How a test says which workflow a user task read from the searchable storage belongs to. This is
 * the 8.8 variant.
 * <p>
 * An 8.8 record of a task does not name its root process instance, so the extension asks the
 * cluster for the call hierarchy and this helper is what answers.
 */
final class TasksInAHierarchy {

  private TasksInAHierarchy() {
  }

  /**
   * @param task The task under test, which sits in a workflow nobody called
   * @param processInstanceKey The instance the task sits in
   */
  static void isItsOwnRoot(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final UserTask task,
      final long processInstanceKey) {

    when(task.getProcessInstanceKey()).thenReturn(processInstanceKey);
    answerWith(clientFactories, adapterId, processInstanceKey, List.of(anEntry(processInstanceKey)));

  }

  /**
   * @param task The task under test, which sits in a called process
   * @param processInstanceKey The instance the task sits in
   * @param rootProcessInstanceKey The instance the whole hierarchy hangs below
   */
  static void isCalledBy(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final UserTask task,
      final long processInstanceKey,
      final long rootProcessInstanceKey) {

    when(task.getProcessInstanceKey()).thenReturn(processInstanceKey);
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
