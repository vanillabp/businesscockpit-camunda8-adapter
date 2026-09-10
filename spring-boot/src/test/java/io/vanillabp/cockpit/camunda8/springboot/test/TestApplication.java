package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.spi.cockpit.workflowmodules.WorkflowModuleDetailsProvider;

/**
 * The application the Camunda 8 half of the Business Cockpit extension is tested inside: a JPA
 * workflow aggregate, a workflow service with details providers, and a real Camunda 8 cluster
 * the VanillaBP Camunda 8 adapter deploys to.
 */
@SpringBootApplication
public class TestApplication {

  /** The groups this application reports as allowed to see its cases. */
  public static final List<String> ACCESSIBLE_TO_GROUPS = List.of("clerks", "approvers");

  /**
   * @return What the cockpit server is told about this workflow module
   */
  @Bean
  public WorkflowModuleDetailsProvider workflowModuleDetailsProvider() {

    return new WorkflowModuleDetailsProvider() {

      @Override
      public List<String> getAccessibleToGroups() {

        return ACCESSIBLE_TO_GROUPS;

      }

      @Override
      public String getWorkflowModuleId() {

        return "c8-cockpit";

      }

    };

  }

}
