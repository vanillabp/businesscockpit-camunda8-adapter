package io.vanillabp.cockpit.camunda8;

import io.camunda.client.api.search.response.ProcessInstance;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * The business key a workflow is shown under. This is the 8.8 variant.
 * <p>
 * An 8.8 process instance carries no business id; the field arrived with 8.9, and the 8.9
 * variant of this class prefers it where the cluster holds one.
 * <p>
 * What this line answers is what the newer lines answer for every workflow VanillaBP started:
 * the workflow aggregate's id. It is the business key of a VanillaBP workflow, it is how the
 * adapter finds an instance again - it writes the id into a variable and searches by it - and
 * the reference the cockpit asked about already carries it. So the value is the same on every
 * line without asking the cluster, and reading the variable back would only cost a request to
 * arrive at the id which is already in hand.
 */
final class Camunda8BusinessIds {

  private Camunda8BusinessIds() {
  }

  /**
   * @param instance The process instance as the cluster answered it, which this line has nothing
   *          to read a business id from
   * @param workflow The workflow the cockpit asked about
   * @return The business key, or <code>null</code> where there is none
   */
  static String businessIdOf(
      final ProcessInstance instance,
      final WorkflowReference workflow) {

    return workflow.workflowAggregateId();

  }

}
