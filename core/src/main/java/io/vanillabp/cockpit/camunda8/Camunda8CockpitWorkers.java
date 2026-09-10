package io.vanillabp.cockpit.camunda8;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;

/**
 * The workers this extension keeps open, one per job type of a workflow module and per Camunda
 * 8 cluster the module was deployed to.
 * <p>
 * A worker asks the cluster for exactly one thing besides the job: the variable the workflow
 * aggregate's id is carried in. Everything else a report needs is read while the outbox entry
 * is dispatched, so a listener job which travels less is a user task which appears sooner.
 * <p>
 * The pipeline starts this extension once per configured Camunda 8 adapter a module was
 * deployed to, and its processing context says which adapter that is - so a start opens the
 * workers of exactly that cluster, subscribing to the job types that cluster's own models carry.
 * See decision 2 in the repository's DECISIONS.md.
 */
public class Camunda8CockpitWorkers {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitWorkers.class);

  private final Camunda8Clients clients;

  private final Camunda8CockpitDeployments deployments;

  private final Camunda8CockpitSettings settings;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * The workers of one workflow module on one cluster.
   *
   * @param adapterId The configured adapter id whose cluster they run against
   * @param workflowModuleId The workflow module they serve
   */
  private record Subscription(
                              String adapterId,
                              String workflowModuleId) {
  }

  private final Map<Subscription, List<JobWorker>> workersBySubscription = new ConcurrentHashMap<>();

  /**
   * @param clients The clusters of the configured Camunda 8 adapters
   * @param deployments What this extension wired
   * @param settings How long a listener job stays locked
   * @param publisher Where an observed event is reported
   */
  public Camunda8CockpitWorkers(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitSettings settings,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.clients = clients;
    this.deployments = deployments;
    this.settings = settings;
    this.publisher = publisher;

  }

  /**
   * Opens the workers of one workflow module on one cluster.
   *
   * @param adapterId The configured adapter id the module started for
   * @param workflowModuleId The workflow module which started
   */
  public synchronized void open(
      final String adapterId,
      final String workflowModuleId) {

    final var subscription = new Subscription(adapterId, workflowModuleId);
    if (workersBySubscription.containsKey(subscription)) {
      return;
    }
    final var listenersByType = deployments.listenersByTypeOf(adapterId, workflowModuleId);
    if (listenersByType.isEmpty()) {
      return;
    }
    final var cluster = clients.of(adapterId);
    final var opened = new LinkedList<JobWorker>();
    try {
      listenersByType
          .forEach((
              listenerType,
              listeners) -> opened.add(open(cluster, workflowModuleId, listenerType, listeners)));
    } catch (final RuntimeException e) {
      // a module which is half subscribed is worse than one which is not subscribed at all:
      // it reports some of what happens and lets the rest of its listener jobs run into an
      // incident, so what was opened is closed again and the start fails
      close(opened);
      throw e;
    }
    // noted only once every worker of the module stands, so that a failed start leaves
    // nothing behind which a later stop would try to close a second time
    workersBySubscription.put(subscription, opened);

  }

  private JobWorker open(
      final Camunda8Clients.Cluster cluster,
      final String workflowModuleId,
      final String listenerType,
      final List<Camunda8CockpitDeployments.WiredListener> listeners) {

    final var variables = new ArrayList<>(
        Camunda8CockpitDeployments.aggregateIdVariablesOf(listeners));
    var builder = cluster
        .client()
        .newWorker()
        .jobType(listenerType)
        .handler(
            new Camunda8CockpitJobHandler(
                cluster.scope(), workflowModuleId, deployments, publisher))
        .timeout(settings.listenerJobTimeout(workflowModuleId, cluster.scope().adapterId()))
        .name("vanillabp-businesscockpit-%s-%s".formatted(cluster.scope().adapterId(), listenerType))
        .fetchVariables(variables);
    builder = withTheAdaptersStreamTimeout(builder, cluster.configuration());
    final var tenantId = cluster.scope().tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      // with 'by-adapter': jobs of a tenant are only delivered to workers subscribing for
      // that tenant
      builder = builder.tenantId(tenantId);
    }
    logger
        .info(
            "Camunda8[{}]: the Business Cockpit opened a worker for '{}' of workflow module '{}', fetching {}",
            cluster.scope().adapterId(), listenerType, workflowModuleId, variables);
    return builder.open();

  }

  /**
   * The one worker setting this extension has to repeat, because the client does not carry it.
   * <p>
   * A worker of this extension polls, streams and waits the way the adapter's own workers do,
   * and almost all of that arrives on its own: whether jobs are streamed, how often a worker
   * polls and how long a request may take are set on the CLIENT while the adapter builds it,
   * and every worker of that client inherits them. Setting them here as well would take that
   * inheritance away, and with it the environment variables which may overrule the configured
   * values on the client - the escape hatch the adapter reports at startup.
   * <p>
   * The stream timeout has no such client-wide setting, so a worker which does not name it
   * streams for as long as the client's default says instead of for as long as the adapter was
   * configured for.
   * <p>
   * The adapter's own metrics are missing altogether: the method which attaches them is
   * package-private in the adapter, so the workers of this extension are counted by the cluster
   * and not by the adapter's counters.
   *
   * @param builder The worker being built
   * @param configuration What the adapter of this cluster was configured with
   * @return The same builder
   */
  static JobWorkerBuilderStep3 withTheAdaptersStreamTimeout(
      final JobWorkerBuilderStep3 builder,
      final Camunda8AdapterConfiguration configuration) {

    final var streamTimeout = configuration.getStreamTimeout();
    return streamTimeout == null
        ? builder
        : builder.streamTimeout(streamTimeout);

  }

  /**
   * Closes the workers of one workflow module on one cluster, in the reverse order they were
   * opened in.
   *
   * @param adapterId The configured adapter id the module is going down for
   * @param workflowModuleId The workflow module which is going down
   */
  public synchronized void close(
      final String adapterId,
      final String workflowModuleId) {

    final var workers = workersBySubscription.remove(new Subscription(adapterId, workflowModuleId));
    if (workers == null) {
      return;
    }
    close(workers);
    logger
        .info(
            "Camunda8[{}]: the Business Cockpit closed its {} worker(s) of workflow module '{}'",
            adapterId, workers.size(), workflowModuleId);

  }

  /**
   * Closes what is open, in the reverse order it was opened in - the last worker to subscribe
   * is the first to stop.
   *
   * @param workers The workers to close
   */
  private static void close(
      final List<JobWorker> workers) {

    final var reversed = new LinkedList<>(workers);
    Collections.reverse(reversed);
    reversed.forEach(JobWorker::close);

  }

}
