package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;

import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which of the adapter's worker settings this extension hands to a worker builder of its own.
 * <p>
 * Only one of them, and that is the point of the test: the other three arrive at every worker
 * through the client the adapter built, where an environment variable can still overrule them.
 * Setting them again per worker would beat that environment variable without saying so, and
 * nothing in a running application would show it - hence a test which fails the moment a
 * second setter is called.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CockpitWorkerOptionsTest {

  private static Camunda8AdapterConfiguration configured() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setStreamEnabled(Boolean.TRUE);
    configuration.setPollInterval(Duration.ofMillis(250));
    configuration.setRequestTimeout(Duration.ofSeconds(20));
    return configuration;

  }

  @Test
  @DisplayName("A configured stream-timeout is the one option a worker of this extension sets itself")
  public void theStreamTimeoutReachesTheWorkerBuilder() {

    final var configuration = configured();
    configuration.setStreamTimeout(Duration.ofMinutes(30));
    final var builder = mock(JobWorkerBuilderStep3.class);
    when(builder.streamTimeout(ArgumentMatchers.any())).thenReturn(builder);

    final var withOptions = Camunda8CockpitWorkers
        .withTheAdaptersStreamTimeout(builder, configuration);

    assertSame(builder, withOptions);
    verify(builder).streamTimeout(Duration.ofMinutes(30));
    // whatever else was configured belongs to the client and must not be repeated here
    verifyNoMoreInteractions(builder);

  }

  @Test
  @DisplayName("Without a configured stream-timeout the builder is handed back untouched")
  public void withoutAStreamTimeoutTheBuilderIsLeftAlone() {

    final var builder = mock(JobWorkerBuilderStep3.class);

    final var withOptions = Camunda8CockpitWorkers
        .withTheAdaptersStreamTimeout(builder, configured());

    assertSame(builder, withOptions);
    verifyNoMoreInteractions(builder);

  }

}
