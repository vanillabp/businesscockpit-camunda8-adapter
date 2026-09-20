package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;

import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;

/**
 * Which execution listeners the cockpit writes at a BPMN process on this release line. This is
 * the 8.10 variant.
 * <p>
 * The <code>end</code> listener reports an instance which ran to its end, and every line has
 * one. The <code>cancel</code> listener beside it reports an instance somebody terminated, and
 * 8.10 is the first line whose cluster takes such a listener. Its event type has no name on the
 * 8.8 and 8.9 clients, so the answer lives once per line and the test says what it means rather
 * than which literal a line spells it with. See decision 11 in the repository's DECISIONS.md.
 */
final class ProcessListenersOfTheLine {

  private ProcessListenersOfTheLine() {
  }

  /**
   * @return The event types the cockpit writes at a process, in the order it writes them
   */
  static List<ZeebeExecutionListenerEventType> theEventTypesTheCockpitWrites() {

    return List.of(ZeebeExecutionListenerEventType.end, ZeebeExecutionListenerEventType.cancel);

  }

}
