package io.vanillabp.cockpit.camunda8.springboot.test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Lets a test stop the user-task details provider in the middle of a dispatch, so that the
 * window in which two writers meet on one workflow aggregate is a fixed point of the test
 * rather than a coincidence.
 * <p>
 * The provider reads the case in the transaction which dispatches the report, and that
 * transaction writes the case back when it commits. Held here, the provider has read the case
 * and has not written it back yet, and the test can change the very same case from a
 * transaction of its own. What happens then is what the version attribute of
 * {@link TestAggregate} is for.
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

  /** The case whose provider writes onto it, if any. */
  private final AtomicReference<Long> writtenCase = new AtomicReference<>();

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
   * Lets the details provider write onto one case, which is what makes the cockpit a second writer
   * of it.
   * <p>
   * Off for every other case, and that is not tidiness. A provider writes into whatever
   * transaction ran it, and the read behind {@code BusinessCockpitService.getUserTask} runs one
   * too, in the transaction of the caller. So a provider which writes onto every case turns a test
   * which only reads into a writer of a case the cockpit's own dispatch is writing at the same
   * moment, and one of the two then reads a conflict. What VanillaBP saves after a details provider
   * and what a persistence writes anyway is decision 17 in the DECISIONS.md of
   * vanillabp/business-cockpit.
   *
   * @param aggregateId The case
   */
  public void letTheProviderWriteOnto(
      final Long aggregateId) {

    writtenCase.set(aggregateId);

  }

  /**
   * @param aggregateId The case a provider was called for
   * @return Whether the provider may write onto it
   */
  public boolean mayWriteOnto(
      final Long aggregateId) {

    return aggregateId.equals(writtenCase.get());

  }

  /**
   * What the details provider calls. Where this case is the one being held, it waits until the
   * test says the provider may write back.
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
    await(open, "the test to let the details provider of case %s write back".formatted(aggregateId));

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
