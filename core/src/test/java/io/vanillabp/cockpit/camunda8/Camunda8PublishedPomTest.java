package io.vanillabp.cockpit.camunda8;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.test.PublishedPom;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The POM this line publishes asks for this line's Camunda 8 adapter, and for nothing which an
 * application of another line would have to take. It also tells the truth about itself: the
 * addresses in it open, and it says nothing about where we deploy.
 * <p>
 * A release line is a promise about the cluster an application may run. The client a build was
 * compiled against is the lowest cluster version that build accepts. The POM keeps the promise,
 * not the jar, because the POM puts the client on an application's classpath. It does that through
 * the adapter: this module names no client of its own, and the published POM of the adapter names
 * the client of its line. So the adapter version is what this test holds the POM against.
 * <p>
 * It used to say something else here. The version was in a property which the line profile sets,
 * the published POM was a copy of the source POM, and an application activates none of our
 * profiles. So every line asked for the client of the current GA line. The 'oss' mode of the
 * flatten plugin ended that, and this test keeps it that way. See decisions 12 and 32 in the
 * repository's DECISIONS.md.
 * <p>
 * What the assertions know sits in {@link PublishedPom} of 'camunda8-adapter-published-pom',
 * published by the VanillaBP Camunda 8 adapter on the same release lines as this repository.
 * Both repositories build a Camunda 8 adapter, both make the same promise, and a copy of the
 * check in each of them would drift. This test is the caller: it names the artifact, the
 * versions expected of it, the repository it comes from and the run it belongs to.
 * <p>
 * The adapter version to expect and the line are handed over by the build, see the surefire
 * configuration in this module's pom.xml. That the adapter on the classpath belongs to the line
 * is {@link Camunda8AdapterOfThisLineTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8PublishedPomTest {

  /** The repository every artifact of this build comes from, and the address all of them name. */
  private static final String REPOSITORY = "https://github.com/vanillabp/businesscockpit-camunda8-adapter";

  @Test
  @DisplayName("the published POM asks for the Camunda 8 adapter of this release line")
  public void thePublishedPomAsksForTheAdapterOfThisLine() {

    thePublishedPom()
        .asksFor("org.camunda.community.vanillabp", "camunda8-adapter", theAdapterToExpect());

  }

  @Test
  @DisplayName("the published POM leaves an application nothing of ours to inherit")
  public void thePublishedPomHasNoParentAndNoDependencyManagement() {

    thePublishedPom().handsAnApplicationNothingToInherit();

  }

  @Test
  @DisplayName("every address in the published POM is one which opens")
  public void thePublishedPomNamesTheRepositoryAndNoModulePath() {

    thePublishedPom().pointsAt(REPOSITORY);

  }

  @Test
  @DisplayName("the published POM says nothing about where we deploy")
  public void thePublishedPomHasNoDistributionManagement() {

    thePublishedPom().saysNothingAboutWhereWeDeploy();

  }

  /** The published POM of this module, told which run is asking about it. */
  private PublishedPom thePublishedPom() {

    return PublishedPom
        .ofTheModuleUnderTest()
        .inTheRun(theRunOfThisBuild());

  }

  /**
   * Which of the three line builds is asking, repeated in every failure message.
   * <p>
   * A failure names the coordinates of the artifact, and outside a release ours say nothing
   * about the line. A pull request builds the same snapshot version on every line, so the same
   * message fits all three, and a reader of a red build has to guess. The line the active profile
   * chose tells them apart.
   */
  private String theRunOfThisBuild() {

    final var line = handedOver("camunda8.line");
    return line == null
        ? "a run which named no release line"
        : "the line "
            + line;

  }

  /**
   * The adapter version to hold the published POM against.
   *
   * @throws AssertionError If the build handed none over, because a test which cannot name
   *           the adapter it expects proves nothing about the POM
   */
  private String theAdapterToExpect() {

    final var adapter = handedOver("camunda8-adapter.version");
    if (adapter != null) {
      return adapter;
    }
    throw new AssertionError(
        "The system property 'camunda8-adapter.version' is not set, so there is nothing to "
            + "hold the published POM against. The build hands it to surefire from the property "
            + "the active line profile selected, see this module's pom.xml. A run which skipped "
            + "that configuration cannot answer the question this test asks.");

  }

  /** A value the build handed to surefire, or {@code null} where it handed none over. */
  private static String handedOver(
      final String name) {

    final var value = System.getProperty(name);
    return ((value == null) || value.isBlank())
        ? null
        : value;

  }

}
