package io.vanillabp.cockpit.camunda8;

import io.vanillabp.camunda8.wiring.Camunda8Scoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * How one configured Camunda 8 cluster names the things the extension asks it about.
 * <p>
 * Name-clash avoidance decides whether a workflow module's processes are deployed under a
 * tenant, under prefixed identifiers or under neither. Every request the extension sends to a
 * cluster has to spell an identifier the way that cluster stores it, while everything the
 * cockpit is told is spelled the way the application wrote it. The rules are the adapter's, so
 * they are asked of the adapter's own helper rather than rebuilt here. A prefix which the
 * extension assembles itself is a prefix which drifts apart from the adapter's on the next
 * change.
 */
public class Camunda8Scope {

  private final String adapterId;

  private final NameClashAvoidanceSupport scoping;

  private final String configuredTenantId;

  /**
   * Creates the naming rules of one cluster.
   *
   * @param adapterId The configured adapter id whose cluster this scope belongs to
   * @param scoping VanillaBP's name-clash avoidance, or <code>null</code> where the platform
   *          offers none
   * @param configuredTenantId What the adapter was configured with, or <code>null</code>
   */
  public Camunda8Scope(
      final String adapterId,
      final NameClashAvoidanceSupport scoping,
      final String configuredTenantId) {

    this.adapterId = adapterId;
    this.scoping = scoping;
    this.configuredTenantId = configuredTenantId;

  }

  /**
   * Gives the adapter id this scope belongs to.
   *
   * @return The configured adapter id
   */
  public String adapterId() {

    return adapterId;

  }

  /**
   * Gives the tenant under which the cluster keeps the processes of a workflow module.
   *
   * @param workflowModuleId The workflow module
   * @return The Camunda tenant this module's workflows live in, or <code>null</code> where the
   *         configured mode uses none
   */
  public String tenantIdOf(
      final String workflowModuleId) {

    return Camunda8Scoping.tenantIdFor(scoping, workflowModuleId, adapterId, configuredTenantId);

  }

  /**
   * Turns a BPMN process id, as the application wrote it, into the id the cluster knows.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @return The process id the cluster knows, which differs from the plain one only under
   *         <code>use-prefix</code>
   */
  public String scopedProcessIdOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return NameClashAvoidanceSupport
        .scopedProcessId(scoping, workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * Turns a task definition, as the cluster knows it, back into the one the application wrote.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @param taskDefinition The external form reference as the cluster knows it
   * @return The task definition as the application wrote it, which is what a
   *         <code>&#64;UserTaskDetailsProvider</code> method is matched by
   */
  public String plainTaskDefinitionOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    return NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

}
