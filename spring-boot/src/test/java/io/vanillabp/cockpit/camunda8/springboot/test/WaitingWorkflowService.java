package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow which waits at a timer and holds no user task, so that a test can cancel a running
 * instance.
 * <p>
 * The missing user task is what makes it usable on 8.10. An instance holding a Camunda-managed
 * user task cannot be cancelled on the alpha of that line at all (camunda/camunda#58193), and
 * the cancel listener of a process runs only once every child element has terminated.
 */
@Service
@WorkflowService(workflowAggregateClass = WaitingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = WaitingWorkflowService.BPMN_PROCESS_ID))
public class WaitingWorkflowService {

  /** The process a case of this service is. */
  public static final String BPMN_PROCESS_ID = "WaitingProcess";

  private final ProcessService<WaitingAggregate> processService;

  private final AtomicBoolean theProviderFails = new AtomicBoolean();

  public WaitingWorkflowService(
      final ProcessService<WaitingAggregate> processService) {

    this.processService = processService;

  }

  /**
   * @return The process service, so that a test can start a case
   */
  public ProcessService<WaitingAggregate> processes() {

    return processService;

  }

  /**
   * Makes the next reports of this workflow fail, so that a test can see what a listener job of
   * this extension costs the instance it runs in.
   *
   * @param failing Whether the provider throws
   */
  public void theDetailsProviderFails(
      final boolean failing) {

    theProviderFails.set(failing);

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final WaitingAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    if (theProviderFails.get()) {
      throw new IllegalStateException("the details provider of the test cannot answer");
    }
    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

}
