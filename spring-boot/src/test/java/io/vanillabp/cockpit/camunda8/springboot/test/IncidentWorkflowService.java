package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow whose details provider fails before it answers.
 * <p>
 * The cockpit's reports are written into an outbox and a details provider runs while such an
 * entry is dispatched, so a provider throwing costs a repetition of that entry and nothing
 * else: the workflow it belongs to has long moved on, and the cluster never learns about it.
 * Version 1 ran the provider inside the listener job and answered a failure with an incident.
 */
@Service
@WorkflowService(workflowAggregateClass = RetriedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = RetriedWorkflowService.BPMN_PROCESS_ID))
public class RetriedWorkflowService {

  /** The BPMN process of this workflow. */
  public static final String BPMN_PROCESS_ID = "RetriedDetailsProcess";

  /** The external form reference of its user task. */
  public static final String TASK_DEFINITION = "retriedApprove";

  /** How often the details provider throws before it answers. */
  public static final int FAILURES_BEFORE_THE_PROVIDER_ANSWERS = 2;

  private final ProcessService<RetriedAggregate> processService;

  private final AtomicInteger attempts = new AtomicInteger();

  public RetriedWorkflowService(
      final ProcessService<RetriedAggregate> processService) {

    this.processService = processService;

  }

  /**
   * @return The process service, so that a test can start this workflow
   */
  public ProcessService<RetriedAggregate> processes() {

    return processService;

  }

  /**
   * @return How often the details provider was called
   */
  public int attempts() {

    return attempts.get();

  }

  /**
   * @param prefilled What the cluster knew about the task
   * @return The enriched details, once this provider is done stumbling
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails retriedApprove(
      final PrefilledUserTaskDetails prefilled) {

    if (attempts.incrementAndGet() <= FAILURES_BEFORE_THE_PROVIDER_ANSWERS) {
      throw new IllegalStateException("the details provider of the test is not ready yet");
    }
    prefilled.setDetails(Map.of("attempts", String.valueOf(attempts.get())));
    return prefilled;

  }

}
