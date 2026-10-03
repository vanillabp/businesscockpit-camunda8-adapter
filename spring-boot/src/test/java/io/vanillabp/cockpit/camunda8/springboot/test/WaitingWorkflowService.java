package io.vanillabp.cockpit.camunda8.springboot.test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>
 * Its details provider can be made to fail, and it can be made to wait. Waiting is what lets a
 * test outlive the lock of the listener job the provider runs in, which is the one way to see
 * what the cluster does with an answer that comes too late.
 * <p>
 * The waiting holds every report of one case, and a test lets them answer one at a time, oldest
 * first. One run of a listener job is not enough to see a late answer refused: the cluster
 * refuses it because another run holds the job, so that other run has to be inside the provider
 * while the first one answers.
 */
@Service
@WorkflowService(workflowAggregateClass = WaitingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = WaitingWorkflowService.BPMN_PROCESS_ID))
public class WaitingWorkflowService {

  /** The process a case of this service is. */
  public static final String BPMN_PROCESS_ID = "WaitingProcess";

  private final ProcessService<WaitingAggregate> processService;

  private final AtomicBoolean theProviderFails = new AtomicBoolean();

  /** How long a held call waits for a test which held it and forgot to let it answer. */
  private static final Duration WAITING_AT_MOST = Duration.ofMinutes(3);

  /** The case whose reports are held, if any. */
  private final AtomicReference<Long> heldCase = new AtomicReference<>();

  /**
   * One report which is waiting inside the provider.
   *
   * @param eventId What the cockpit calls the event this report is about. On Camunda 8 it is the
   *          key of the listener job the report is being built in
   * @param answer Counted down by the test which lets this report answer
   */
  private record HeldReport(
                            String eventId,
                            CountDownLatch answer) {
  }

  /** The reports waiting inside the provider, the oldest one first. */
  private final Queue<HeldReport> held = new ConcurrentLinkedQueue<>();

  /** How often the provider answered, so a test can see a report which was built twice. */
  private final AtomicInteger reports = new AtomicInteger();

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
   * Holds every report of one case inside the listener job which builds it, until a test lets it
   * answer.
   *
   * @param aggregateId The case
   */
  public void holdTheReportsOf(
      final Long aggregateId) {

    heldCase.set(aggregateId);

  }

  /**
   * @return How many reports are waiting inside the provider right now
   */
  public int reportsHeld() {

    return held.size();

  }

  /**
   * @return What the cockpit calls the events the waiting reports are about, the oldest report
   *         first
   */
  public List<String> eventIdsOfTheHeldReports() {

    return held.stream().map(HeldReport::eventId).toList();

  }

  /** Lets the report which has been waiting longest answer, which is what sends its completion. */
  public void letTheOldestHeldReportAnswer() {

    final var oldest = held.poll();
    if (oldest == null) {
      throw new IllegalStateException("No report of this service is waiting inside its provider");
    }
    oldest.answer().countDown();

  }

  /**
   * Stops holding reports and lets every report which is still waiting answer. A report left
   * waiting would give up after {@link #WAITING_AT_MOST} and fail its job, long after the test
   * which held it was over.
   */
  public void stopHoldingReports() {

    heldCase.set(null);
    HeldReport waiting;
    while ((waiting = held.poll()) != null) {
      waiting.answer().countDown();
    }

  }

  /**
   * @return How often this provider answered since {@link #forgetWhatWasReported()}
   */
  public int reportsBuilt() {

    return reports.get();

  }

  /** Starts counting the reports of this provider from zero. */
  public void forgetWhatWasReported() {

    reports.set(0);

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
    waitWhereThisCaseIsHeld(aggregate.getId(), prefilled.getEventId());
    reports.incrementAndGet();
    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

  /**
   * Waits where this case is the one a test is holding. Every report of that case waits, so a
   * test can have two runs of one listener job inside the provider at the same time.
   *
   * @param aggregateId The case this report is about
   * @param eventId What the cockpit calls the event, which a test reads to see which job the
   *          waiting reports are about
   */
  private void waitWhereThisCaseIsHeld(
      final Long aggregateId,
      final String eventId) {

    // by value rather than by reference: two boxed longs of the same number are two objects
    // unless the number is small
    if (!aggregateId.equals(heldCase.get())) {
      return;
    }
    final var hold = new HeldReport(eventId, new CountDownLatch(1));
    held.add(hold);
    if (!aggregateId.equals(heldCase.get())) {
      // a test stopped holding between the two reads. It let every report of the queue answer
      // before this one was in it, so this one lets itself answer
      hold.answer().countDown();
    }
    await(
        hold.answer(),
        "the test to let report %s of case %s answer".formatted(eventId, aggregateId));

  }

  private static void await(
      final CountDownLatch latch,
      final String what) {

    try {
      if (!latch.await(WAITING_AT_MOST.toSeconds(), TimeUnit.SECONDS)) {
        throw new IllegalStateException("Waited "
            + WAITING_AT_MOST
            + " for "
            + what);
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for "
          + what, e);
    }

  }

}
