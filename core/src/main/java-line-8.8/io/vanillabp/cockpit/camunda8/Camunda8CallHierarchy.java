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
 * its own. See decision 3 in the repository's DECISIONS.md. So every report has to know the root
 * of the hierarchy its job sits in.
 * <p>
 * Since 8.9 the job carries that root, and the 8.9 variant of this class reads it off the job.
 * The 8.8 job does not carry it, so the cluster is asked. Where that answer comes from is what
 * makes this class the expensive one.
 * <p>
 * An 8.8 cluster gives three answers, and telling them apart is the whole job of
 * {@link #askOnce(Long)}.
 * <ul>
 * <li>A chain of entries, running from the root down to the instance which was asked about. The
 * first entry is the root.</li>
 * <li>No entries at all. That is an answer and not a gap: the cluster holds the instance, and the
 * instance stands in no call hierarchy. So it is its own root. A workflow nobody called is
 * answered this way, which is nearly every workflow there is.</li>
 * <li>Nothing about that instance, which the cluster says with an HTTP <code>404</code>. Only
 * this one means the searchable storage has not caught up yet.</li>
 * </ul>
 * <p>
 * Only the third answer is waited for. It is served by the searchable storage, which lags behind
 * the transition whose listener is running, so a job of a process which started a moment ago can
 * be asked about before the cluster holds it. How long the lookup waits is the adapter's word.
 * <code>vanillabp.adapters.&lt;id&gt;.workflow-visibility-timeout</code> is what the Camunda 8
 * adapter waits out for the same storage when it knows a workflow is there, ten seconds by
 * default, and zero switches the waiting off here as it does there. A cluster whose exporter is
 * slow is slow for both of them, so it is one number and not two.
 * <p>
 * Waiting inside a listener job holds that job's transition open, and this is the one thing the
 * extension still waits for there. That is why the wait happens only when the cluster has nothing
 * to say yet, and why a deployment which cannot afford it sets the adapter's key to zero. Nothing
 * else a report needs is read from that storage: it is built from the job. See
 * {@link Camunda8CockpitJobHandler}.
 * <p>
 * That is why the empty answer may not be read as a gap. Every ordinary workflow would pay the
 * whole window for it, with its transition standing still and an execution slot of the adapter
 * spent on the waiting. And the log would fill up with warnings about a cluster which is not
 * behind at all.
 * <p>
 * If the window runs out, the job is treated as the root of its own hierarchy and the reason is
 * logged. That is the lesser of two wrong answers. A called process reported as a case adds a
 * case the cockpit should not show. Failing the job, which is the other option, raises an
 * incident on a workflow which is doing nothing wrong (see decision 5).
 * <p>
 * Every answer costs a request, so what the cluster said is remembered per process instance. The
 * relation cannot change, because an instance's root is settled when it is created. And one of
 * these belongs to one cluster rather than to one worker, so the workers of a workflow share what
 * any of them asked. A workflow is served by several of them - one for its start event and one
 * per task definition - and they all ask about the same instance.
 */
final class Camunda8CallHierarchy {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CallHierarchy.class);

  /** How often the lookup asks again while it waits. */
  private static final Duration ASK_AGAIN_AFTER = Duration.ofMillis(100);

  /**
   * How many hierarchies are remembered before the memory is emptied. A workflow which is running
   * produces jobs of the same process instance again and again, so a small map already takes the
   * repeated requests off the cluster, and an entry which is dropped is one request away from
   * being back. Emptying the whole map is what keeps this class a single class. An eviction order
   * would need a map of its own, and a line may not carry a type the other lines do not have.
   */
  private static final int REMEMBERED_HIERARCHIES = 1_000;

  private final Camunda8Clients.Cluster cluster;

  /**
   * What the cluster answered, by process instance key, holding the ROOT key of each. That is
   * the key itself where the instance is its own root.
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

    final var giveUpAt = System.nanoTime() + waitForTheHierarchy().toNanos();
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
   * How long this lookup waits for the searchable storage, which is what the adapter of this
   * cluster waits out for the same storage elsewhere.
   *
   * @return The adapter's <code>workflow-visibility-timeout</code>, its default where the
   *         application configured none, and zero where the application switched the waiting off
   */
  private Duration waitForTheHierarchy() {

    // the adapter resolves the key itself, so the default lives where the key does and a later
    // change of it reaches this lookup without anybody remembering to copy a number
    return cluster.configuration().workflowVisibilityWindow();

  }

  /**
   * One call-hierarchy request.
   *
   * @param processInstanceKey The instance to ask about
   * @return The root's key, or <code>null</code> where the searchable storage does not hold the
   *         instance yet, which is the only answer worth asking again for
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
      // the cluster holds no such instance. Asking again is what this is for: the exporter
      // writes the instance a moment after the engine created it
      if (Camunda8Errors.notFound(e)) {
        return null;
      }
      throw e;
    }
    // the cluster answered, so this is settled. An answer with no entries says the instance
    // stands in no call hierarchy, which makes it its own root. Waiting for entries to turn up
    // would wait for something the cluster already said there is none of
    if ((hierarchy == null) || hierarchy.isEmpty()) {
      return processInstanceKey;
    }
    // the chain runs from the root down to the instance which was asked about
    return hierarchy.get(0).getProcessInstanceKey();

  }

}
