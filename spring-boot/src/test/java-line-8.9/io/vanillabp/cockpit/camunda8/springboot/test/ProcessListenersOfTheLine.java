package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;

import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;

/**
 * Which execution listeners the cockpit writes at a BPMN process on this release line. This is
 * the 8.9 variant.
 * <p>
 * Only the <code>end</code> listener, which reports an instance that ran to its end. A
 * <code>cancel</code> listener arrived with 8.10. A cluster of this line refuses a model which
 * carries one, and this line's client has no event type to name one with either. So an
 * instance somebody terminates goes without a word here. See decision 11 in the repository's
 * DECISIONS.md.
 */
final class ProcessListenersOfTheLine {

  private ProcessListenersOfTheLine() {
  }

  /**
   * @return The event types the cockpit writes at a process, in the order it writes them
   */
  static List<ZeebeExecutionListenerEventType> theEventTypesTheCockpitWrites() {

    return List.of(ZeebeExecutionListenerEventType.end);

  }

}
