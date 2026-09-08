package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.HttpURLConnection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.ProblemException;
import io.vanillabp.cockpit.camunda8.Camunda8CockpitReads;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Telling "the cluster has no such record" apart from everything else it may answer. Getting
 * this wrong is invisible in both directions: a missed 404 turns a task nobody created into a
 * retried report, and a 404 read into an outage turns an outage into a cockpit which quietly
 * stops showing what is there.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitReadsTest {

  private static ProblemException problem(
      final int code) {

    return new ProblemException(code, "reason", null);

  }

  @Test
  @DisplayName("A 404 of the cluster is recognized, however deep it is wrapped")
  public void aNotFoundIsRecognized() {

    assertTrue(Camunda8CockpitReads.nothingFound(problem(HttpURLConnection.HTTP_NOT_FOUND)));
    assertTrue(
        Camunda8CockpitReads
            .nothingFound(
                new IllegalStateException(
                    "reading", new RuntimeException(problem(HttpURLConnection.HTTP_NOT_FOUND)))));

  }

  @Test
  @DisplayName("Everything else the cluster answers is not a missing record")
  public void anythingElseIsNotANotFound() {

    assertFalse(Camunda8CockpitReads.nothingFound(problem(HttpURLConnection.HTTP_FORBIDDEN)));
    assertFalse(Camunda8CockpitReads.nothingFound(problem(HttpURLConnection.HTTP_UNAVAILABLE)));
    assertFalse(Camunda8CockpitReads.nothingFound(new IllegalStateException("the cluster is gone")));
    assertFalse(Camunda8CockpitReads.nothingFound(null));

  }

  @Test
  @DisplayName("A failure whose cause is itself ends the search rather than looping")
  public void aSelfReferencingCauseTerminates() {

    final var failure = new IllegalStateException("round and round") {

      private static final long serialVersionUID = 1L;

      @Override
      public synchronized Throwable getCause() {

        return this;

      }

    };

    assertFalse(Camunda8CockpitReads.nothingFound(failure));

  }

  @Test
  @DisplayName("A report of something not exported yet comes back after a bounded wait")
  public void aReportComesBackLater() {

    final var retryLater = Camunda8CockpitReads.notExportedYet("the user task '7'", "c8");

    assertEquals(Camunda8CockpitReads.WHILE_THE_EXPORTER_CATCHES_UP, retryLater.getRetryAfter());
    assertTrue(retryLater.getMessage().contains("the user task '7'"));
    assertTrue(retryLater.getMessage().contains("'c8'"));

  }

}
