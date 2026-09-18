package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.response.ProcessInstanceCallHierarchyEntryResponse;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which workflow a job belongs to, on a line whose jobs do not say it.
 * <p>
 * The same three questions the other lines answer from the job are answered here by the
 * cluster's call hierarchy, and two more come with asking: what happens while the searchable
 * storage is behind, and that an answer is not bought twice.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CallHierarchyTest {

  private static final String ADAPTER_ID = "c8";

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final Camunda8CallHierarchy hierarchy = new Camunda8CallHierarchy(
      new Camunda8Clients(clientFactories, null).of(ADAPTER_ID));

  /**
   * How long the lookup waits for the searchable storage: the adapter's
   * <code>workflow-visibility-timeout</code>, which a test sets to something it can wait for.
   */
  @BeforeEach
  public void aClusterWhoseExporterIsGivenAMoment() {

    when(
        clientFactories
            .getFactory(ADAPTER_ID)
            .getConfiguration()
            .workflowVisibilityWindow())
        .thenReturn(Duration.ofMillis(300));

  }

  @Test
  @DisplayName("A job of a called process names the workflow above it")
  public void aCalledProcessNamesItsRoot() {

    clusterAnswers(777L, List.of(anEntry(12345L), anEntry(777L)));

    assertEquals(12345L, hierarchy.rootProcessInstanceKeyOf(aJobOf(777L)));

  }

  @Test
  @DisplayName("A job of a workflow nobody called names nothing above it")
  public void aRootWorkflowNamesNothing() {

    clusterAnswers(12345L, List.of(anEntry(12345L)));

    assertNull(hierarchy.rootProcessInstanceKeyOf(aJobOf(12345L)));

  }

  @Test
  @DisplayName("A workflow which stands in no hierarchy is answered at once, without waiting")
  public void anEmptyHierarchyIsAnsweredAtOnce() {

    final var asked = new AtomicInteger();
    when(client().newProcessInstanceGetCallHierarchyRequest(12345L).send().join())
        .thenAnswer(request -> {
          asked.incrementAndGet();
          return List.of();
        });

    assertNull(hierarchy.rootProcessInstanceKeyOf(aJobOf(12345L)));

    // an 8.8 cluster answers a workflow nobody called with no entries at all, which is nearly
    // every workflow there is. Reading that as a storage which has not caught up would hold the
    // transition of every listener job open for the whole window
    assertEquals(1, asked.get());

  }

  @Test
  @DisplayName("A hierarchy the searchable storage does not hold yet ends as a workflow of its own")
  public void anUnknownHierarchyIsARootWorkflow() {

    final var asked = new AtomicInteger();
    when(client().newProcessInstanceGetCallHierarchyRequest(12345L).send().join())
        .thenAnswer(request -> {
          asked.incrementAndGet();
          // the cluster holds no instance of that key: the exporter has not written it yet
          throw new ClientHttpException(404, "Not Found");
        });

    assertNull(hierarchy.rootProcessInstanceKeyOf(aJobOf(12345L)));

    // this is the answer worth asking again for, so the lookup used its window on it
    assertTrue(asked.get() > 1, "the lookup gave up after one attempt");

  }

  @Test
  @DisplayName("A window of zero asks once and waits for nothing")
  public void whereTheWaitingIsSwitchedOff() {

    when(
        clientFactories
            .getFactory(ADAPTER_ID)
            .getConfiguration()
            .workflowVisibilityWindow())
        .thenReturn(Duration.ZERO);
    final var asked = new AtomicInteger();
    when(client().newProcessInstanceGetCallHierarchyRequest(12345L).send().join())
        .thenAnswer(request -> {
          asked.incrementAndGet();
          throw new ClientHttpException(404, "Not Found");
        });

    assertNull(hierarchy.rootProcessInstanceKeyOf(aJobOf(12345L)));

    assertEquals(1, asked.get());

  }

  @Test
  @DisplayName("What the cluster answered is not asked a second time")
  public void anAnsweredHierarchyIsRemembered() {

    final var asked = new AtomicInteger();
    when(client().newProcessInstanceGetCallHierarchyRequest(777L).send().join())
        .thenAnswer(request -> {
          asked.incrementAndGet();
          return List.of(anEntry(12345L), anEntry(777L));
        });

    hierarchy.rootProcessInstanceKeyOf(aJobOf(777L));
    hierarchy.rootProcessInstanceKeyOf(aJobOf(777L));

    assertEquals(1, asked.get());

  }

  private ActivatedJob aJobOf(
      final long processInstanceKey) {

    final var job = mock(ActivatedJob.class);
    when(job.getProcessInstanceKey()).thenReturn(processInstanceKey);
    return job;

  }

  private CamundaClient client() {

    return clientFactories.getFactory(ADAPTER_ID).getClient();

  }

  private void clusterAnswers(
      final long processInstanceKey,
      final List<ProcessInstanceCallHierarchyEntryResponse> hierarchyOfThatInstance) {

    when(client().newProcessInstanceGetCallHierarchyRequest(anyLong()).send().join())
        .thenReturn(List.of());
    when(
        client()
            .newProcessInstanceGetCallHierarchyRequest(processInstanceKey)
            .send()
            .join())
        .thenReturn(hierarchyOfThatInstance);

  }

  private static ProcessInstanceCallHierarchyEntryResponse anEntry(
      final long processInstanceKey) {

    final var entry = mock(ProcessInstanceCallHierarchyEntryResponse.class);
    when(entry.getProcessInstanceKey()).thenReturn(processInstanceKey);
    return entry;

  }

}
