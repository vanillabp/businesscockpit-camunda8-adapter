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
import io.vanillabp.camunda8.client.Camunda8ClientFactory.WorkflowModuleShutdownRegistration;
import io.vanillabp.camunda8.client.Camunda8Workers;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;

/**
 * The workers this extension keeps open, one per job type of a workflow module and per Camunda
 * 8 cluster the module was deployed to.
 * <p>
 * A worker asks the cluster for exactly one variable besides the job: the one the workflow
 * aggregate's id is carried in. Everything else a report needs travels on the job itself, so a
 * job which carries fewer variables is a user task which appears sooner.
 * <p>
 * The pipeline starts this extension once per configured Camunda 8 adapter a module was
 * deployed to, and its processing context says which adapter that is. So a start opens the
 * workers of exactly that cluster, and they subscribe to the job types that cluster's own
 * models carry. See decision 2 in the repository's DECISIONS.md.
 * <p>
 * A worker opened here is set up the way the adapter sets up its own, so an operator reads one
 * kind of worker rather than two. The counters of these workers therefore appear next to the
 * adapter's, under the adapter id they belong to and under the listener type as their job type.
 * <p>
 * These workers usually stop because the pipeline stops workflow processing of their module.
 * Where a shutdown never gets that far, the adapter's client factory closes them. It is told
 * they are open, and it stops what is still open before it closes the client.
 */
public class Camunda8CockpitWorkers {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitWorkers.class);

  private final Camunda8Clients clients;

  private final Camunda8CockpitDeployments deployments;

  private final Camunda8CockpitSettings settings;

  /**
   * Where the job counters of these workers go. An application without a metrics backend hands
   * in {@link Camunda8Metrics#NONE}, which measures nothing.
   */
  private final Camunda8Metrics metrics;

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

  /**
   * What one workflow module of one cluster has open.
   *
   * @param workers Its workers, in the order they were opened in
   * @param removeTheShutdownHook What takes the hook of these workers off the adapter's client
   *          factory again once they are closed
   */
  private record OpenWorkers(
                             List<JobWorker> workers,
                             WorkflowModuleShutdownRegistration removeTheShutdownHook) {
  }

  private final Map<Subscription, OpenWorkers> openWorkers = new ConcurrentHashMap<>();

  /**
   * @param clients The clusters of the configured Camunda 8 adapters
   * @param deployments What this extension wired
   * @param settings How long a listener job stays locked
   * @param metrics Where the job counters of these workers go
   * @param publisher Where an observed event is reported
   */
  public Camunda8CockpitWorkers(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitSettings settings,
      final Camunda8Metrics metrics,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.clients = clients;
    this.deployments = deployments;
    this.settings = settings;
    this.metrics = metrics;
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
    if (openWorkers.containsKey(subscription)) {
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
      // a module which is half subscribed is worse than one which is not subscribed at all. It
      // reports some of what happens and lets the rest of its listener jobs run into an
      // incident. So what was opened is closed again and the start fails
      close(opened);
      throw e;
    }
    // Where a shutdown path never reaches this extension, nothing else closes these workers and
    // the client goes down under them. The listener jobs they are serving are cut off, and the
    // activation requests they parked at the cluster stay parked. So the adapter's client
    // factory is told they are open, and it closes them before its client if it has to. The
    // hook belongs to this extension alone. The adapter's own hook is registered beside it, and
    // neither replaces the other
    final var removeTheShutdownHook = cluster
        .factory()
        .workflowModuleStarted(workflowModuleId, () -> close(adapterId, workflowModuleId));
    // noted only once every worker of the module stands, so that a failed start leaves
    // nothing behind which a later stop would try to close a second time
    openWorkers.put(subscription, new OpenWorkers(opened, removeTheShutdownHook));

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
                cluster, workflowModuleId, deployments, publisher))
        .timeout(settings.listenerJobTimeout(workflowModuleId, cluster.scope().adapterId()))
        .name("vanillabp-businesscockpit-%s-%s".formatted(cluster.scope().adapterId(), listenerType))
        .fetchVariables(variables);
    // the two things a worker cannot inherit from the client the adapter built, and therefore
    // the only two this extension repeats: the stream timeout, which the client has no setting
    // for, and the job counters, which exist per worker because they carry the job type.
    // Whether jobs are streamed, how often a worker polls and how long a request may take are
    // set on the client, where an environment variable can still overrule them. Naming any of
    // them here would take that way out away without saying so
    builder = Camunda8Workers
        .applyWorkerOptions(
            builder, cluster.scope().adapterId(), listenerType, cluster.configuration(), metrics);
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
   * Closes the workers of one workflow module on one cluster, in the reverse order they were
   * opened in.
   *
   * @param adapterId The configured adapter id the module is going down for
   * @param workflowModuleId The workflow module which is going down
   */
  public synchronized void close(
      final String adapterId,
      final String workflowModuleId) {

    final var open = openWorkers.remove(new Subscription(adapterId, workflowModuleId));
    if (open == null) {
      return;
    }
    // before the workers, and only this extension's hook. One left behind would point at
    // workers which are already closed, and the adapter would call it while it shuts down
    open.removeTheShutdownHook().close();
    close(open.workers());
    logger
        .info(
            "Camunda8[{}]: the Business Cockpit closed its {} worker(s) of workflow module '{}'",
            adapterId, open.workers().size(), workflowModuleId);

  }

  /**
   * Closes what is open, in the reverse order it was opened in. The last worker to subscribe is
   * the first to stop.
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
