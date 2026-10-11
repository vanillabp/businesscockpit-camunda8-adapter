package io.vanillabp.coverage;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Checks that a tool which only compiles our source stays off the classpath of the applications
 * which use our artifacts.
 * <p>
 * Lombok and the annotation processors of Quarkus and Spring Boot are used while javac runs.
 * Once the class file exists, they have nothing left to do. An application asked for the
 * cockpit, not for them. Every jar it did not ask for is one more thing to ship and to answer a
 * CVE report about. The scope for such a tool is {@code provided}: it puts the jar on our own
 * compile path and hands it to nobody. The section "What a POM hands an application" of the
 * AGENTS.md of this repository says the same in words. This test turns it into a gate.
 * <p>
 * The check itself is in {@link PublishedPoms} in the module 'test-utils' of the platform. Every
 * VanillaBP repository can make this mistake, and they all make it in the same way. So the rule
 * lives in one place and each repository calls it. A copy per repository drifts, and the check
 * is only worth something if it still runs in two years. This test is the caller. It names the
 * file this repository publishes and the tools of this build.
 * <p>
 * This repository publishes through the flatten plugin, so the check reads the flattened
 * file. That file is what an application really resolves: a scope which stands only in a
 * dependencyManagement is already written into the declaration there, test dependencies are
 * gone, and the profiles are applied. The source POM shows none of that.
 * <p>
 * The flatten plugin writes that file in the phase 'process-resources', so this test only says
 * something about a module which ran before it. That is why it lives in the gate of this
 * repository, which is the last module of the reactor, and not next to Camunda8PublishedPomTest
 * in 'core', which is the first. Run from an IDE, or from a build narrowed down to later phases,
 * it fails instead of passing on nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools of this build, each as {@code groupId:artifactId}. When this repository starts to
   * use a new tool, add it here, because nothing else knows that it is a tool.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "io.quarkus:quarkus-extension-processor");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_FLATTENED_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

}
