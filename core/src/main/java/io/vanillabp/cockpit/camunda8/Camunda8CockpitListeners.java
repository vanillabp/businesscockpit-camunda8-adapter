package io.vanillabp.cockpit.camunda8;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.ExtensionElements;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;

/**
 * The listeners the Business Cockpit puts into a Camunda 8 model, and the one place which
 * knows what they look like.
 * <p>
 * A Camunda 8 cluster tells nobody what it is doing unless it is asked to, so the only way to
 * watch a workflow is to have the cluster hand out a job whenever something happens. Each of
 * these listeners is such a job: it is delivered to a worker of this extension, gates the
 * transition it belongs to until that worker answers, and carries no retries - see decision 5 in
 * the repository's DECISIONS.md.
 * <p>
 * <b>The task listeners are byte-for-byte what Version 1 wrote.</b> Their job type, their
 * retries and where they are inserted are a compatibility promise rather than a choice: a
 * Version 1 application whose model is deployed again by Version 2 must produce the same bytes,
 * or the cluster stores a new process version and every running workflow keeps the old one - see
 * decision 4 in the repository's DECISIONS.md. The execution listeners are NOT the Version 1
 * ones - see decision 1 there.
 * <p>
 * Every method here is idempotent: the deployment pipeline hands the same model instance to
 * every executable process of a file, and re-wiring an element which already carries this
 * extension's listener leaves it alone.
 */
public final class Camunda8CockpitListeners {

  /**
   * What every listener type of this extension starts with. Version 1 used it, so a cluster
   * which already holds Version 1 models recognizes the very same job types, and an operator
   * reading a model sees whose listener it is.
   */
  public static final String LISTENER_TYPE_PREFIX = "io.vanillabp.businesscockpit:";

  /**
   * What the listeners are given as their retries. A report to the cockpit is not business
   * work the cluster may repeat on its own: a failure is an incident somebody has to look at,
   * and the entry which carries the report is retried by the outbox rather than by the cluster -
   * see decision 5 in the repository's DECISIONS.md.
   */
  public static final String RETRIES = "0";

  private Camunda8CockpitListeners() {
  }

  /**
   * @param identifier The BPMN process id respectively the external form reference, both in
   *          the form the CLUSTER knows them
   * @return The listener type, which is also the job type a worker of this extension
   *         subscribes to
   */
  public static String listenerTypeOf(
      final String identifier) {

    return LISTENER_TYPE_PREFIX + identifier;

  }

  /**
   * The inverse of {@link #listenerTypeOf}.
   *
   * @param listenerType A job type of this extension
   * @return What it was built from, or the type itself where it carries no such prefix
   */
  public static String identifierOf(
      final String listenerType) {

    return listenerType.startsWith(LISTENER_TYPE_PREFIX)
        ? listenerType.substring(LISTENER_TYPE_PREFIX.length())
        : listenerType;

  }

  /**
   * @param model The model of one BPMN file
   * @param scopedBpmnProcessId The process id as the CLUSTER knows it
   * @return The BPMN process element, or empty where this file holds no such process
   */
  public static Optional<Process> processOf(
      final BpmnModelInstance model,
      final String scopedBpmnProcessId) {

    return model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(process -> scopedBpmnProcessId.equals(process.getId()))
        .findFirst();

  }

  /**
   * The start events a workflow of this process begins at.
   * <p>
   * Only the ones the process itself carries: a start event of an embedded subprocess or of an
   * event subprocess starts a piece of the workflow rather than the workflow, and reporting it
   * would tell the cockpit about a case which began long ago.
   *
   * @param process The BPMN process element
   * @return Its own start events, in model order
   */
  public static List<StartEvent> startEventsOf(
      final Process process) {

    return process
        .getModelInstance()
        .getModelElementsByType(StartEvent.class)
        .stream()
        .filter(startEvent -> startEvent.getParentElement() == process)
        .toList();

  }

