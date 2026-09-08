package io.vanillabp.cockpit.camunda8.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.camunda8.Camunda8CockpitReads;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this extension does with the answer "I do not hold that" while a report is being
 * dispatched: it hands the entry back for a later attempt rather than dropping it, because a
 * report is dispatched moments after the cluster handed out the job it came from.
 * <p>
 * Recognising that answer is the adapter's job and is asserted there ({@code Camunda8ErrorsTest}
 * holds both transports and the wrapped answer), which is why this half no longer reads a code
 * of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitReadsTest {

  @Test
  @DisplayName("A report of something not exported yet comes back after a bounded wait")
  public void aReportComesBackLater() {

    final var retryLater = Camunda8CockpitReads.notExportedYet("the user task '7'", "c8");

    assertEquals(Camunda8CockpitReads.WHILE_THE_EXPORTER_CATCHES_UP, retryLater.getRetryAfter());
    assertTrue(retryLater.getMessage().contains("the user task '7'"));
    assertTrue(retryLater.getMessage().contains("'c8'"));

  }

}
