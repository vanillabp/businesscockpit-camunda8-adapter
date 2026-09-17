package io.vanillabp.cockpit.camunda8.springboot.test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Lets a test stop the user-task details provider in the middle of an event, so that the moment
 * a report is built from is a fixed point of the test rather than a coincidence.
 * <p>
 * The provider runs inside the listener job of the task, holding the case as that event left it.
 * Held here, it has read the case and has not answered yet, and the test can change the very same
 * case from a transaction of its own. What the report then carries is the state of its event, and
 * not the state of the moment it is sent.
 * <p>
 * The gate is open unless a test closes it, so every other test runs at its own speed, and it
 * holds the FIRST call for the case it was closed for and lets every later one through.
 */
@Component
public class DetailsProviderGate {

  /** How long the provider waits for a test which closed the gate and forgot to open it. */
  private static final Duration WAITING_AT_MOST = Duration.ofSeconds(60);

  /** The case whose next call is held, if any. */
  private final AtomicReference<Long> heldCase = new AtomicReference<>();

  /** Counted down once the provider is inside the call and holding the case. */
  private volatile CountDownLatch arrived = new CountDownLatch(0);

  /** Counted down by the test which lets the provider finish. */
  private volatile CountDownLatch released = new CountDownLatch(0);

  /**
   * Closes the gate in front of the next call for one case.
   *
   * @param aggregateId The case
   */
  public void holdTheNextCallFor(
      final Long aggregateId) {

    arrived = new CountDownLatch(1);
    released = new CountDownLatch(1);
    heldCase.set(aggregateId);

  }

  /**
   * What the details provider calls. Where this case is the one being held, it waits until the
   * test lets the event finish.
   *
   * @param aggregateId The case the provider was called for
   */
  public void passOrWait(
      final Long aggregateId) {

    // by value rather than by reference: comparing the ids themselves would compare two boxed
    // longs, and only the small ones are the same object
    final var held = heldCase.get();
    if (!aggregateId.equals(held) || !heldCase.compareAndSet(held, null)) {
      return;
    }
    final var open = released;
    arrived.countDown();
    await(open, "the test to let the details provider of case %s answer".formatted(aggregateId));

  }

  /**
   * Waits until the provider is inside the held call, which is the moment the test may change
   * the case behind its back.
   */
  public void awaitTheHeldCall() {

    await(arrived, "the details provider to reach the gate");

  }

  /** Lets the held call finish. */
  public void letTheHeldCallFinish() {

    released.countDown();

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
