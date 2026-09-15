package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which workflow a job belongs to, on a line whose jobs say it themselves.
 * <p>
 * The same three questions are asked of every line, and the 8.8 variant of this test answers
 * them with a cluster it has to ask. What the lines have in common is the answer, which is the
 * point of testing them separately.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CallHierarchyTest {

  private static final String ADAPTER_ID = "c8";

  private final Camunda8ClientFactoryRegistry clientFactories = mock(
      Camunda8ClientFactoryRegistry.class, RETURNS_DEEP_STUBS);

  private final Camunda8CallHierarchy hierarchy = new Camunda8CallHierarchy(
      new Camunda8Clients(clientFactories, null).of(ADAPTER_ID));

  @Test
  @DisplayName("A job of a called process names the workflow above it")
  public void aCalledProcessNamesItsRoot() {

    final var job = mock(ActivatedJob.class);
    when(job.getProcessInstanceKey()).thenReturn(777L);
    when(job.getRootProcessInstanceKey()).thenReturn(12345L);

    assertEquals(12345L, hierarchy.rootProcessInstanceKeyOf(job));

  }

  @Test
  @DisplayName("A job of a workflow nobody called names nothing above it")
  public void aRootWorkflowNamesNothing() {

    final var job = mock(ActivatedJob.class);
    when(job.getProcessInstanceKey()).thenReturn(12345L);
    when(job.getRootProcessInstanceKey()).thenReturn(12345L);

    assertNull(hierarchy.rootProcessInstanceKeyOf(job));

  }

  @Test
  @DisplayName("A cluster which reports no root at all means the job is its own")
  public void noRootReportedIsARootWorkflow() {

    final var job = mock(ActivatedJob.class);
    when(job.getProcessInstanceKey()).thenReturn(12345L);
    when(job.getRootProcessInstanceKey()).thenReturn(null);

    assertNull(hierarchy.rootProcessInstanceKeyOf(job));

  }

  @Test
  @DisplayName("The cluster is not asked, because the job already said it")
  public void theClusterIsNotAsked() {

    final var job = mock(ActivatedJob.class);
    when(job.getProcessInstanceKey()).thenReturn(12345L);
    when(job.getRootProcessInstanceKey()).thenReturn(12345L);

    // what the cluster handed out while this object was built is not what is under test here
    clearInvocations(clientFactories);

    hierarchy.rootProcessInstanceKeyOf(job);

    verifyNoInteractions(clientFactories);

  }

}
