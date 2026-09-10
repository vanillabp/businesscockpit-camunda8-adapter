package io.vanillabp.cockpit.camunda8;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitDeployments.WiredListener;
import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * The Camunda 8 half of the Business Cockpit in VanillaBP's deployment pipeline.
 * <p>
 * It declares the Camunda 8 adapter's model and processing-context types, so it takes part in
 * the deployment of a workflow module only where that module runs on Camunda 8. What it does
 * there is add the listeners which make a cluster say what it is doing, remember where it put
 * them, and open the workers those listeners hand their jobs to.
 * <p>
 * The order is the Business Cockpit's own, the last one, so whatever an adapter or another
 * extension does to a model has happened by the time this runs - which is also what puts the
 * cockpit's listeners behind VanillaBP's own on every element they share.
 */
public class Camunda8CockpitWiring implements ExtensionWiringService<BpmnModelInstance, Camunda8ProcessingContext> {

  private static final Logger logger = LoggerFactory.getLogger(Camunda8CockpitWiring.class);

  private final Camunda8Clients clients;

  private final Camunda8CockpitDeployments deployments;

  private final Camunda8CockpitWorkers workers;

  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * @param clients The clusters of the configured Camunda 8 adapters
   * @param deployments Where what was wired is remembered
   * @param workers The workers serving the listeners
   * @param workflowTaskWiring VanillaBP's registry of what the application declared, which is
   *          what names the workflow aggregate's id variable
   */
  public Camunda8CockpitWiring(
      final Camunda8Clients clients,
      final Camunda8CockpitDeployments deployments,
      final Camunda8CockpitWorkers workers,
      final WorkflowTaskWiring workflowTaskWiring) {

    this.clients = clients;
    this.deployments = deployments;
    this.workers = workers;
    this.workflowTaskWiring = workflowTaskWiring;

  }

  @Override
  public Class<BpmnModelInstance> getModelType() {

    return BpmnModelInstance.class;

  }

  @Override
  public Class<Camunda8ProcessingContext> getProcessContextType() {

    return Camunda8ProcessingContext.class;

  }

  @Override
  public int getOrder() {

    return BusinessCockpitWiringService.ORDER;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda8ProcessingContext context) {

    final var adapterId = context.getAdapterId();
    final var aggregateIdName = aggregateIdNameOf(workflowModuleId, bpmnProcessId);
    if (aggregateIdName == null) {
      // A listener carries no retries, so an unserved job stops the workflow where it sits.
      // A BPMN process no @WorkflowService class claims has no workflow aggregate, therefore
      // nothing the cockpit could report a case for, and therefore no listener either - see
      // decision 5 in the repository's DECISIONS.md
      logger
          .debug(
              "Camunda8: the Business Cockpit adds no listeners to BPMN process '{}' of workflow module '{}' (file '{}'): no workflow aggregate of this application claims it",
              bpmnProcessId, workflowModuleId, filename);
      return;
    }

    final var process = processInModel(model, adapterId, workflowModuleId, bpmnProcessId);
    if (process.isEmpty()) {
      logger
          .debug(
              "Camunda8[{}]: the Business Cockpit found no BPMN process '{}' in file '{}' of workflow module '{}' under the identifier this adapter deploys it as",
              adapterId, bpmnProcessId, filename, workflowModuleId);
      return;
    }
    reportTheWorkflow(adapterId, workflowModuleId, bpmnProcessId, process.get(), aggregateIdName);
    reportTheUserTasks(
        adapterId, workflowModuleId, filename, bpmnProcessId, model, process.get().getId(), aggregateIdName);

  }

