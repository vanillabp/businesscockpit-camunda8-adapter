package io.vanillabp.cockpit.camunda8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda8.client.Camunda8Errors;

/**
 * Which process instance is the business case of an instance in a call hierarchy.
 * <p>
 * The cockpit shows business cases. A called process which shares the workflow aggregate of its
 * caller is a step of the caller's case. A called process with a workflow aggregate of its own is
 * a case of its own. See decision 30 in the repository's DECISIONS.md. So the case of an instance
 * is the highest instance above it which can be reached by links that share the aggregate. The
 * walk stops at the first caller which does not share it.
 * <p>
 * Whether two processes share the aggregate is the core's answer
 * (<code>WorkflowTaskWiring#workflowsShareTheWorkflowAggregate</code>), the same answer the
 * Camunda 8 adapter uses for its call activities. This class does not decide it. It only reads the
 * call hierarchy from the cluster and hands each pair of the chain to that answer.
 * <p>
 * The cluster is asked only where the answer can differ from the instance itself. An instance
 * with nothing above it is its own case, and so is an instance whose process shares its aggregate
 * with no other process of the workflow module. That is a called process with an aggregate of its
 * own, and it costs no request. Only an instance of a process which shares its aggregate with
 * another one, and which was called, needs the chain.
 * <p>
 * The chain comes from the searchable storage, which runs behind the engine. A listener of a
 * called process which started a moment ago can be served before the storage holds it. So the
 * lookup waits as long as the Camunda 8 adapter waits for the same storage
 * (<code>workflow-visibility-timeout</code>), like the 8.8 variant of
 * {@link Camunda8CallHierarchy}. If that window runs out, the root of the hierarchy is taken,
 * which is what every called process got before decision 30, and the reason is logged.
 * <p>
 * What the cluster answered is remembered per process instance and per process definition. Both
 * cannot change: the hierarchy of an instance is settled when it is created, and a deployed
 * definition keeps its id.
 */
