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
 * The cockpit builds its report at the moment of the event, which on Camunda 8 is inside the
 * listener job of the task. A provider which throws therefore fails that job, and a listener of
 * this extension carries no retries, so the cluster raises an incident and the transition waits
 * for somebody to look at it. That is meant: only reading happens on this way, but what is read
 * has to be right, and a defect which repetitions hide is a defect nobody fixes. See decision 8
 * in the repository's DECISIONS.md.
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
