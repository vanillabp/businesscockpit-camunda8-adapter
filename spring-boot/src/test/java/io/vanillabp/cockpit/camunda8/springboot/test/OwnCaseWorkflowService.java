package io.vanillabp.cockpit.camunda8.springboot.test;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * A process which the calling workflow starts by a call activity, and which is a case of its own
 * because it works on a workflow aggregate of its own.
 * <p>
 * The cluster still copies the caller's variables into it, the caller's aggregate id among them.
 * So a search for the caller's tasks by that id alone would find the task of this process, and
 * the cockpit would count it twice: once for each case.
 */
@Service
@WorkflowService(workflowAggregateClass = OwnCaseAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = OwnCaseWorkflowService.BPMN_PROCESS_ID))
public class OwnCaseWorkflowService {

  /** The process a case of this service is. */
  public static final String BPMN_PROCESS_ID = "OwnCaseProcess";

  /**
   * Names the case a call activity started. VanillaBP did not start it, so the application
   * names it, out of the caller's id the cluster copied into it.
   *
   * @param callersId The id of the calling case
   * @return The case of its own
   */
  @WorkflowStartedByBpms
  public OwnCaseAggregate aCaseOfItsOwn(
      @TaskParam("id") final String callersId) {

    final var aggregate = new OwnCaseAggregate();
    aggregate.setCaseNumber("own-"
        + callersId);
    return aggregate;

  }

}
