package io.vanillabp.cockpit.camunda8;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this extension put into the models of a workflow module, remembered until the workers
 * serving it are opened and for as long as they are.
 * <p>
 * Two things have to survive the wiring. The workers need to know which job types exist and
 * which variable each of them has to ask the cluster for, and every job arriving later has to
 * be translated back: it carries the identifiers the CLUSTER knows, while everything the
 * cockpit is told - and everything the application's own methods are matched by - is spelled
 * the way the application wrote it.
 * <p>
 * Nothing here is per adapter id. A workflow module deployed to two Camunda 8 clusters is
 * wired twice, and where those two use different name-clash avoidance the two spellings simply
 * both end up in this register; a job carries the spelling of the cluster it came from, so it
 * finds its own.
 */
public class Camunda8CockpitDeployments {

  /**
   * One place a listener of this extension sits at.
   *
   * @param listenerType The job type the listener produces, which is what a worker subscribes
   *          to
   * @param scopedBpmnProcessId The BPMN process id as the cluster knows it
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @param elementId The BPMN element the listener sits on - a user task, a start event, or
   *          the process itself
   * @param userTask Whether it is a user task's listener; the other two report the workflow
   * @param aggregateIdName The process variable the workflow aggregate's id is carried in
   */
  public record WiredListener(
                              String listenerType,
                              String scopedBpmnProcessId,
                              String bpmnProcessId,
                              String elementId,
                              boolean userTask,
                              String aggregateIdName) {
  }

  private final Map<String, List<WiredListener>> listenersByWorkflowModule = new ConcurrentHashMap<>();

  /**
   * Notes one listener this extension added.
   *
   * @param workflowModuleId The workflow module the process belongs to
   * @param listener Where the listener sits and what it reports about
   */
  public void register(
      final String workflowModuleId,
      final WiredListener listener) {

    final var listeners = listenersByWorkflowModule
        .computeIfAbsent(workflowModuleId, id -> new LinkedList<>());
    synchronized (listeners) {
      final var alreadyKnown = listeners
          .stream()
          .anyMatch(
              known -> known.listenerType().equals(listener.listenerType()) && known.scopedBpmnProcessId()
                  .equals(listener.scopedBpmnProcessId()) && known.elementId().equals(listener.elementId()));
      if (!alreadyKnown) {
        listeners.add(listener);
      }
    }

  }

  /**
   * @param workflowModuleId The workflow module
   * @return Its listeners by job type, in the order they were wired - which is one worker each
   */
  public Map<String, List<WiredListener>> listenersByTypeOf(
      final String workflowModuleId) {

    final var byType = new LinkedHashMap<String, List<WiredListener>>();
    of(workflowModuleId)
        .forEach(
            listener -> byType
                .computeIfAbsent(listener.listenerType(), type -> new LinkedList<>())
                .add(listener));
    return byType;

  }

  /**
   * The variables one worker has to ask the cluster for: the workflow aggregate's id, under
   * whatever name each of the processes this worker serves carries it.
   *
   * @param listeners What the worker serves
   * @return The variable names, sorted so that the worker's subscription is stable
   */
  public static Collection<String> aggregateIdVariablesOf(
      final Collection<WiredListener> listeners) {

    final var names = new TreeSet<String>();
    listeners.forEach(listener -> names.add(listener.aggregateIdName()));
    return names;

  }

  /**
   * What a job of this extension belongs to.
   *
   * @param workflowModuleId The workflow module the worker was opened for
   * @param scopedBpmnProcessId The BPMN process id the job carries
   * @param listenerType The job's type
   * @return The listener the job comes from, or empty where this module wired none such - a
   *         job of a model somebody else deployed under the same job type
   */
  public Optional<WiredListener> listenerOf(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String listenerType) {

    return of(workflowModuleId)
        .stream()
        .filter(listener -> listener.listenerType().equals(listenerType))
        .filter(listener -> listener.scopedBpmnProcessId().equals(scopedBpmnProcessId))
        .findFirst();

  }

  /**
   * @param workflowModuleId The workflow module
   * @return Whether this extension wired anything of that module
   */
  public boolean knows(
      final String workflowModuleId) {

    return !of(workflowModuleId).isEmpty();

  }

  /**
   * @return Every workflow module something was wired for
   */
  public Set<String> workflowModules() {

    return Set.copyOf(listenersByWorkflowModule.keySet());

  }

  private List<WiredListener> of(
      final String workflowModuleId) {

    final var listeners = listenersByWorkflowModule.get(workflowModuleId);
    if (listeners == null) {
      return List.of();
    }
    synchronized (listeners) {
      return List.copyOf(listeners);
    }

  }

}
