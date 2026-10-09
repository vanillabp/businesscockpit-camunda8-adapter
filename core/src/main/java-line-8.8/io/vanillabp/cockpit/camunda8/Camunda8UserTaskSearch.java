package io.vanillabp.cockpit.camunda8;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.filter.UserTaskFilter;
import io.camunda.client.api.search.response.UserTask;

/**
 * Searches the user tasks of several BPMN processes. This is the 8.8 variant.
 * <p>
 * A task of a business case may sit in the process of the case or in a process it calls, so a
 * search for the tasks of a case names all of those processes. The 8.8 client knows only "this
 * one id" for the process of a task, and every condition of a filter has to hold. So this line
 * sends one request per process and puts the answers together. The 8.10 variant asks once.
 */
final class Camunda8UserTaskSearch {

  private Camunda8UserTaskSearch() {
  }

  /**
   * @param client The client of the cluster to search
   * @param scopedBpmnProcessIds The processes a task may sit in, as the cluster knows them. Never
   *          empty
   * @param conditions Everything else the search asks for
   * @return The tasks found, each one once
   */
  static List<UserTask> search(
      final CamundaClient client,
      final Collection<String> scopedBpmnProcessIds,
      final Consumer<UserTaskFilter> conditions) {

    // a task sits in one process only, so two answers never hold the same task. The map is
    // there all the same, because a doubled task would become a doubled report
    final var byKey = new LinkedHashMap<Long, UserTask>();
    scopedBpmnProcessIds
        .forEach(
            scopedBpmnProcessId -> client
                .newUserTaskSearchRequest()
                .filter(filter -> {
                  filter.bpmnProcessId(scopedBpmnProcessId);
                  conditions.accept(filter);
                })
                .send()
                .join()
                .items()
                .forEach(task -> byKey.putIfAbsent(task.getUserTaskKey(), task)));
    return List.copyOf(byKey.values());

  }

}
