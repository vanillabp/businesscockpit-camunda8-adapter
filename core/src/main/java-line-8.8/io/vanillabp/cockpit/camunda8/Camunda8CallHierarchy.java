package io.vanillabp.cockpit.camunda8;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.response.ProcessInstanceCallHierarchyEntryResponse;
import io.vanillabp.camunda8.client.Camunda8Errors;

/**
 * Which workflow a job belongs to, when the job sits in a called process. This is the 8.8
 * variant.
 * <p>
 * The cockpit shows business cases, and a called process is a step of one rather than a case of
 * its own - see decision 3 in the repository's DECISIONS.md. So every report has to know the
 * root of the hierarchy its job sits in.
 * <p>
 * Since 8.9 the job carries that root, and the 8.9 variant of this class reads it off the job.
 * The 8.8 job does not carry it, so the cluster is asked: its call-hierarchy request answers
 * with the chain from the root down to the instance, and the first entry is the root. Two things
 * follow from where that answer comes from, and both are the reason this class is the expensive
 * one.
 * <p>
 * It is served by the searchable storage, so it lags behind the transition whose listener is
 * running. A job of a process which was started a moment ago can get an answer which does not
 * exist yet, and the lookup therefore waits for it, up to
 * {@link Camunda8CockpitReads#WHILE_THE_EXPORTER_CATCHES_UP}. Waiting inside a listener job
 * holds that job's transition open, which is why the window is short and why the wait happens
 * only when the cluster has nothing to say yet.
 * <p>
 * If the window runs out, the job is treated as the root of its own hierarchy and the reason is
 * logged. That is the lesser of two wrong answers: a called process reported as a case adds a
 * case the cockpit should not show, while the alternative - failing the job - raises an incident
 * on a workflow which is doing nothing wrong (see decision 5).
 * <p>
 * It also costs a request per job, so what a hierarchy answered is remembered per process
 * instance. The relation cannot change: an instance's root is settled when it is created.
 */
final class Camunda8CallHierarchy {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CallHierarchy.class);

  /**
   * How long the lookup waits for the searchable storage before it answers without it, and how
   * often it asks in that time. Both are read where the extension already answers the same
   * question for a dispatch, so there is one window in this repository rather than two.
   */
  private static final Duration WAIT_FOR_THE_HIERARCHY = Camunda8CockpitReads.WHILE_THE_EXPORTER_CATCHES_UP;

  private static final Duration ASK_AGAIN_AFTER = Duration.ofMillis(100);

  /**
   * How many hierarchies are remembered before the memory is emptied. A workflow which is running
   * produces jobs of the same process instance again and again, so a small map already takes the
   * repeated requests off the cluster, and an entry which is dropped is one request away from
   * being back. Emptying it wholesale is what keeps this class a class: an eviction order would
   * need a map of its own, and a line may not carry a type the other lines do not have.
   */
  private static final int REMEMBERED_HIERARCHIES = 1_000;

  private final Camunda8Clients.Cluster cluster;

  /**
   * What the cluster answered, by process instance key, holding the ROOT key of each - the key
   * itself where the instance is its own root.
   */
  private final Map<Long, Long> rootsAnswered = new ConcurrentHashMap<>();

  /**
   * @param cluster The cluster the job came from, which this line has to ask
   */
  Camunda8CallHierarchy(
      final Camunda8Clients.Cluster cluster) {

    this.cluster = cluster;

  }

  /**
   * @param job The listener job a report is about
   * @return The process instance the whole hierarchy hangs below, or <code>null</code> where the
   *         job's own instance is that one
   */
  Long rootProcessInstanceKeyOf(
      final ActivatedJob job) {

    final var own = job.getProcessInstanceKey();
    // asked outside the map, because the answer may take a moment and every other lookup would
    // wait behind it for a hierarchy it does not care about
    var root = rootsAnswered.get(own);
    if (root == null) {
      root = rootAccordingToTheCluster(own);
      if (rootsAnswered.size() >= REMEMBERED_HIERARCHIES) {
        rootsAnswered.clear();
      }
      rootsAnswered.put(own, root);
    }
    return root.equals(own) ? null : root;

  }

  /**
   * The root of one instance's hierarchy, as the cluster tells it.
   *
   * @param processInstanceKey The instance a job of it is being reported
   * @return The root's key, which is the instance's own key where it is the root and where the
   *         cluster could not say in time
   */
  private Long rootAccordingToTheCluster(
      final Long processInstanceKey) {

    final var giveUpAt = System.nanoTime() + WAIT_FOR_THE_HIERARCHY.toNanos();
    while (true) {
      final var root = askOnce(processInstanceKey);
      if (root != null) {
        return root;
      }
      if (System.nanoTime() >= giveUpAt) {
        logger
            .warn(
                "Camunda8[{}]: the cluster does not know the call hierarchy of process instance {} yet, "
                    + "so this report treats it as a business case of its own. If it is a called process, "
                    + "the cockpit shows a case it should not show. This is a Camunda 8.8 cluster, whose "
                    + "jobs do not carry their root process instance; on 8.9 and above the job says it.",
                cluster.scope().adapterId(), processInstanceKey);
        return processInstanceKey;
      }
      try {
        Thread.sleep(ASK_AGAIN_AFTER.toMillis());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return processInstanceKey;
      }
    }

  }

  /**
   * One call-hierarchy request.
   *
   * @param processInstanceKey The instance to ask about
   * @return The root's key, or <code>null</code> where the searchable storage does not hold the
   *         instance yet
   */
  private Long askOnce(
      final Long processInstanceKey) {

    final List<ProcessInstanceCallHierarchyEntryResponse> hierarchy;
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
      return null;
    }
    // the chain runs from the root down to the instance which was asked about
    return hierarchy.get(0).getProcessInstanceKey();

  }

}
