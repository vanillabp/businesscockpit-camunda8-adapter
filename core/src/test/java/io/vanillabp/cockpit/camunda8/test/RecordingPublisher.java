package io.vanillabp.cockpit.camunda8.test;

import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * The platform-neutral half of the extension, played by the test: it writes nothing and
 * remembers what it was told.
 * <p>
 * Where a test hands it a BPMS half, it asks that half what the event says while it is being
 * told about the event, which is where the real one asks. The answer is remembered beside the
 * identifiers, so a test can assert that a report carries the state of its own event.
 */
public class RecordingPublisher implements BusinessCockpitEventPublisher {

  /**
   * One reported user-task event.
   *
   * @param userTask What it is about
   * @param kind What happened
   * @param bpmsEventId What the cluster called the event
   * @param transaction Which transaction the entry was asked for
   * @param values What the BPMS half said about the task while it was being reported, empty
   *          where this publisher was given no half to ask
   */
  public record UserTaskEvent(
                              UserTaskReference userTask,
                              UserTaskEventKind kind,
                              String bpmsEventId,
                              EventTransaction transaction,
                              Optional<UserTaskDetailsPrefill> values) {
  }

  /**
   * One reported workflow event.
   *
   * @param workflow What it is about
   * @param kind What happened
   * @param bpmsEventId What the cluster called the event
   * @param transaction Which transaction the entry was asked for
   * @param values What the BPMS half said about the workflow while it was being reported, empty
   *          where this publisher was given no half to ask
   */
  public record WorkflowEvent(
                              WorkflowReference workflow,
                              WorkflowEventKind kind,
                              String bpmsEventId,
                              EventTransaction transaction,
                              Optional<WorkflowDetailsPrefill> values) {
  }

  private final List<UserTaskEvent> userTasks = new LinkedList<>();

  private final List<WorkflowEvent> workflows = new LinkedList<>();

  private boolean writingNothing;

  private final BusinessCockpitBpmsBridge bridge;

  /**
   * A publisher which only remembers what it was told.
   */
  public RecordingPublisher() {

    this(null);

  }

  /**
   * @param bridge The BPMS half to ask while an event is being reported, or <code>null</code>
   */
  public RecordingPublisher(
      final BusinessCockpitBpmsBridge bridge) {

    this.bridge = bridge;

  }

  /**
   * Lets this publisher answer what the real one answers where no entry was written: the report
   * was dropped, or an entry of the same idempotency key is still waiting and the store kept it.
   *
   * @param writingNothing Whether the next reports produce no entry
   */
  public void writesNothing(
      final boolean writingNothing) {

    this.writingNothing = writingNothing;

  }

  @Override
  public boolean publishUserTaskEvent(
      final UserTaskReference userTask,
      final UserTaskEventKind kind,
      final String bpmsEventId,
      final OffsetDateTime timestamp,
      final EventTransaction transaction) {

    userTasks
        .add(
            new UserTaskEvent(
                userTask, kind, bpmsEventId, transaction, bridge == null
                    ? Optional.empty()
                    : bridge.prefilledUserTaskDetails(userTask)));
    return !writingNothing;

  }

  @Override
  public boolean publishWorkflowEvent(
      final WorkflowReference workflow,
      final WorkflowEventKind kind,
      final String bpmsEventId,
      final OffsetDateTime timestamp,
      final EventTransaction transaction) {

    workflows
        .add(
            new WorkflowEvent(
                workflow, kind, bpmsEventId, transaction, bridge == null
                    ? Optional.empty()
                    : bridge.prefilledWorkflowDetails(workflow)));
    return !writingNothing;

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
