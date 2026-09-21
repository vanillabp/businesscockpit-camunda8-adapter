package io.vanillabp.cockpit.camunda8;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.test.PublishedPom;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The POM this line publishes asks for this line's Camunda client, and for nothing an
 * application of another line would have to take. It also says the truth about itself: the
 * addresses in it open, and it keeps quiet about where we deploy.
 * <p>
 * A release line is a promise about the cluster an application may run: the client a build was
 * compiled against is the lowest cluster version that build accepts. The promise is kept by the
 * POM rather than by the jar, because that POM is what puts the client on an application's
 * classpath. It used to say something else here. The version lived in a property the line
 * profile sets, the published POM was a copy of the source POM, and an application activates
 * none of our profiles, so every line asked for the client of the current GA line. That is what
 * the flatten plugin's 'oss' mode ended, and this test is what keeps it ended. See decision 12
 * in the repository's DECISIONS.md.
 * <p>
 * What the assertions know sits in {@link PublishedPom} of 'camunda8-adapter-test-support',
 * published by the VanillaBP Camunda 8 adapter on the same release lines as this repository.
 * Both repositories build a Camunda 8 adapter, both make the same promise, and a copy of the
 * check in each of them would drift. This test is the caller: it names the artifact, the
 * versions expected of it and the repository it comes from.
 * <p>
 * The client to expect is handed over by the build, see the surefire configuration in this
 * module's pom.xml. The adapter reads it from its own line descriptor and we cannot: every line
 * of this repository still compiles against the same adapter snapshot, so that descriptor would
 * answer for whichever line the adapter was last built for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8PublishedPomTest {

  /** The repository every artifact of this build comes from, and the address all of them name. */
  private static final String REPOSITORY = "https://github.com/vanillabp/businesscockpit-camunda8-adapter";

  @Test
  @DisplayName("the published POM asks for the client of this release line")
  public void thePublishedPomAsksForTheClientOfThisLine() {

    PublishedPom
        .ofTheModuleUnderTest()
        .asksFor("io.camunda", "camunda-client-java", clientOfThisBuild());

  }

  @Test
  @DisplayName("the published POM leaves an application nothing of ours to inherit")
  public void thePublishedPomHasNoParentAndNoDependencyManagement() {

    PublishedPom
        .ofTheModuleUnderTest()
        .handsAnApplicationNothingToInherit();

  }

  @Test
  @DisplayName("every address in the published POM is one which opens")
  public void thePublishedPomNamesTheRepositoryAndNoModulePath() {

    PublishedPom
        .ofTheModuleUnderTest()
        .pointsAt(REPOSITORY);

  }

  @Test
  @DisplayName("the published POM says nothing about where we deploy")
  public void thePublishedPomHasNoDistributionManagement() {

    PublishedPom
        .ofTheModuleUnderTest()
        .saysNothingAboutWhereWeDeploy();

  }

  /** The exact Camunda client the active line profile selected for this build. */
  private String clientOfThisBuild() {

    final var client = System.getProperty("camunda8.client");
    if ((client != null) && !client.isBlank()) {
      return client;
    }
    throw new AssertionError(
        "The system property 'camunda8.client' is not set, so there is nothing to hold the "
            + "published POM against. The build hands it to surefire from the property the "
            + "active line profile selected, see this module's pom.xml. A run which skipped "
            + "that configuration cannot answer the question this test asks.");

  }

}
