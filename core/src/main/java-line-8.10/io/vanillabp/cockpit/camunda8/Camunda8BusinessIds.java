package io.vanillabp.cockpit.camunda8;

import io.camunda.client.api.search.response.ProcessInstance;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * The business key a workflow is shown under. This is the 8.10 variant.
 * <p>
 * Since 8.9 a process instance carries a business id of its own, and a cluster which holds one
 * has the last word. An instance may have been started by something other than this application,
 * and what that something called the case is what an operator searches for.
 * <p>
 * The cluster holds none for every workflow VanillaBP started, because the adapter creates
 * instances without one. Then the workflow aggregate's id is the business key. VanillaBP
 * addresses a workflow by that id everywhere else, and the reference the cockpit asked about
 * already carries it, so nothing is read for it.
 */
final class Camunda8BusinessIds {

  private Camunda8BusinessIds() {
  }

  /**
   * @param instance The process instance as the cluster answered it
   * @param workflow The workflow the cockpit asked about
   * @return The business key, or <code>null</code> where there is none
   */
  static String businessIdOf(
      final ProcessInstance instance,
      final WorkflowReference workflow) {

    final var businessId = instance.getBusinessId();
    return businessId != null ? businessId : workflow.workflowAggregateId();

  }

}