final class Camunda8BusinessCases {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8BusinessCases.class);

  /** How often the lookup asks again while it waits. */
  private static final Duration ASK_AGAIN_AFTER = Duration.ofMillis(100);

  /**
   * How many answers are remembered before the memory is emptied. The reason for a small map
   * which is emptied as a whole is given at the 8.8 variant of {@link Camunda8CallHierarchy}.
   */
  private static final int REMEMBERED_ANSWERS = 1_000;

  /**
   * One instance of a call hierarchy.
   *
   * @param processInstanceKey The key of the instance
   * @param scopedBpmnProcessId The process of the instance as the cluster knows it, or
   *          <code>null</code> where the cluster could not say
   */
  record Link(
              Long processInstanceKey,
              String scopedBpmnProcessId) {
  }

  /**
   * Whether two processes of a workflow module share the workflow aggregate. The core answers
   * it, and the caller of this class says how it reaches the core.
   */
  @FunctionalInterface
  interface SharedAggregates {

    /**
     * @param callingBpmnProcessId The calling process, as the application wrote it
     * @param calledBpmnProcessId The called process, as the application wrote it
     * @return Whether both work on the same workflow aggregate
     */
    boolean share(
        String callingBpmnProcessId,
        String calledBpmnProcessId);

  }

  private final Camunda8Clients.Cluster cluster;

  /** The chain of each instance asked about, from the root down to that instance. */
  private final Map<Long, List<Link>> chains = new ConcurrentHashMap<>();

  /** The process id of each process definition key asked about, as the cluster knows it. */
  private final Map<Long, String> processIdsByDefinition = new ConcurrentHashMap<>();

  /**
   * @param cluster The cluster whose hierarchies this class reads
   */
  Camunda8BusinessCases(
      final Camunda8Clients.Cluster cluster) {

    this.cluster = cluster;

  }

  /**
   * The business case of one process instance.
   *
   * @param processInstanceKey The instance a report is about
   * @param scopedBpmnProcessId The process of that instance, as the cluster knows it
   * @param root The root of its hierarchy, or <code>null</code> where the instance is the root
   *          itself. On 8.8 nobody may know the root, and then <code>null</code> is passed too
   * @param hasACaller Whether the instance was called by another one. Where the root is known
   *          that is <code>root != null</code>
   * @param plainProcessIds The plain process id by the scoped one, for every process this
   *          extension wired for the workflow module
   * @param sharedAggregates The core's answer whether two processes share the aggregate
   * @return The key of the instance which is the business case
   */
  Long caseOf(
      final Long processInstanceKey,
      final String scopedBpmnProcessId,
      final Long root,
      final boolean hasACaller,
      final Map<String, String> plainProcessIds,
      final SharedAggregates sharedAggregates) {

    if (!hasACaller) {
      return processInstanceKey;
    }
    final var plainProcessId = plainProcessIds.get(scopedBpmnProcessId);
    if (!sharesWithAnotherProcess(plainProcessId, plainProcessIds, sharedAggregates)) {
      // a called process with an aggregate of its own, or one this module did not wire. No
      // caller can share an aggregate with it, so the cluster is not asked
      return processInstanceKey;
    }
    final var chain = chainOf(processInstanceKey);
    if (chain.isEmpty()) {
      // the storage did not answer in time. The root is what every called process got before
      // decision 30. Where nobody knows it either, the instance is its own case
      return root == null
          ? processInstanceKey
          : root;
    }
    // the chain ends with the instance itself, whose process the caller already named
    var theCase = new Link(processInstanceKey, scopedBpmnProcessId);
    for (var i = chain.size() - 2; i >= 0; --i) {
      final var caller = chain.get(i);
      final var callingProcessId = plainProcessIds.get(caller.scopedBpmnProcessId());
      final var calledProcessId = plainProcessIds.get(theCase.scopedBpmnProcessId());
      // a process this module did not wire is unknown to the core, and the core answers false
      // for it. So a call from a foreign process starts a case of its own
      if ((callingProcessId == null) || (calledProcessId == null) || !sharedAggregates
          .share(callingProcessId, calledProcessId)) {
        break;
      }
      theCase = caller;
    }
    return theCase.processInstanceKey();

  }

  /**
   * Whether the process shares its workflow aggregate with any other process this extension
   * wired for the module. Where it does not, no caller can be part of its case.
   */
  private static boolean sharesWithAnotherProcess(
      final String plainProcessId,
      final Map<String, String> plainProcessIds,
      final SharedAggregates sharedAggregates) {

    if (plainProcessId == null) {
      return false;
    }
    return plainProcessIds
        .values()
        .stream()
        .filter(other -> !other.equals(plainProcessId))
        .anyMatch(other -> sharedAggregates.share(other, plainProcessId));

  }

  /**
   * The chain of one instance, from the root down to that instance.
   *
   * @param processInstanceKey The instance
   * @return The chain, or an empty list where the storage did not hold the instance in time
   */
  private List<Link> chainOf(
      final Long processInstanceKey) {

    final var known = chains.get(processInstanceKey);
    if (known != null) {
      return known;
    }
    final var chain = chainAccordingToTheCluster(processInstanceKey);
    if (chain.isEmpty()) {
      // not remembered: the next report of the instance asks again, and by then the storage
      // has usually caught up
      return chain;
    }
    if (chains.size() >= REMEMBERED_ANSWERS) {
      chains.clear();
    }
    chains.put(processInstanceKey, chain);
    return chain;

  }

  private List<Link> chainAccordingToTheCluster(
      final Long processInstanceKey) {

    final var giveUpAt = System.nanoTime() + cluster.configuration().workflowVisibilityWindow().toNanos();
    while (true) {
      final var chain = askOnce(processInstanceKey);
      if (chain != null) {
        return chain;
      }
      if (System.nanoTime() >= giveUpAt) {
        logger
            .warn(
                "Camunda8[{}]: the cluster does not know the call hierarchy of process instance {} yet, "
                    + "so this report files it under the root of its hierarchy. If a process in between "
                    + "has a workflow aggregate of its own, the report names the wrong case. Raise the "
                    + "adapter's workflow-visibility-timeout if this happens often.",
                cluster.scope().adapterId(), processInstanceKey);
        return List.of();
      }
      try {
        Thread.sleep(ASK_AGAIN_AFTER.toMillis());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return List.of();
      }
    }

  }

  /**
   * One call-hierarchy request.
   *
   * @return The chain, or <code>null</code> where the storage does not hold the instance yet
   */
  private List<Link> askOnce(
      final Long processInstanceKey) {

    final List<io.camunda.client.api.search.response.ProcessInstanceCallHierarchyEntryResponse> hierarchy;
    try {
      hierarchy = cluster
          .client()
          .newProcessInstanceGetCallHierarchyRequest(processInstanceKey)
          .send()
          .join();
    } catch (final RuntimeException e) {
      if (Camunda8Errors.notFound(e)) {
        return null;
      }
      throw e;
    }
    if ((hierarchy == null) || hierarchy.isEmpty()) {
      // the cluster holds the instance, and it stands in no hierarchy
      return List.of(new Link(processInstanceKey, null));
    }
    final var chain = new ArrayList<Link>(hierarchy.size());
    for (final var entry : hierarchy) {
      chain.add(new Link(entry.getProcessInstanceKey(), processIdOf(entry.getProcessDefinitionKey())));
    }
    return List.copyOf(chain);

  }

  /**
   * The process id of a deployed definition, as the cluster knows it.
   *
   * @param processDefinitionKey The definition's key
   * @return The id, or <code>null</code> where the cluster could not say
   */
  private String processIdOf(
      final Long processDefinitionKey) {

    if (processDefinitionKey == null) {
      return null;
    }
    final var known = processIdsByDefinition.get(processDefinitionKey);
    if (known != null) {
      return known;
    }
    final String processId;
    try {
      processId = cluster
          .client()
          .newProcessDefinitionGetRequest(processDefinitionKey)
          .send()
          .join()
          .getProcessDefinitionId();
    } catch (final RuntimeException e) {
      if (Camunda8Errors.notFound(e)) {
        // a definition is in the storage long before an instance of it is. A missing one
        // breaks the walk at this link, which makes the instance below a case of its own
        return null;
      }
      throw e;
    }
    if (processId == null) {
      return null;
    }
    if (processIdsByDefinition.size() >= REMEMBERED_ANSWERS) {
      processIdsByDefinition.clear();
    }
    processIdsByDefinition.put(processDefinitionKey, processId);
    return processId;

  }

}
