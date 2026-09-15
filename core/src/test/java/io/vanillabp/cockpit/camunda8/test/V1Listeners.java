package io.vanillabp.cockpit.camunda8.test;

import java.util.LinkedList;

import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;

/**
 * The Version 1 code, kept here as the thing the new one is measured against.
 * <p>
 * It is a copy of what the Business Cockpit's Version 1 Camunda 8 adapter did to a user task
 * while deploying it, taken from
 * <code>io.vanillabp.cockpit.adapter.camunda8.deployments.Camunda8DeploymentAdapter</code>. A
 * test wires one model with it and one with the extension, and the two have to serialize to the
 * same bytes. That is what keeps an application which upgrades on the process version it is
 * running on.
 * <p>
 * It is deliberately a copy rather than a call. Version 1 is not a dependency of this
 * repository, and the promise is about the bytes it produced rather than about its code.
 * <p>
 * The <code>isNew</code> branch below, the one for a user task carrying no listeners at all,
 * is copied for completeness and never runs in the comparison. VanillaBP's Camunda 8 adapter
 * wires every user task it claims before the cockpit sees the model and leaves the
 * <code>zeebe:taskListeners</code> container behind, which is what the other branch reads, and
 * that was as true of Version 1 as it is of Version 2. The test class pins both halves of that
 * statement.
 */
public final class V1Listeners {

  /** What Version 1 built its listener types from. */
  public static final String JOBTYPE_DETAILSPROVIDER = "io.vanillabp.businesscockpit:";

  private V1Listeners() {
  }

  /**
   * Adds the three task listeners the way Version 1 added them.
   *
   * @param element The user task element
   * @param externalFormReference The form reference the listeners are named after
   */
  public static void addTaskListenersToBpmnModel(
      final BaseElement element,
      final String externalFormReference) {

    final ZeebeTaskListeners taskListeners;
    final boolean isNew;
    if (element.getSingleExtensionElement(ZeebeTaskListeners.class) != null) {
      taskListeners = element.getSingleExtensionElement(ZeebeTaskListeners.class);
      isNew = false;
    } else {
      taskListeners = element.getExtensionElements().addExtensionElement(ZeebeTaskListeners.class);
      isNew = true;
    }

    final var createListener = element.getModelInstance().newInstance(ZeebeTaskListener.class);
    createListener.setEventType(ZeebeTaskListenerEventType.creating);
    createListener.setType(JOBTYPE_DETAILSPROVIDER + externalFormReference);
    createListener.setRetries("0");

    if (isNew) {
      taskListeners.insertElementAfter(createListener, null);
    } else {
      final var previousListeners = new LinkedList<>(taskListeners
          .getTaskListeners()
          .stream()
          .filter(listener -> listener.getEventType().equals(ZeebeTaskListenerEventType.creating))
          .toList());
      taskListeners
          .insertElementAfter(
              createListener, previousListeners.isEmpty()
                  ? null
                  : previousListeners.getLast());
    }

    final var cancelListener = element.getModelInstance().newInstance(ZeebeTaskListener.class);
    cancelListener.setEventType(ZeebeTaskListenerEventType.canceling);
    cancelListener.setType(JOBTYPE_DETAILSPROVIDER + externalFormReference);
    cancelListener.setRetries("0");

    if (isNew) {
      taskListeners.insertElementAfter(cancelListener, createListener);
    } else {
      taskListeners
          .insertElementAfter(
              cancelListener, new LinkedList<>(taskListeners.getTaskListeners()).getLast());
    }

    final var completeListener = element.getModelInstance().newInstance(ZeebeTaskListener.class);
    completeListener.setEventType(ZeebeTaskListenerEventType.completing);
    completeListener.setType(JOBTYPE_DETAILSPROVIDER + externalFormReference);
    completeListener.setRetries("0");

    if (isNew) {
      taskListeners.insertElementAfter(completeListener, createListener);
    } else {
      taskListeners
          .insertElementAfter(
              completeListener, new LinkedList<>(taskListeners.getTaskListeners()).getLast());
    }

  }

}