  /**
   * Adds what makes the cluster say that a workflow of this process began and that it ended: an
   * <code>end</code> listener on every start event, and one at the process itself.
   *
   * @param adapterId The configured adapter id whose models are being wired
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @param process The BPMN process element, carrying the id the cluster will know
   * @param aggregateIdName The variable the workflow aggregate's id is carried in
   */
  private void reportTheWorkflow(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Process process,
      final String aggregateIdName) {

    final var scopedBpmnProcessId = process.getId();
    final var listenerType = Camunda8CockpitListeners.listenerTypeOf(scopedBpmnProcessId);

    Camunda8CockpitListeners
        .startEventsOf(process)
        .forEach(startEvent -> {
          Camunda8CockpitListeners.addStartEventListener(startEvent, listenerType);
          deployments
              .register(
                  adapterId,
                  workflowModuleId,
                  new WiredListener(
                      listenerType, scopedBpmnProcessId, bpmnProcessId, startEvent.getId(), aggregateIdName));
        });

    Camunda8CockpitListeners.addProcessListener(process, listenerType);
    deployments
        .register(
            adapterId,
            workflowModuleId,
            new WiredListener(
                listenerType, scopedBpmnProcessId, bpmnProcessId, scopedBpmnProcessId, aggregateIdName));

  }

  /**
   * Adds what makes the cluster say what happened to a user task of this process, to every user
   * task the cluster manages itself. A user task served by a job worker is none of this
   * extension's business: VanillaBP delivers it like any other task.
   * <p>
   * Which user tasks those are, and what each of them is called, is not decided here. The
   * adapter's own deployment path decides it, and this is that very method: a task the adapter
   * wired is a task the cockpit reports, under the same task definition - the external form
   * reference, which is what a <code>&#64;UserTaskDetailsProvider</code> method is matched by
   * and what Version 1 named its listeners after. Reading the model with the method meant for
   * models a cluster already holds would take a Version 1 formKey as a task definition and
   * produce a listener type Version 1 never wrote.
   * <p>
   * Calling it a second time adds nothing: the adapter ran before this extension and its
   * listeners are already in the model, which is what that method checks before it inserts
   * anything. A user task it would refuse has already ended the deployment by then.
   *
   * @param adapterId The configured adapter id whose models are being wired
   * @param workflowModuleId The workflow module
   * @param filename The file being wired, for the message a refused user task produces
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @param model The model of the file being wired
   * @param scopedBpmnProcessId The process id the cluster will know
   * @param aggregateIdName The variable the workflow aggregate's id is carried in
   */
  private void reportTheUserTasks(
      final String adapterId,
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final String scopedBpmnProcessId,
      final String aggregateIdName) {

    Camunda8TaskWiring
        .userTasksOf(model, scopedBpmnProcessId, workflowModuleId, filename)
        .forEach(userTask -> {
          final var listenerType = Camunda8CockpitListeners
              .listenerTypeOf(userTask.externalFormReference());
          if (model.getModelElementById(userTask.activityId()) instanceof UserTask element) {
            Camunda8CockpitListeners.addUserTaskListeners(element, listenerType);
          }
          deployments
              .register(
                  adapterId,
                  workflowModuleId,
                  new WiredListener(
                      listenerType, scopedBpmnProcessId, bpmnProcessId, userTask.activityId(), aggregateIdName));
        });

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    workers.open(bpmsProcessingContext.getAdapterId(), workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    workers.close(bpmsProcessingContext.getAdapterId(), workflowModuleId);

  }

  /**
   * The BPMN process this call is about, as it stands in the model.
   * <p>
   * The pipeline hands over the process id the application wrote, while the model already
   * carries the identifier the cluster will know - name-clash avoidance rewrote it before any
   * wiring ran. Which of the two spellings this model uses is decided by the adapter it was
   * prepared for, and the processing context says which adapter that is, so the id is asked of
   * that one adapter's scope. Trying every configured adapter's spelling instead stops
   * answering the moment two of them avoid name clashes differently.
   */
  private Optional<Process> processInModel(
      final BpmnModelInstance model,
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return Camunda8CockpitListeners
        .processOf(
            model,
            clients.of(adapterId).scope().scopedProcessIdOf(workflowModuleId, bpmnProcessId));

  }

  /**
   * The variable a BPMN process carries the workflow aggregate's id in.
   *
   * @return The name, or <code>null</code> where no workflow aggregate of this application
   *         claims the process
   */
  private String aggregateIdNameOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    try {
      return workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId);
    } catch (final RuntimeException e) {
      logger
          .debug(
              "Camunda8: the BPMN process '{}' of workflow module '{}' has no known workflow aggregate",
              bpmnProcessId, workflowModuleId, e);
      return null;
    }

  }

}
