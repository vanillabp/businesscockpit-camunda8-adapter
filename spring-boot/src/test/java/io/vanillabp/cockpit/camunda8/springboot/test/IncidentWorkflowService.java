package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow whose details provider always fails.
 * <p>
 * The cockpit builds its report at the moment of the event. On Camunda 8 that is inside the
 * listener job of the task. So a provider which throws fails that job. A listener of this
 * extension has no retries, so the cluster raises an incident, and the transition waits for
 * somebody to look at it. This is intended. This path only reads, but what it reads has to be
 * right, and nobody fixes a defect which retries hide. See decision 8 in the repository's
 * DECISIONS.md.
 */
@Service
@WorkflowService(workflowAggregateClass = IncidentAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = IncidentWorkflowService.BPMN_PROCESS_ID))
public class IncidentWorkflowService {

  /** The BPMN process of this workflow. */
  public static final String BPMN_PROCESS_ID = "IncidentDetailsProcess";

  /** The external form reference of its user task. */
  public static final String TASK_DEFINITION = "incidentApprove";

  private final ProcessService<IncidentAggregate> processService;

  private final AtomicInteger attempts = new AtomicInteger();

  public IncidentWorkflowService(
      final ProcessService<IncidentAggregate> processService) {

    this.processService = processService;

  }

  /**
   * @return The process service, so that a test can start this workflow
   */
  public ProcessService<IncidentAggregate> processes() {

    return processService;

  }

  /**
   * @return How often the details provider was called
   */
  public int attempts() {

    return attempts.get();

  }

  /**
   * @param prefilled What the cluster said about the task
   * @return Never anything: this provider is the defect the test is about
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails incidentApprove(
      final PrefilledUserTaskDetails prefilled) {

    attempts.incrementAndGet();
    throw new IllegalStateException("the details provider of the test cannot answer");

  }

}
