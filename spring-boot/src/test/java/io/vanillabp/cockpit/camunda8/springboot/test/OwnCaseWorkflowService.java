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

  /** The external form reference of its user task. */
  public static final String TASK_DEFINITION = "check";

  /** What both providers write the case number into, so a test finds the reports of a case. */
  public static final String OWN_CASE = "ownCase";

  private final BusinessCockpitService<OwnCaseAggregate> businessCockpitService;

  public OwnCaseWorkflowService(
      final BusinessCockpitService<OwnCaseAggregate> businessCockpitService) {

    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of a case
   */
  public BusinessCockpitService<OwnCaseAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final OwnCaseAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of(OWN_CASE, aggregate.getCaseNumber()));
    return prefilled;

  }

  /**
   * @param aggregate The workflow aggregate, which is this case's and not the caller's
   * @param prefilled What the cluster knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails check(
      final OwnCaseAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of(OWN_CASE, aggregate.getCaseNumber()));
    return prefilled;

  }

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
