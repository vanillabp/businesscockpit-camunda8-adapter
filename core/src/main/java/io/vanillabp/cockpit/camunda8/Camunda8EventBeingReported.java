package io.vanillabp.cockpit.camunda8;

import java.util.Optional;
import java.util.function.Supplier;

import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;

/**
 * What one listener job says about the task or the workflow it is about, held for as long as the
 * report of that job is being built.
 * <p>
 * The cockpit builds a report at the moment of the event and asks the BPMS half for the values
 * while it does. That question names a task or a workflow by its identifiers and nothing else, so
 * the answer has to come from somewhere. On Camunda 8 it comes from the job the cluster just
 * handed out: {@link Camunda8CockpitJobHandler} reads the job, leaves what it read here, and calls
 * the cockpit, which calls {@link Camunda8CockpitBridge} back on the very same thread. So the
 * values never leave the call which produced them, and the cluster is not asked about an event it
 * has not written down yet.
 * <p>
 * Held per thread, because that is the length of one report. A worker of this extension runs many
 * jobs at once, each on a thread of its own, and a report which read another job's values would be
 * about another case entirely. What is held is put back the way it was found when the report is
 * done, so a report which somehow starts inside another one leaves that one intact.
 * <p>
 * Every answer here names the task or the workflow it belongs to, and it is given out only to a
 * question about that one. An application's details provider may read a second task while it runs
 * ({@code BusinessCockpitService.getUserTask}), and that read is a question about now rather than
 * about this event. It gets what it asks for, from the cluster, because nothing is held for it.
 * <p>
 * One of these belongs to one configured adapter id, so two clusters cannot read each other's
 * events.
 */
public class Camunda8EventBeingReported {

  /**
   * One task the thread is reporting about.
   *
   * @param userTaskId The task's key, as the cluster handed it out
   * @param values What its job said about it
   */
  private record UserTaskOfTheEvent(
                                    String userTaskId,
                                    UserTaskDetailsPrefill values) {
  }

  /**
   * One workflow the thread is reporting about.
   *
   * @param workflowId The workflow's key, which is the business case rather than the job's own
   *          process instance
   * @param values What its job said about it
   */
  private record WorkflowOfTheEvent(
                                    String workflowId,
                                    WorkflowDetailsPrefill values) {
  }

  private final ThreadLocal<UserTaskOfTheEvent> userTaskBeingReported = new ThreadLocal<>();

  private final ThreadLocal<WorkflowOfTheEvent> workflowBeingReported = new ThreadLocal<>();

  /**
   * Reports one user task with what its listener job said about it.
   *
   * @param <T> What the reporting answers
   * @param userTaskId The task's key
   * @param values What the job said
   * @param report Hands the event to the cockpit, which asks the bridge back
   * @return What the reporting answered
   */
  public <T> T whileReportingTheUserTask(
      final String userTaskId,
      final UserTaskDetailsPrefill values,
      final Supplier<T> report) {

    final var before = userTaskBeingReported.get();
    userTaskBeingReported.set(new UserTaskOfTheEvent(userTaskId, values));
    try {
      return report.get();
    } finally {
      restore(userTaskBeingReported, before);
    }

  }

  /**
   * Reports one workflow with what its listener job said about it.
   *
   * @param <T> What the reporting answers
   * @param workflowId The workflow's key
   * @param values What the job said
   * @param report Hands the event to the cockpit, which asks the bridge back
   * @return What the reporting answered
   */
  public <T> T whileReportingTheWorkflow(
      final String workflowId,
      final WorkflowDetailsPrefill values,
      final Supplier<T> report) {

    final var before = workflowBeingReported.get();
    workflowBeingReported.set(new WorkflowOfTheEvent(workflowId, values));
    try {
      return report.get();
    } finally {
      restore(workflowBeingReported, before);
    }

  }

  /**
   * @param userTaskId The task a report is being built for
   * @return What its listener job said, or empty where this thread reports no event about that
   *         task and the cluster has to be asked instead
   */
  public Optional<UserTaskDetailsPrefill> userTaskValuesOf(
      final String userTaskId) {

    final var reported = userTaskBeingReported.get();
    return (reported != null) && reported.userTaskId().equals(userTaskId)
        ? Optional.of(reported.values())
        : Optional.empty();

  }

  /**
   * @param workflowId The workflow a report is being built for
   * @return What its listener job said, or empty where this thread reports no event about that
   *         workflow and the cluster has to be asked instead
   */
  public Optional<WorkflowDetailsPrefill> workflowValuesOf(
      final String workflowId) {

    final var reported = workflowBeingReported.get();
    return (reported != null) && reported.workflowId().equals(workflowId)
        ? Optional.of(reported.values())
        : Optional.empty();

  }

  /**
   * Puts back what was held before, and holds nothing at all where nothing was held. A thread of a
   * worker serves job after job, and a value left behind would be read by the next one.
   */
  private static <T> void restore(
      final ThreadLocal<T> held,
      final T before) {

    if (before == null) {
      held.remove();
      return;
    }
    held.set(before);

  }

}
