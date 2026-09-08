package io.vanillabp.cockpit.camunda8;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.client.api.worker.JobWorker;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;

/**
 * The workers this extension keeps open, one per job type of a workflow module and per Camunda
 * 8 cluster the module was deployed to.
 * <p>
 * A worker asks the cluster for exactly one thing besides the job: the variable the workflow
 * aggregate's id is carried in. Everything else a report needs is read while the outbox entry
 * is dispatched, so a listener job which travels less is a user task which appears sooner.
 * <p>
 * They are opened once per workflow module rather than once per cluster the pipeline calls this
 * extension for, because the pipeline says which module is starting but not which cluster - see
 * decision 2 in the repository's DECISIONS.md.
 */
public class Camunda8CockpitWorkers {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitWorkers.class);

  private final Camunda8Clients clients;

  private final Camunda8CockpitDeployments deployments;

  private final Camunda8CockpitSettings settings;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final Map<String, List<JobWorker>> workersByWorkflowModule = new ConcurrentHashMap<>();

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
   * Opens the workers of one workflow module, once. The deployment pipeline calls this
   * extension once per Camunda 8 adapter the module was deployed to, and every one of those
   * calls means the same set of workers.
   *
   * @param workflowModuleId The workflow module which started
   */
  public synchronized void open(
      final String workflowModuleId) {

    if (workersByWorkflowModule.containsKey(workflowModuleId)) {
      return;
    }
    final var listenersByType = deployments.listenersByTypeOf(workflowModuleId);
    if (listenersByType.isEmpty()) {
      return;
    }
    final var opened = new LinkedList<JobWorker>();
    workersByWorkflowModule.put(workflowModuleId, opened);

    clients
        .clustersHolding(workflowModuleId)
        .forEach(cluster -> listenersByType.forEach((
            listenerType,
            listeners) -> opened
                .add(open(cluster, workflowModuleId, listenerType, listeners))));

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
   * Closes the workers of one workflow module, in the reverse order they were opened in.
   *
   * @param workflowModuleId The workflow module which is going down
   */
  public synchronized void close(
      final String workflowModuleId) {

    final var workers = workersByWorkflowModule.remove(workflowModuleId);
    if (workers == null) {
      return;
    }
    final var reversed = new LinkedList<>(workers);
    java.util.Collections.reverse(reversed);
    reversed.forEach(JobWorker::close);
    logger
        .info(
            "The Business Cockpit closed its {} Camunda 8 worker(s) of workflow module '{}'",
            workers.size(), workflowModuleId);

  }

}
