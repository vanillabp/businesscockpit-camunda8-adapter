package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.BusinessCockpitService;
import io.vanillabp.spi.cockpit.details.DetailsEvent;
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
 * The application under test: a workflow whose user task the cockpit is to show, with a
 * details provider which enriches what the cluster reported and writes into the aggregate
 * while doing so.
 */
@Service
@WorkflowService(workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = TestWorkflowService.BPMN_PROCESS_ID))
public class TestWorkflowService {

  /** The BPMN process of the test. */
  public static final String BPMN_PROCESS_ID = "CockpitProcess";

  /** The external form reference of the user task, which is its task definition. */
  public static final String TASK_DEFINITION = "approve";

  /** The BPMN element id of the user task. */
  public static final String BPMN_TASK_ID = "Approve";

  /** The message the second start event of the process waits for. */
  public static final String START_MESSAGE = "StartCockpitProcess";

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  private final ProcessService<TestAggregate> processService;

  private final BusinessCockpitService<TestAggregate> businessCockpitService;

  private final DetailsProviderGate gate;

  public TestWorkflowService(
      final ProcessService<TestAggregate> processService,
      final BusinessCockpitService<TestAggregate> businessCockpitService,
      final DetailsProviderGate gate) {

    this.processService = processService;
    this.businessCockpitService = businessCockpitService;
    this.gate = gate;

  }

  /**
   * @return The process service, so that a test can start a workflow and complete its task
   */
  public ProcessService<TestAggregate> processes() {

    return processService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of its own
   */
  public BusinessCockpitService<TestAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * Matched by the external form reference of the user task. It enriches what the cluster
   * reported, and for the one case a test asks for it also writes into the workflow aggregate,
   * which a details provider is allowed to do and which makes it a second writer of that case.
   * <p>
   * No test reads the note any more. The write is there because
   * Camunda8CockpitIT#aChangeMadeWhileADetailsProviderHoldsTheCaseSurvives needs a second writer,
   * and it is asked for per case because a provider writes into whatever transaction ran it - see
   * {@link DetailsProviderGate#letTheProviderWriteOnto(Long)} for what writing onto every case did
   * to the tests which only read.
   * <p>
   * The gate is what a test closes to hold this call open while it changes the same case, so
   * that the two writers meet at a fixed point rather than by chance. It is open otherwise.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the cluster knew about the task
   * @param event What happened to the task
   * @return The very object it was given, which is the common case
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event) {

    gate.passOrWait(aggregate.getId());
    if (gate.mayWriteOnto(aggregate.getId())) {
      aggregate.setNote(APPROVE_NOTE);
    }
    prefilled.setDetails(Map.of("customer", aggregate.getCustomer(), "event", event.name()));
    prefilled.setCandidateGroups(List.of("approvers"));
    return prefilled;

  }

  /**
   * The one provider a BPMN process may have for its workflow.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    // the aggregate id is in there so that a test can see WHICH case a report is about: the
    // cluster tells the extension the aggregate id and the extension loads this very aggregate
    // by it, so a report carrying it is a report which found its case
    prefilled
        .setDetails(
            Map
                .of(
                    "customer", aggregate.getCustomer(), "aggregateId",
                    String.valueOf(aggregate.getId())));
    return prefilled;

  }

}
