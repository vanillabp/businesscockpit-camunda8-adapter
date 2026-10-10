package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import io.camunda.client.api.search.response.ProcessInstanceCallHierarchyEntryResponse;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;

/**
 * How a test says which processes a call hierarchy runs through. The cluster answers this with a
 * call-hierarchy request on every release line, and each entry names the definition its instance
 * runs on. The test gives every definition a key of its own and says which process it is.
 */
public final class CallChains {

  /** Added to an instance key to make the key of its definition, so the two never mix. */
  private static final long DEFINITION_KEYS_START_AT = 1_000_000L;

  private CallChains() {
  }

  /**
   * Answers the call-hierarchy request of one instance.
   *
   * @param clientFactories The factories the client of the adapter comes from
   * @param adapterId The adapter id
   * @param chain The scoped process id by instance key, from the root down to the instance asked
   *          about, which is the last one
   */
  public static void chainIs(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final LinkedHashMap<Long, String> chain) {

    final var client = clientFactories.getFactory(adapterId).getClient();
    final var entries = new ArrayList<ProcessInstanceCallHierarchyEntryResponse>();
    chain
        .forEach((
            processInstanceKey,
            scopedBpmnProcessId) -> {
          final var definitionKey = processInstanceKey + DEFINITION_KEYS_START_AT;
          final var entry = mock(ProcessInstanceCallHierarchyEntryResponse.class);
          when(entry.getProcessInstanceKey()).thenReturn(processInstanceKey);
          when(entry.getProcessDefinitionKey()).thenReturn(definitionKey);
          entries.add(entry);
          when(
              client
                  .newProcessDefinitionGetRequest(definitionKey)
                  .send()
                  .join()
                  .getProcessDefinitionId())
              .thenReturn(scopedBpmnProcessId);
        });
    final var asked = entries.getLast().getProcessInstanceKey();
    when(client.newProcessInstanceGetCallHierarchyRequest(asked).send().join()).thenReturn(entries);

  }

  /**
   * A chain of two: a caller and the instance it called.
   *
   * @param clientFactories The factories the client of the adapter comes from
   * @param adapterId The adapter id
   * @param callerKey The key of the calling instance
   * @param callingProcessId The scoped process id of the caller
   * @param calledKey The key of the called instance
   * @param calledProcessId The scoped process id of the called instance
   */
  public static void calls(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId,
      final long callerKey,
      final String callingProcessId,
      final long calledKey,
      final String calledProcessId) {

    final var chain = new LinkedHashMap<Long, String>();
    chain.put(callerKey, callingProcessId);
    chain.put(calledKey, calledProcessId);
    chainIs(clientFactories, adapterId, chain);

  }

}
