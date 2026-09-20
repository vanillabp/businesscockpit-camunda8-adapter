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
 * The order is the Business Cockpit's own, and {@link BusinessCockpitWiringService#ORDER} says
 * what that number means. The cockpit's listeners sit behind VanillaBP's own on every element
 * they share. That is not the number's doing: the pipeline calls the adapter before it calls any
 * extension.
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
      // A listener carries no retries, so a job nobody serves stops the workflow where it sits.
      // A BPMN process which no @WorkflowService class claims has no workflow aggregate, so
      // there is nothing the cockpit could report a case for, and it gets no listener either.
      // See decision 5 in the repository's DECISIONS.md
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
        adapterId, workflowModuleId, filename, bpmnProcessId, model, process.get(), aggregateIdName);

  }

  /**
   * Adds what makes the cluster say that a workflow of this process began, that it ended and
   * that somebody cancelled it: an <code>end</code> listener on every start event, one at the
   * process itself, and on 8.10 and above a <code>cancel</code> listener at the process.
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
    // read once, and read here: a job names the process it comes from and never what the
    // process is called, while a report which produced no title of its own shows that name
    final var bpmnProcessName = nameOrIdentifier(process.getName(), bpmnProcessId);

    Camunda8CockpitListeners
        .startEventsOf(process)
        .forEach(startEvent -> {
          Camunda8CockpitListeners.addStartEventListener(startEvent, listenerType);
          deployments
              .register(
                  adapterId,
                  workflowModuleId,
                  new WiredListener(
                      listenerType, scopedBpmnProcessId, bpmnProcessId, startEvent.getId(), nameOrIdentifier(
                          startEvent.getName(), startEvent.getId()), bpmnProcessName, aggregateIdName));
        });

    Camunda8CockpitListeners.addProcessListener(process, listenerType);
    // and what makes it say that a workflow was cancelled, which only 8.10 and above can say.
    // It carries the same job type, so the worker of the end listener beside it receives that
    // job too and nothing is registered a second time
    Camunda8CockpitListeners.addProcessCancelListener(process, listenerType);
    deployments
        .register(
            adapterId,
            workflowModuleId,
            new WiredListener(
                listenerType, scopedBpmnProcessId, bpmnProcessId, scopedBpmnProcessId, bpmnProcessName, bpmnProcessName, aggregateIdName));

  }

  /**
   * Adds what makes the cluster say what happened to a user task of this process, to every user
   * task the cluster manages itself. A user task served by a job worker is none of this
   * extension's business: VanillaBP delivers it like any other task.
   * <p>
   * Which user tasks those are, and what each of them is called, is not decided here. The
   * adapter's own deployment path decides it, and
   * {@link Camunda8TaskWiring#readUserTasksOf} is the reading half of that path. A task the
   * adapter wired is a task the cockpit reports, under the same task definition. That definition
   * is the external form reference, which is what a <code>&#64;UserTaskDetailsProvider</code>
   * method is matched by and what Version 1 named its listeners after.
   * <p>
   * The reading half is the one an extension asks. The other half writes the adapter's own
   * listeners into the model, and that is the adapter's work rather than the cockpit's. The
   * adapter has done it by the time this runs, because the pipeline calls the adapter first.
   * Both halves return the same list and refuse the same user task, so reading loses nothing.
   * A user task without an external form reference has ended the deployment already.
   * <p>
   * There is a third method, for a model the cluster already holds. It is wrong here. It reads
   * a Version 1 formKey as a task definition, and that would produce a listener type Version 1
   * never wrote.
   *
   * @param adapterId The configured adapter id whose models are being wired
   * @param workflowModuleId The workflow module
   * @param filename The file being wired, for the message a refused user task produces
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @param model The model of the file being wired
   * @param process The BPMN process element, carrying the id the cluster will know
   * @param aggregateIdName The variable the workflow aggregate's id is carried in
   */
  private void reportTheUserTasks(
      final String adapterId,
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Process process,
      final String aggregateIdName) {

    final var scopedBpmnProcessId = process.getId();
    Camunda8TaskWiring
        .readUserTasksOf(model, scopedBpmnProcessId, workflowModuleId, filename)
        .forEach(userTask -> {
          final var listenerType = Camunda8CockpitListeners
              .listenerTypeOf(userTask.externalFormReference());
          final var element = model.getModelElementById(userTask.activityId()) instanceof UserTask found
              ? found
              : null;
          if (element != null) {
            Camunda8CockpitListeners.addUserTaskListeners(element, listenerType);
          }
          deployments
              .register(
                  adapterId,
                  workflowModuleId,
                  new WiredListener(
                      listenerType, scopedBpmnProcessId, bpmnProcessId, userTask.activityId(), nameOrIdentifier(
                          element == null
                              ? null
                              : element.getName(),
                          userTask.activityId()), nameOrIdentifier(process.getName(), bpmnProcessId), aggregateIdName));
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
   * What a report shows where neither a template nor a details provider produced a title.
   * <p>
   * It is the BPMN name of the element, and where the model carries none it is the identifier
   * the application wrote. An identifier says more on a screen than an empty line, and it is
   * also what the cluster's searchable storage answers for such an element, so a report does not
   * change its title depending on where it was built. The plain identifier, never the one the
   * cluster knows: name-clash avoidance is nothing a person reading the cockpit has to see.
   *
   * @param name What the model says, which may be nothing
   * @param identifier The identifier to fall back to
   * @return The name to report
   */
  private static String nameOrIdentifier(
      final String name,
      final String identifier) {

    return (name == null) || name.isBlank()
        ? identifier
        : name;

  }

  /**
   * The BPMN process this call is about, as it stands in the model.
   * <p>
   * The pipeline hands over the process id the application wrote, while the model already
   * carries the identifier the cluster will know, because name-clash avoidance rewrote it before
   * any wiring ran. Which of the two spellings this model uses is decided by the adapter it was
   * prepared for, and the processing context says which adapter that is. So the id is asked of
   * that one adapter's scope. Trying every configured adapter's spelling instead stops answering
   * the moment two of them avoid name clashes differently.
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
