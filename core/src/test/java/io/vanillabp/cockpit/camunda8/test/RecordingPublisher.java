package io.vanillabp.cockpit.camunda8.test;

import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * The platform-neutral half of the extension, played by the test: it writes nothing and
 * remembers what it was told.
 */
public class RecordingPublisher implements BusinessCockpitEventPublisher {

  /**
   * One reported user-task event.
   *
   * @param userTask What it is about
   * @param kind What happened
   * @param bpmsEventId What the cluster called the event
   * @param transaction Which transaction the entry was asked for
   */
  public record UserTaskEvent(
                              UserTaskReference userTask,
                              UserTaskEventKind kind,
                              String bpmsEventId,
                              EventTransaction transaction) {
  }

  /**
   * One reported workflow event.
   *
   * @param workflow What it is about
   * @param kind What happened
   * @param bpmsEventId What the cluster called the event
   * @param transaction Which transaction the entry was asked for
   */
  public record WorkflowEvent(
                              WorkflowReference workflow,
                              WorkflowEventKind kind,
                              String bpmsEventId,
                              EventTransaction transaction) {
  }

  private final List<UserTaskEvent> userTasks = new LinkedList<>();

  private final List<WorkflowEvent> workflows = new LinkedList<>();

  private boolean collapsing;

  /**
   * Lets this publisher answer what the real one answers while a report of the same
   * idempotency key is still waiting to be dispatched.
   *
   * @param collapsing Whether the next reports collapse into an entry already written
   */
  public void collapsesReports(
      final boolean collapsing) {

    this.collapsing = collapsing;

  }

  @Override
  public boolean publishUserTaskEvent(
      final UserTaskReference userTask,
      final UserTaskEventKind kind,
      final String bpmsEventId,
      final OffsetDateTime timestamp,
      final EventTransaction transaction) {

    userTasks.add(new UserTaskEvent(userTask, kind, bpmsEventId, transaction));
    return !collapsing;

  }

  @Override
  public boolean publishWorkflowEvent(
      final WorkflowReference workflow,
      final WorkflowEventKind kind,
      final String bpmsEventId,
      final OffsetDateTime timestamp,
      final EventTransaction transaction) {

    workflows.add(new WorkflowEvent(workflow, kind, bpmsEventId, transaction));
    return !collapsing;

  }

  /**
   * @return Every user-task event reported so far
   */
  public List<UserTaskEvent> userTaskEvents() {

    return List.copyOf(userTasks);

  }

  /**
   * @return Every workflow event reported so far
   */
  public List<WorkflowEvent> workflowEvents() {

    return List.copyOf(workflows);

  }

}
