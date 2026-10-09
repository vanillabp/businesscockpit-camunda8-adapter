package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.Map;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.BusinessCockpitService;
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
 * A workflow which has one of its steps done by a process of its own.
 * <p>
 * Both processes are served by this one service and share one workflow aggregate, which is how
 * VanillaBP decomposes a process: the detail sits elsewhere, the business case does not. The
 * cockpit shows the case, so the called process is a step of it and never a case of its own -
 * see decision 3.
 * <p>
 * The user task lives in the CALLED process, which is what makes it interesting: whichever
 * workflow the cockpit hangs that task on has to be the calling one.
 * <p>
 * A second called process is reached by a call activity naming it by an expression. Its user
 * task is served by two details providers, one for the first version of its model and one for
 * every later one. Only the version of the CALLED model picks between them, so a report naming
 * the calling process with that version would pick a provider nobody meant.
 */
@Service
@WorkflowService(workflowAggregateClass = CallingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = CallingWorkflowService.BPMN_PROCESS_ID),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = CallingWorkflowService.CALLED_BPMN_PROCESS_ID), @BpmnProcess(
            bpmnProcessId = CallingWorkflowService.EXPRESSION_CALLED_BPMN_PROCESS_ID)
    })
public class CallingWorkflowService {

  /** The process a case of this service is. */
  public static final String BPMN_PROCESS_ID = "CallingProcess";

  /** The process one of its steps is done by. */
  public static final String CALLED_BPMN_PROCESS_ID = "CalledProcess";

  /** The external form reference of the user task inside the called process. */
  public static final String TASK_DEFINITION = "handle";

  /** The process a call activity reaches by an expression. */
  public static final String EXPRESSION_CALLED_BPMN_PROCESS_ID = "ExpressionCalledProcess";

  /** The external form reference of the user task inside that process. */
  public static final String INSPECT_TASK_DEFINITION = "inspect";

  /** What the providers of that task write the customer into. */
  public static final String INSPECTED = "inspected";

  /** What the provider of the first model of that task writes into {@link #SERVED_BY}. */
  public static final String SERVED_BY_THE_FIRST_MODEL = "the first model";

  /** What the provider of every later model of that task writes into {@link #SERVED_BY}. */
  public static final String SERVED_BY_LATER_MODELS = "the later models";

  /** Which provider of that task ran. */
  public static final String SERVED_BY = "servedBy";

  private final ProcessService<CallingAggregate> processService;

  private final BusinessCockpitService<CallingAggregate> businessCockpitService;

  public CallingWorkflowService(
      final ProcessService<CallingAggregate> processService,
      final BusinessCockpitService<CallingAggregate> businessCockpitService) {

    this.processService = processService;
    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of a case
   */
  public BusinessCockpitService<CallingAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * @return The process service, so that a test can start a case
   */
  public ProcessService<CallingAggregate> processes() {

    return processService;

  }

  /**
   * The provider of the user task the called process holds.
   *
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
   * The provider of the user task in the process reached by an expression, for the first version
   * of that model.
   *
   * @param aggregate The workflow aggregate, which is the CALLING workflow's case
   * @param prefilled What the cluster knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = INSPECT_TASK_DEFINITION, version = "1")
  public UserTaskDetails inspect(
      final CallingAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled
        .setDetails(Map.of(INSPECTED, aggregate.getCustomer(), SERVED_BY, SERVED_BY_THE_FIRST_MODEL));
    return prefilled;

  }

  /**
   * The same task, for every later version of that model.
   *
   * @param aggregate The workflow aggregate, which is the CALLING workflow's case
   * @param prefilled What the cluster knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = INSPECT_TASK_DEFINITION, version = ">1")
  public UserTaskDetails inspectLater(
      final CallingAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of(INSPECTED, aggregate.getCustomer(), SERVED_BY, SERVED_BY_LATER_MODELS));
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
