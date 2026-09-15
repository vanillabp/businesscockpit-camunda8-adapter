package io.vanillabp.cockpit.camunda8;

import io.camunda.client.api.response.ActivatedJob;

/**
 * Which workflow a job belongs to, when the job sits in a called process. This is the 8.10
 * variant.
 * <p>
 * The cockpit shows business cases, and a called process is a step of one rather than a case of
 * its own - see decision 3 in the repository's DECISIONS.md. So every report has to know the
 * root of the hierarchy its job sits in.
 * <p>
 * Since 8.9 the job carries it: the cluster puts the root process instance key into the job it
 * hands to a worker, and a root instance reports itself or nothing at all. Nothing is asked of
 * the cluster here, which is why the 8.8 variant of this class exists and is the expensive one.
 */
final class Camunda8CallHierarchy {

  /**
   * @param cluster The cluster the job came from, which this line does not need to ask
   */
  Camunda8CallHierarchy(
      final Camunda8Clients.Cluster cluster) {
  }

  /**
   * @param job The listener job a report is about
   * @return The process instance the whole hierarchy hangs below, or <code>null</code> where the
   *         job's own instance is that one
   */
  Long rootProcessInstanceKeyOf(
      final ActivatedJob job) {

    final var root = job.getRootProcessInstanceKey();
    // a root instance is reported either as having none or as being its own, and both mean the
    // job belongs to the workflow it sits in
    if ((root == null) || (root == job.getProcessInstanceKey())) {
      return null;
    }
    return root;

  }

}
