package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.Map;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow whose user task can be taken away while the case keeps running, so that a test can
 * see what the cockpit hears about a cancelled task on its own.
 * <p>
 * A message interrupts the task. That is the one way a Camunda 8 cluster cancels a user task
 * without cancelling the case, and it happens on every release line.
 */
@Service
@WorkflowService(workflowAggregateClass = InterruptedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = InterruptedWorkflowService.BPMN_PROCESS_ID))
public class InterruptedWorkflowService {

  /** The process a case of this service is. */
  public static final String BPMN_PROCESS_ID = "InterruptedProcess";

  /** The external form reference of the user task, which is its task definition. */
  public static final String TASK_DEFINITION = "review";

  /** The message the boundary event of that user task waits for. */
  public static final String INTERRUPT_MESSAGE = "InterruptTheReview";

  private final ProcessService<InterruptedAggregate> processService;

  public InterruptedWorkflowService(
      final ProcessService<InterruptedAggregate> processService) {

    this.processService = processService;

  }

  /**
   * @return The process service, so that a test can start a case and interrupt its task
   */
  public ProcessService<InterruptedAggregate> processes() {

    return processService;

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails review(
      final InterruptedAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final InterruptedAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

}
