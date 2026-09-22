package io.vanillabp.coverage;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A tool which only translates our source stays out of the classpath of the applications
 * using our artifacts.
 * <p>
 * Lombok and the annotation processors of Quarkus and Spring Boot are read while javac
 * runs and have nothing left to do once the class file exists. Somebody added one
 * dependency to see their user tasks in the cockpit, and every jar they did not ask for is
 * one more thing to ship and to answer a CVE report about. The scope which says that is
 * {@code provided}: it puts the jar on our own compile path and hands it to nobody. The
 * section "What a POM hands an application" of this repository's AGENTS.md says the same
 * in words, and this test is what makes it a gate.
 * <p>
 * What the check knows sits in {@link PublishedPoms} of the platform's module
 * 'test-utils'. Every repository of VanillaBP can make this mistake and they all make it
 * in the same way, so the rule lives in one place and each repository calls it. A copy per
 * repository drifts, and the worth of this check is that it still runs in two years. This
 * test is the caller which names the file this repository publishes and the tools of this
 * build.
 * <p>
 * This repository publishes through the flatten plugin, so the check reads the flattened
 * file. That file is what an application really resolves: a scope which stands only in a
 * dependencyManagement is already written into the declaration there, test dependencies
 * are gone and the profiles are applied. The source POM shows none of that.
 * <p>
 * The flatten plugin writes that file in the phase 'process-resources', so this test only
 * says something about a module which ran before it. That is why it lives in the gate of
 * this repository, which is the last module of the reactor, and not next to
 * Camunda8PublishedPomTest in 'core', which is the first. Run from an IDE, or from a build
 * narrowed down to later phases, it fails instead of passing on nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools of this build, each as {@code groupId:artifactId}. A tool this repository
   * starts using belongs in this list, because nothing else knows that it is one.
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
