package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.Map;

import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A workflow which has one of its steps done by a process of its own.
 * <p>
 * Both processes are served by this one service and share one workflow aggregate, which is how
 * VanillaBP decomposes a process. The cockpit shows the case, so the called process is a step of
 * it and never a case of its own. See decision 3. The user task sits in the CALLED process, which
 * is what makes it worth testing: the workflow the cockpit hangs it on has to be the calling
 * one.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = CallingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = CallingWorkflowService.BPMN_PROCESS_ID),
    secondaryBpmnProcesses = @BpmnProcess(
        bpmnProcessId = CallingWorkflowService.CALLED_BPMN_PROCESS_ID))
public class CallingWorkflowService {

  /** The process a case of this service is. */
  public static final String BPMN_PROCESS_ID = "CallingProcess";

  /** The process one of its steps is done by. */
  public static final String CALLED_BPMN_PROCESS_ID = "CalledProcess";

  /** The external form reference of the user task inside the called process. */
  public static final String TASK_DEFINITION = "handle";

  @Inject
  ProcessService<CallingAggregate> processService;

  /**
   * @return The process service, so that a test can start a case
   */
  public ProcessService<CallingAggregate> processes() {

    return processService;

  }

  /**
   * @param aggregate The workflow aggregate, which is the CALLING workflow's case
   * @param prefilled What the cluster knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails handle(
      final CallingAggregate aggregate,
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
      final CallingAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

}
