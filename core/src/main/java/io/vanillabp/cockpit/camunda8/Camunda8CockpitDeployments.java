package io.vanillabp.cockpit.camunda8;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What this extension put into the models of a workflow module, remembered until the workers
 * serving it are opened and for as long as they are.
 * <p>
 * Three things have to survive the wiring. The workers need to know which job types exist and
 * which variables each of them has to ask the cluster for. Every job arriving later has to be
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
   * Creates an empty record. The wiring adds the listeners of each workflow module to it while it
   * wires the module.
   */
  public Camunda8CockpitDeployments() {
  }

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
   * @param taskDefinition The task definition of the user task the listener sits on, as the
   *          application wrote it, which is what a <code>&#64;UserTaskDetailsProvider</code>
   *          method is matched by. <code>null</code> for a listener at the process or at a start
   *          event
   */
  public record WiredListener(
                              String listenerType,
                              String scopedBpmnProcessId,
                              String bpmnProcessId,
                              String elementId,
                              String elementName,
                              String bpmnProcessName,
                              String aggregateIdName,
                              String taskDefinition) {

    /**
     * A listener at the process or at a start event, which reports the workflow and not a user
     * task.
     *
     * @param listenerType See the record
     * @param scopedBpmnProcessId See the record
     * @param bpmnProcessId See the record
     * @param elementId See the record
     * @param elementName See the record
     * @param bpmnProcessName See the record
     * @param aggregateIdName See the record
     */
    public WiredListener(
        final String listenerType,
        final String scopedBpmnProcessId,
        final String bpmnProcessId,
        final String elementId,
        final String elementName,
        final String bpmnProcessName,
        final String aggregateIdName) {

      this(
          listenerType, scopedBpmnProcessId, bpmnProcessId, elementId, elementName, bpmnProcessName, aggregateIdName, null);

    }

    /**
     * Whether the listener sits on a user task. Only a user task has a details provider which may
     * read process variables.
     *
     * @return Whether the listener sits on a user task
     */
    public boolean sitsOnAUserTask() {

      return taskDefinition != null;

    }

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

  private final Map<Deployment, Camunda8MultiInstance.Registry> multiInstancesByDeployment = new ConcurrentHashMap<>();

  private final Map<Deployment, WorkflowTaskWiring> coresByDeployment = new ConcurrentHashMap<>();

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
   * Lists the listeners this extension added to one workflow module on one cluster.
   *
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

  /**
   * The BPMN processes of one workflow module this extension wired listeners into on one
   * cluster.
   * <p>
   * A search of the cluster's storage names a process the way the cluster knows it, and a report
   * names it the way the application wrote it. Both spellings come from here. So a process a
   * search asks for is one this extension wired, and a task found that way can be named back.
   *
   * @param adapterId The configured adapter id whose models were wired
   * @param workflowModuleId The workflow module
   * @return The plain BPMN process id by the id the cluster knows, in the order the processes
   *         were wired. Empty where the module was not wired for that adapter id
   */
  public Map<String, String> bpmnProcessIdsByScopedIdOf(
      final String adapterId,
      final String workflowModuleId) {

    final var byScopedId = new LinkedHashMap<String, String>();
    of(adapterId, workflowModuleId)
        .forEach(
            listener -> byScopedId
                .putIfAbsent(listener.scopedBpmnProcessId(), listener.bpmnProcessId()));
    return byScopedId;

  }

  /**
   * The BPMN name of one process, as this extension read it out of the model while wiring it.
   * <p>
   * A report about a workflow the cluster's searchable storage has not written yet takes its
   * fallback title from here, because there is no record to read the name from.
   *
   * @param adapterId The configured adapter id whose models were wired
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @return The name, or empty where this extension wired no listener into that process
   */
  public Optional<String> bpmnProcessNameOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return of(adapterId, workflowModuleId)
        .stream()
        .filter(listener -> listener.bpmnProcessId().equals(bpmnProcessId))
        .map(WiredListener::bpmnProcessName)
        .findFirst();

  }

  /**
   * Notes the multi-instance elements of one workflow module, once the adapter linked every
   * called process of the module to the elements of its caller.
   * <p>
   * The workers get them when they are opened. A report built from the searchable storage needs
   * them as well, to read the rounds of a task, and it has no worker to ask.
   *
   * @param adapterId The configured adapter id the module started for
   * @param workflowModuleId The workflow module which started
   * @param multiInstances The elements, as the adapter collected them
   */
  public void rememberMultiInstances(
      final String adapterId,
      final String workflowModuleId,
      final Camunda8MultiInstance.Registry multiInstances) {

    multiInstancesByDeployment.put(new Deployment(adapterId, workflowModuleId), multiInstances);

  }

  /**
   * Remembers the core which wired one workflow module on one cluster. A listener job asks it
   * whether a called process shares the workflow aggregate of its caller, and a job has no
   * other way to reach it.
   *
   * @param adapterId The configured adapter id the module was wired for
   * @param workflowModuleId The workflow module
   * @param core The core, which answers which processes share a workflow aggregate
   */
  public void rememberTheCore(
      final String adapterId,
      final String workflowModuleId,
      final WorkflowTaskWiring core) {

    coresByDeployment.put(new Deployment(adapterId, workflowModuleId), core);

  }

  /**
   * Whether two processes of one workflow module work on the same workflow aggregate. The core
   * answers it (<code>WorkflowTaskWiring#workflowsShareTheWorkflowAggregate</code>), and nothing
   * is decided here. See decision 30 in the repository's DECISIONS.md.
   *
   * @param adapterId The configured adapter id
   * @param workflowModuleId The workflow module
   * @param callingBpmnProcessId The calling process, as the application wrote it
   * @param calledBpmnProcessId The called process, as the application wrote it
   * @return Whether both share the aggregate. <code>false</code> where the module was not wired
   *         on that cluster, and where the core does not know one of the two processes
   */
  public boolean shareTheWorkflowAggregate(
      final String adapterId,
      final String workflowModuleId,
      final String callingBpmnProcessId,
      final String calledBpmnProcessId) {

    final var core = coresByDeployment.get(new Deployment(adapterId, workflowModuleId));
    return (core != null) && core
        .workflowsShareTheWorkflowAggregate(workflowModuleId, callingBpmnProcessId, calledBpmnProcessId);

  }

  /**
   * The multi-instance elements of one workflow module on one cluster.
   *
   * @param adapterId The configured adapter id
   * @param workflowModuleId The workflow module
   * @return The elements, or empty where the module has not started on that cluster
   */
  public Optional<Camunda8MultiInstance.Registry> multiInstancesOf(
      final String adapterId,
      final String workflowModuleId) {

    return Optional.ofNullable(multiInstancesByDeployment.get(new Deployment(adapterId, workflowModuleId)));

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