  /**
   * Adds the three task listeners of one user task, in the order Version 1 left behind.
   * <p>
   * VanillaBP's own listeners are already there when this runs - the deployment pipeline calls
   * an adapter before any extension - and the order this produces is the Version 1 one:
   * VanillaBP's <code>creating</code>, whatever the modeller wrote as a <code>creating</code>
   * listener, the cockpit's <code>creating</code>, the modeller's remaining listeners,
   * VanillaBP's <code>canceling</code>, and finally the cockpit's <code>canceling</code> and
   * <code>completing</code>. Each of the cockpit's listeners sits behind everything which may
   * still change what it is about to report.
   *
   * @param task The user task element
   * @param listenerType The type all three listeners carry
   * @return Whether they were added, <code>false</code> where this task already carries them
   */
  public static boolean addUserTaskListeners(
      final UserTask task,
      final String listenerType) {

    final var listeners = taskListenersOf(task);
    final var alreadyWired = listeners
        .getTaskListeners()
        .stream()
        .anyMatch(listener -> listenerType.equals(listener.getType()));
    if (alreadyWired) {
      return false;
    }

    final var creating = taskListener(task, ZeebeTaskListenerEventType.creating, listenerType);
    // behind the last listener which also runs while the task is being created, so that
    // what the cockpit reports includes whatever those listeners changed
    listeners
        .insertElementAfter(
            creating,
            lastOf(
                listeners
                    .getTaskListeners()
                    .stream()
                    .filter(listener -> ZeebeTaskListenerEventType.creating.equals(listener.getEventType()))
                    .toList()));
    // the other two are the last word on a task which is going away, so they go behind
    // everything, including VanillaBP's own 'canceling'
    listeners
        .insertElementAfter(
            taskListener(task, ZeebeTaskListenerEventType.canceling, listenerType),
            lastOf(listeners.getTaskListeners()));
    listeners
        .insertElementAfter(
            taskListener(task, ZeebeTaskListenerEventType.completing, listenerType),
            lastOf(listeners.getTaskListeners()));
    return true;

  }

  /**
   * Adds the listener which reports that a workflow began.
   * <p>
   * It is an <code>end</code> listener of the START EVENT and not a <code>start</code> listener
   * of the process, although the process is what began: a process-level <code>start</code>
   * listener runs before the variables the workflow was started with exist, so the workflow
   * aggregate's id - the one thing every report needs - would be missing. See decision 1 in the
   * repository's DECISIONS.md.
   *
   * @param startEvent The start event element
   * @param listenerType The listener's type
   * @return Whether it was added, <code>false</code> where this start event already carries it
   */
  public static boolean addStartEventListener(
      final StartEvent startEvent,
      final String listenerType) {

    return addExecutionListener(startEvent, listenerType);

  }

  /**
   * Adds the listener which reports that a workflow ended.
   *
   * @param process The BPMN process element
   * @param listenerType The listener's type
   * @return Whether it was added, <code>false</code> where this process already carries it
   */
  public static boolean addProcessListener(
      final Process process,
      final String listenerType) {

    return addExecutionListener(process, listenerType);

  }

  /**
   * An <code>end</code> execution listener, appended behind everything the element already
   * carries so that what the cockpit reports includes whatever those listeners changed.
   */
  private static boolean addExecutionListener(
      final BaseElement element,
      final String listenerType) {

    final var listeners = executionListenersOf(element);
    final var alreadyWired = listeners
        .getExecutionListeners()
        .stream()
        .anyMatch(listener -> listenerType.equals(listener.getType()));
    if (alreadyWired) {
      return false;
    }

    final var listener = element
        .getModelInstance()
        .newInstance(ZeebeExecutionListener.class);
    listener.setEventType(ZeebeExecutionListenerEventType.end);
    listener.setType(listenerType);
    listener.setRetries(RETRIES);
    listeners.insertElementAfter(listener, lastOf(listeners.getExecutionListeners()));
    return true;

  }

  private static ZeebeTaskListener taskListener(
      final UserTask task,
      final ZeebeTaskListenerEventType eventType,
      final String listenerType) {

    final var listener = task.getModelInstance().newInstance(ZeebeTaskListener.class);
    listener.setEventType(eventType);
    listener.setType(listenerType);
    listener.setRetries(RETRIES);
    return listener;

  }

  private static ZeebeTaskListeners taskListenersOf(
      final UserTask task) {

    final var existing = task.getSingleExtensionElement(ZeebeTaskListeners.class);
    return existing != null
        ? existing
        : extensionElementsOf(task).addExtensionElement(ZeebeTaskListeners.class);

  }

  private static ZeebeExecutionListeners executionListenersOf(
      final BaseElement element) {

    final var existing = element.getSingleExtensionElement(ZeebeExecutionListeners.class);
    return existing != null
        ? existing
        : extensionElementsOf(element).addExtensionElement(ZeebeExecutionListeners.class);

  }

  /**
   * An element which carries no extension elements at all needs the container before anything
   * can be put into it - a start event drawn without any configuration is the common case.
   */
  private static ExtensionElements extensionElementsOf(
      final BaseElement element) {

    if (element.getExtensionElements() == null) {
      element
          .setExtensionElements(element.getModelInstance().newInstance(ExtensionElements.class));
    }
    return element.getExtensionElements();

  }

  /**
   * @return The element to insert behind, or <code>null</code> - which is what the model
   *         library reads as "insert as the first one"
   */
  private static <T> T lastOf(
      final Collection<T> elements) {

    return elements.isEmpty()
        ? null
        : new LinkedList<>(elements).getLast();

  }

}
