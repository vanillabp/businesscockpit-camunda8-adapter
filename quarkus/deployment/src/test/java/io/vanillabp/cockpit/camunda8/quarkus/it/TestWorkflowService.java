package io.vanillabp.cockpit.camunda8.quarkus.it;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application under test: a workflow whose user task the cockpit is to show, with a
 * details provider which enriches what the cluster reported.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = TestWorkflowService.BPMN_PROCESS_ID))
public class TestWorkflowService {

  /** The BPMN process of the test. */
  public static final String BPMN_PROCESS_ID = "CockpitProcess";

  /** The external form reference of the user task, which is its task definition. */
  public static final String TASK_DEFINITION = "approve";

  /** The BPMN element id of the user task. */
  public static final String BPMN_TASK_ID = "Approve";

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  /**
   * The detail both generations of a details provider write, each with its own value: it says
   * which of the two ran, and the version of the deployed process is what picks between them.
   */
  public static final String SERVED_BY = "servedBy";

  /** What the methods serving version 1 write into {@link #SERVED_BY}. */
  public static final String SERVED_BY_VERSION_ONE = "the methods of version 1";

  /** What the methods serving every later version write into {@link #SERVED_BY}. */
  public static final String SERVED_BY_LATER_VERSIONS = "the methods of the later versions";

  /**
   * The detail both generations of the user-task provider fill with the process variable
   * <code>customer</code>, read with <code>&#64;TaskParam</code>. No <code>&#64;WorkflowTask</code>
   * method reads that variable, so only the provider's own name brings it onto the listener job.
   */
  public static final String CUSTOMER_VARIABLE = "customerVariable";

  @Inject
  ProcessService<TestAggregate> processService;

  @Inject
  BusinessCockpitService<TestAggregate> businessCockpitService;

  /**
   * @return The process service, so that a test can start a workflow
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
   * It is here so that a test can see the version of the deployed process pick a method: the
   * application deploys version 1 while it starts, a test deploys a second version, and the
   * workflow started after that is reported by this method rather than by {@link #approve}. The
   * two ranges do not overlap, which is what lets one task definition have two methods.
   * <p>
   * What it reports is what {@link #approve} reports apart from {@link #SERVED_BY}, so that
   * every other test reads the same report whichever version its workflow happens to run on.
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

    aggregate.setNote(APPROVE_NOTE);
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
   * The same workflow, served for every version deployed after the first one - the counterpart
   * of {@link #approveOfALaterVersion} for the workflow.
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

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer(), SERVED_BY, servedBy));
    return prefilled;

  }

}
