package io.vanillabp.cockpit.camunda8.test;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.mockito.ArgumentCaptor;

import io.camunda.client.api.search.filter.UserTaskFilter;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;

/**
 * Which BPMN processes the user-task search of the bridge asked for. This is the 8.9 variant.
 * <p>
 * The 8.9 client knows only "this one id", so the bridge sends one request per process. The 8.10
 * variant reads one request naming all of them.
 */
final class ProcessesSearched {

  private ProcessesSearched() {
  }

  /**
   * @return The process ids the search named, as the cluster knows them
   */
  static Set<String> byTheUserTaskSearch(
      final Camunda8ClientFactoryRegistry clientFactories,
      final String adapterId) {

    final ArgumentCaptor<Consumer<UserTaskFilter>> narrowing = ArgumentCaptor.captor();
    verify(clientFactories.getFactory(adapterId).getClient().newUserTaskSearchRequest(), atLeastOnce())
        .filter(narrowing.capture());
    final var named = new HashSet<String>();
    narrowing.getAllValues().forEach(consumer -> {
      final var filter = mock(UserTaskFilter.class);
      consumer.accept(filter);
      final var id = ArgumentCaptor.forClass(String.class);
      verify(filter).bpmnProcessId(id.capture());
      named.add(id.getValue());
    });
    return named;

  }

}
