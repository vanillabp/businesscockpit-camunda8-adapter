package io.vanillabp.cockpit.camunda8.springboot.test;

import java.time.Duration;
import java.util.Map;
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

  /** The case whose next report is held, if any. */
  private final AtomicReference<Long> heldCase = new AtomicReference<>();

  /** Counted down once the provider is inside the held call. */
  private volatile CountDownLatch arrived = new CountDownLatch(0);

  /** Counted down by the test which lets the held call answer. */
  private volatile CountDownLatch released = new CountDownLatch(0);

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
   * Holds the next report of one case inside the listener job which builds it, and lets every
   * later report of that case through.
   *
   * @param aggregateId The case
   */
  public void holdTheNextReportOf(
      final Long aggregateId) {

    arrived = new CountDownLatch(1);
    released = new CountDownLatch(1);
    heldCase.set(aggregateId);

  }

  /** Waits until the held report is inside the provider, which is where its lock runs out. */
  public void awaitTheHeldReport() {

    await(arrived, "the details provider to reach the held call");

  }

  /** Lets the held report answer, which is what sends the late completion. */
  public void letTheHeldReportAnswer() {

    released.countDown();

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
    waitWhereThisCaseIsHeld(aggregate.getId());
    reports.incrementAndGet();
    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

  /**
   * Waits where this case is the one a test is holding. The hold is taken away by the first
   * call, so a report which follows is not held again.
   *
   * @param aggregateId The case this report is about
   */
  private void waitWhereThisCaseIsHeld(
      final Long aggregateId) {

    // by value rather than by reference: two boxed longs of the same number are two objects
    // unless the number is small
    final var held = heldCase.get();
    if (!aggregateId.equals(held) || !heldCase.compareAndSet(held, null)) {
      return;
    }
    final var open = released;
    arrived.countDown();
    await(open, "the test to let the report of case %s answer".formatted(aggregateId));

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
