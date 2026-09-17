package io.vanillabp.cockpit.camunda8;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this extension put into the models of a workflow module, remembered until the workers
 * serving it are opened and for as long as they are.
 * <p>
 * Three things have to survive the wiring. The workers need to know which job types exist and
 * which variable each of them has to ask the cluster for. Every job arriving later has to be
 * translated back, because it carries the identifiers the CLUSTER knows, while everything the
 * cockpit is told is spelled the way the application wrote it. So is everything the
 * application's own methods are matched by. And a report is built from the job alone, so what
 * the job does not carry has to be read out of the model here: the BPMN names of the process and
 * of the element the listener sits on.
 * <p>
 * Everything here is per adapter id and workflow module. A module deployed to two Camunda 8
 * clusters is wired twice, once per adapter. The two runs put different identifiers into their
 * models wherever the two avoid name clashes differently, so the workers of one cluster
 * subscribe to what THAT cluster's models carry and to nothing else.
 */
public class Camunda8CockpitDeployments {

  /**
   * One place a listener of this extension sits at.
   *
   * @param listenerType The job type the listener produces, which is what a worker subscribes
   *          to
   * @param scopedBpmnProcessId The BPMN process id as the cluster knows it
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @param elementId The BPMN element the listener sits on, which is a user task, a start event
   *          or the process itself
   * @param elementName The BPMN name of that element, which a report of a user task carries as
   *          the title the cockpit falls back to. A job says which element it comes from and
   *          never what that element is called, so the name is taken out of the model while it
   *          is being wired and kept here
   * @param bpmnProcessName The BPMN name of the process, the other fallback title. It is taken
   *          out of the model for the same reason
   * @param aggregateIdName The process variable the workflow aggregate's id is carried in
   */
  public record WiredListener(
                              String listenerType,
                              String scopedBpmnProcessId,
                              String bpmnProcessId,
                              String elementId,
                              String elementName,
                              String bpmnProcessName,
                              String aggregateIdName) {
  }

  /**
   * One deployment of one workflow module: its models were prepared for exactly one configured
   * adapter, and what was wired into them belongs to that adapter's cluster.
   *
   * @param adapterId The configured adapter id the models were wired for
   * @param workflowModuleId The workflow module
   */
  private record Deployment(
                            String adapterId,
                            String workflowModuleId) {
  }

  private final Map<Deployment, List<WiredListener>> listenersByDeployment = new ConcurrentHashMap<>();

  /**
   * Notes one listener this extension added.
   *
   * @param adapterId The configured adapter id whose models were being wired
   * @param workflowModuleId The workflow module the process belongs to
   * @param listener Where the listener sits and what it reports about
   */
  public void register(
      final String adapterId,
      final String workflowModuleId,
      final WiredListener listener) {

    final var listeners = listenersByDeployment
        .computeIfAbsent(new Deployment(adapterId, workflowModuleId), id -> new LinkedList<>());
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
   * @param adapterId The configured adapter id whose cluster the workers are opened on
   * @param workflowModuleId The workflow module
   * @return Its listeners by job type, in the order they were wired, which is one worker each
   */
  public Map<String, List<WiredListener>> listenersByTypeOf(
      final String adapterId,
      final String workflowModuleId) {

    final var byType = new LinkedHashMap<String, List<WiredListener>>();
    of(adapterId, workflowModuleId)
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
   * @param adapterId The configured adapter id whose cluster delivered the job
   * @param workflowModuleId The workflow module the worker was opened for
   * @param scopedBpmnProcessId The BPMN process id the job carries
   * @param listenerType The job's type
   * @return The listener the job comes from, or empty where this module wired no such listener.
   *         Then the job comes from a model somebody else deployed under the same job type
   */
  public Optional<WiredListener> listenerOf(
      final String adapterId,
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String listenerType) {

    return of(adapterId, workflowModuleId)
        .stream()
        .filter(listener -> listener.listenerType().equals(listenerType))
        .filter(listener -> listener.scopedBpmnProcessId().equals(scopedBpmnProcessId))
        .findFirst();

  }

  private List<WiredListener> of(
      final String adapterId,
      final String workflowModuleId) {

    final var listeners = listenersByDeployment.get(new Deployment(adapterId, workflowModuleId));
    if (listeners == null) {
      return List.of();
    }
    synchronized (listeners) {
      return List.copyOf(listeners);
    }

  }

}
