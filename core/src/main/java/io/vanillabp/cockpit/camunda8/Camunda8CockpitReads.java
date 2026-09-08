package io.vanillabp.cockpit.camunda8;

import java.time.Duration;

import io.vanillabp.integration.spi.PhaseTwoRetryLater;

/**
 * How this extension reads the state Camunda 8 keeps beside its own engine, and what a missing
 * answer means there.
 * <p>
 * The engine of Camunda 8 knows a user task the moment it exists; the storage which can be
 * SEARCHED for it learns about it a little later, because an exporter has to write it first.
 * Everything the cockpit is told is read from that second storage, so "the cluster does not
 * know it" has two meanings and telling them apart is the whole job here:
 * <ul>
 * <li>while a report of something which just happened is being dispatched, it means the
 * exporter has not caught up - the entry comes back in a moment;</li>
 * <li>while the application asks about a task or a workflow of its own, it means there is
 * nothing to show, and an empty answer is what the caller can work with.</li>
 * </ul>
 * Anything else the cluster answers travels on unchanged. An outage must not look like an empty
 * result, or a cockpit would quietly stop showing what is there.
 * <p>
 * WHICH answer means "I do not hold that" is the adapter's to say
 * ({@code Camunda8Errors#notFound}): the REST gateway says it with HTTP <code>404</code> and the
 * gRPC gateway with the status <code>NOT_FOUND</code>, and reading only one of the two turns the
 * other transport's answer into a hard failure.
 */
public final class Camunda8CockpitReads {

  /**
   * How long a report waits before it asks the cluster again.
   * <p>
   * The outbox bounds the attempts (<code>vanillabp.outbox.block-after-attempts</code>, fifty by
   * default) and blocks an entry which used them up, so this window times that bound is the
   * export lag a report survives - two seconds carry a cluster which is a minute and a half
   * behind, while a shorter window would make a busy cluster lose reports rather than deliver
   * them late. Late is the right answer here: nothing downstream waits for one, and the
   * dispatch reads the current state anyway.
   */
  public static final Duration WHILE_THE_EXPORTER_CATCHES_UP = Duration.ofSeconds(2);

  private Camunda8CockpitReads() {
  }

  /**
   * What a report does when the cluster does not know yet what it is about.
   *
   * @param what The thing which was read, for the message
   * @param adapterId The adapter whose cluster was asked
   * @return The exception to throw, which hands the outbox entry back for a later attempt
   */
  public static PhaseTwoRetryLater notExportedYet(
      final String what,
      final String adapterId) {

    return new PhaseTwoRetryLater(
        """
            The Camunda 8 cluster of adapter '%s' does not know %s yet. Its searchable storage is \
            written by an exporter which runs behind the engine, so a report of something which \
            just happened arrives before the record it reads does - this report is dispatched \
            again in a moment."""
            .formatted(adapterId, what), WHILE_THE_EXPORTER_CATCHES_UP);

  }

}
