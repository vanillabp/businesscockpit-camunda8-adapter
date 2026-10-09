package io.vanillabp.cockpit.camunda8;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.filter.UserTaskFilter;
import io.camunda.client.api.search.response.UserTask;

/**
 * Searches the user tasks of several BPMN processes at once. This is the 8.10 variant.
 * <p>
 * A task of a business case may sit in the process of the case or in a process it calls, so a
 * search for the tasks of a case names all of those processes. The 8.10 client can say "one of
 * these ids" in a filter, so this is one request. The 8.8 and 8.9 clients cannot, and their
 * variant sends one request per process.
 */
final class Camunda8UserTaskSearch {

  private Camunda8UserTaskSearch() {
  }

  /**
   * @param client The client of the cluster to search
   * @param scopedBpmnProcessIds The processes a task may sit in, as the cluster knows them. Never
   *          empty
   * @param conditions Everything else the search asks for
   * @return The tasks found
   */
  static List<UserTask> search(
      final CamundaClient client,
      final Collection<String> scopedBpmnProcessIds,
      final Consumer<UserTaskFilter> conditions) {

    final var ids = List.copyOf(scopedBpmnProcessIds);
    return client
        .newUserTaskSearchRequest()
        .filter(filter -> {
          if (ids.size() == 1) {
            filter.bpmnProcessId(ids.getFirst());
          } else {
            filter.bpmnProcessId(property -> property.in(ids));
          }
          conditions.accept(filter);
        })
        .send()
        .join()
        .items();

  }

}
