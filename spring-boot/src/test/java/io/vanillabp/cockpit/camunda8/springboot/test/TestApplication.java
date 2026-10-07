package io.vanillabp.cockpit.camunda8.springboot.test;

import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.spi.cockpit.workflowmodules.WorkflowModuleDetailsProvider;

/**
 * The application the Camunda 8 half of the Business Cockpit extension is tested inside: a JPA
 * workflow aggregate, a workflow service with details providers, and a real Camunda 8 cluster
 * the VanillaBP Camunda 8 adapter deploys to.
 * <p>
 * Each test class gets a database of its own. The test configurations set
 * <code>spring.datasource.generate-unique-name</code>, so every Spring context gets an in-memory
 * database with a random name. The Camunda 7 and the Process Engine API adapters of the cockpit
 * name the database through a <code>ContextCustomizerFactory</code> and a key per test class
 * instead. Both ways keep test classes apart. The adapters differ here on purpose.
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
