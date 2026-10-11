package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.LinkedHashMap;
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
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The application under test: a workflow with a user task for the cockpit to show, and a details
 * provider which adds to what the cluster reported.
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

  /**
   * The detail which both generations of a details provider write, each with its own value. It
   * says which of the two ran. The version of the deployed process picks between them.
   */
  public static final String SERVED_BY = "servedBy";

  /** What the methods serving version 1 write into {@link #SERVED_BY}. */
  public static final String SERVED_BY_VERSION_ONE = "the methods of version 1";

  /** What the methods serving every later version write into {@link #SERVED_BY}. */
  public static final String SERVED_BY_LATER_VERSIONS = "the methods of the later versions";

  /**
   * The detail which both generations of the user-task provider fill with the process variable
   * <code>customer</code>, read with <code>&#64;TaskParam</code>. No <code>&#64;WorkflowTask</code>
   * method reads that variable, so it is on the listener job only because the provider names it.
   */
  public static final String CUSTOMER_VARIABLE = "customerVariable";

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
   * Matched by the external form reference of the user task and by the version of the deployed
   * process. It adds to what the cluster reported and writes nothing. A details provider only
   * answers a question.
   * <p>
   * A test closes the gate to hold this call open while it changes the same case. This lets
   * Camunda8CockpitIT#aReportCarriesTheStateOfItsEvent show from which moment a report is built.
   * Otherwise the gate is open.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the cluster knew about the task
   * @param event What happened to the task
   * @param customer The process variable <code>customer</code>, where the report carries it
   * @return The very object it was given, which is the common case
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION, version = "1")
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event,
      @TaskParam("customer") final String customer) {

    return approveDetails(aggregate, prefilled, event, customer, SERVED_BY_VERSION_ONE);

  }

  /**
   * The same task, served for every version deployed after the first one.
   * <p>
   * A test uses it to see the version of the deployed process pick a method. The application
   * deploys version 1 while it starts, a test deploys a second version, and the workflow started
   * after that is reported by this method instead of {@link #approve}. The two ranges do not
   * overlap, so one task definition can have two methods.
   * <p>
   * It reports the same as {@link #approve} except for {@link #SERVED_BY}. So every other test
   * reads the same report, whichever version its workflow runs on.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the cluster knew about the task
   * @param event What happened to the task
   * @param customer The process variable <code>customer</code>, where the report carries it
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION, version = ">1")
  public UserTaskDetails approveOfALaterVersion(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event,
      @TaskParam("customer") final String customer) {

    return approveDetails(aggregate, prefilled, event, customer, SERVED_BY_LATER_VERSIONS);

  }

  private UserTaskDetails approveDetails(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      final DetailsEvent.Event event,
      final String customer,
      final String servedBy) {

    gate.passOrWait(aggregate.getId());
    final var details = new LinkedHashMap<String, Object>();
    details.put("customer", aggregate.getCustomer());
    details.put("event", event.name());
    details.put(SERVED_BY, servedBy);
    // a report built from a listener job carries the variable, and so does a report built from
    // the cluster's searchable storage. The parameter is null only where the workflow never set
    // the variable, and then the detail is left out
    if (customer != null) {
      details.put(CUSTOMER_VARIABLE, customer);
    }
    prefilled.setDetails(details);
    prefilled.setCandidateGroups(List.of("approvers"));
    return prefilled;

  }

  /**
   * The workflow of version 1, which is the version this application deploys while it starts.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider(version = "1")
  public WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    return workflowDetails(aggregate, prefilled, SERVED_BY_VERSION_ONE);

  }

  /**
   * The same workflow, served for every version deployed after the first one. It is the
   * counterpart of {@link #approveOfALaterVersion} for the workflow.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the cluster knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider(version = ">1")
  public WorkflowDetails workflowDetailsOfALaterVersion(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    return workflowDetails(aggregate, prefilled, SERVED_BY_LATER_VERSIONS);

  }

  private WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled,
      final String servedBy) {

    // the aggregate id is in there so a test can see WHICH case a report is about. The cluster
    // tells the extension the aggregate id, and the extension loads this aggregate by it. So a
    // report with the id is a report which found its case
    prefilled
        .setDetails(
            Map
                .of(
                    "customer", aggregate.getCustomer(), "aggregateId",
                    String.valueOf(aggregate.getId()), SERVED_BY, servedBy));
    return prefilled;

  }

}
