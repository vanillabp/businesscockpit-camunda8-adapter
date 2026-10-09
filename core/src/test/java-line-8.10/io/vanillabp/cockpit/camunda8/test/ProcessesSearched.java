package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.camunda.client.api.search.filter.UserTaskFilter;
import io.camunda.client.api.search.filter.builder.StringProperty;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;

/**
 * Which BPMN processes the user-task search of the bridge asked for. This is the 8.10 variant.
 * <p>
 * The 8.10 client says "one of these ids" in one filter, so the bridge sends one request. The
 * 8.8 and 8.9 variants read one request per process.
 */
final class ProcessesSearched {

  private ProcessesSearched() {
  }

  /**
   * @return The process ids the search named, as the cluster knows them
   */
  @SuppressWarnings("unchecked")
  static Set<String> byTheUserTaskSearch(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId) {

    final ArgumentCaptor<Consumer<UserTaskFilter>> narrowing = ArgumentCaptor.captor();
    verify(clientFactories.getFactory(adapterId).getClient().newUserTaskSearchRequest())
        .filter(narrowing.capture());
    final var filter = mock(UserTaskFilter.class);
    narrowing.getValue().accept(filter);

    final var named = new HashSet<String>();
    Mockito.mockingDetails(filter).getInvocations().forEach(invocation -> {
      if (!invocation.getMethod().getName().equals("bpmnProcessId")) {
        return;
      }
      final var argument = invocation.getArgument(0);
      if (argument instanceof String id) {
        named.add(id);
      } else {
        final var property = mock(StringProperty.class);
        ((Consumer<StringProperty>) argument).accept(property);
        final ArgumentCaptor<List<String>> ids = ArgumentCaptor.captor();
        verify(property).in(ids.capture());
        named.addAll(ids.getValue());
      }
    });
    return named;

  }

}
