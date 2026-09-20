package io.vanillabp.cockpit.camunda8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Element;

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
 * the flatten plugin's 'oss' mode ended, and this test is what keeps it ended.
 * <p>
 * The published POM is read from disk rather than derived, because deriving it would repeat the
 * reasoning the mistake was made in. It is the file the flatten plugin writes during
 * 'process-resources' and Maven installs and deploys in place of the source POM.
 * <p>
 * The client to expect is handed over by the build, see the surefire configuration in this
 * module's pom.xml. The VanillaBP Camunda 8 adapter carries the same guard and is about to
 * publish it as an assertion of its 'camunda8-adapter-test-support'. This class is written in
 * that shape, so adopting the assertion replaces two method bodies.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8PublishedPomTest {

  /** The POM which is installed and deployed for this module, written by the flatten plugin. */
  private static final Path PUBLISHED_POM = Path.of(".flattened-pom.xml");

  /** The repository every artifact of this build comes from, and the address all of them name. */
  private static final String REPOSITORY = "https://github.com/vanillabp/businesscockpit-camunda8-adapter";

  @Test
  @DisplayName("the published POM asks for the client of this release line")
  public void thePublishedPomAsksForTheClientOfThisLine() throws Exception {

    final var published = read();
    final var expected = clientOfThisBuild();
    final var client = dependencyVersion(published, "io.camunda", "camunda-client-java");

    if (expected.equals(client)) {
      return;
    }
    throw new AssertionError(
        ("The POM published for module %s of release line %s asks for Camunda client %s, but "
            + "this build was compiled against %s. An application takes its client from that "
            + "POM, so it would run code of one client against another one. A version reaches "
            + "the published POM only when the flatten plugin resolves it: check that "
            + "<flattenMode> in the parent pom.xml is still 'oss'.")
            .formatted(
                moduleOf(published),
                lineOfThisBuild(),
                client == null
                    ? "no version at all"
                    : client,
                expected));

  }

  @Test
  @DisplayName("the published POM leaves an application nothing of ours to inherit")
  public void thePublishedPomHasNoParentAndNoDependencyManagement() throws Exception {

    final var published = read();
    if ((published.getElementsByTagName("parent").getLength() == 0) && (published
        .getElementsByTagName("dependencyManagement").getLength() == 0)) {
      return;
    }
    throw new AssertionError(
        "The POM published for module %s of release line %s carries a parent or a "
            .formatted(moduleOf(published), lineOfThisBuild())
            + "dependencyManagement. Both are read when an application resolves its "
            + "dependencies, and both would hand it versions this repository picked for the "
            + "newest line, the protobuf pin of the parent pom.xml above all. What one line "
            + "needs is no business of another line's users, see decision 12 in the "
            + "repository's DECISIONS.md.");

  }

  @Test
  @DisplayName("every address in the published POM is one which opens")
  public void thePublishedPomNamesTheRepositoryAndNoModulePath() throws Exception {

    final var published = read();
    final var scm = childOf(published, "scm");
    final var wrongAddresses = new ArrayList<String>();
    checkAddress(wrongAddresses, "url", childText(published, "url"), REPOSITORY);
    checkAddress(wrongAddresses, "scm/connection", childText(scm, "connection"), "scm:git:"
        + REPOSITORY
        + ".git");
    checkAddress(
        wrongAddresses,
        "scm/developerConnection",
        childText(scm, "developerConnection"),
        "scm:git:"
            + REPOSITORY
            + ".git");
    checkAddress(wrongAddresses, "scm/url", childText(scm, "url"), REPOSITORY
        + "/tree/main");

    if (wrongAddresses.isEmpty()) {
      return;
    }
    throw new AssertionError(
        ("The POM published for module %s of release line %s names an address nobody can open: "
            + "%s. Maven appends the module path to the url and to all three scm elements a "
            + "child inherits, which turns every one of them into a page which does not exist. "
            + "The four 'child.*.inherit.append.path' attributes in the parent pom.xml switch "
            + "that off, so every artifact of this repository names the repository root.")
            .formatted(moduleOf(published), lineOfThisBuild(), String.join("; ", wrongAddresses)));

  }

  @Test
  @DisplayName("the published POM says nothing about where we deploy")
  public void thePublishedPomHasNoDistributionManagement() throws Exception {

    final var published = read();
    if (published
        .getElementsByTagName("distributionManagement")
        .getLength() == 0) {
      return;
    }
    throw new AssertionError(
        ("The POM published for module %s of release line %s carries a distributionManagement. "
            + "It names where we deploy, which is nothing a consumer can use, and on an artifact "
            + "which later sits on Maven Central it points a reader at GitHub Packages. The "
            + "source POM keeps it, because the deployment reads it from there; the published "
            + "one loses it through the <pomElements> configuration of the flatten plugin in "
            + "the parent pom.xml.")
            .formatted(moduleOf(published), lineOfThisBuild()));

  }

  private Element read() throws Exception {

    if (!Files.isRegularFile(PUBLISHED_POM)) {
      throw new AssertionError(
          "The published POM is missing at '%s'. The flatten plugin writes it in the phase "
              .formatted(PUBLISHED_POM.toAbsolutePath())
              + "'process-resources', so this test cannot run from an IDE which skipped it.");
    }
    final var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory
        .newDocumentBuilder()
        .parse(PUBLISHED_POM.toFile())
        .getDocumentElement();

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

  /** The Camunda 8 minor this build belongs to, for the messages above. */
  private String lineOfThisBuild() {

    final var line = System.getProperty("camunda8.line");
    return (line == null) || line.isBlank()
        ? "unknown"
        : line;

  }

  /**
   * The artifact the published POM belongs to. Read as a direct child of the project, because
   * a POM which wrongly kept its parent names that parent's artifact first.
   */
  private static String moduleOf(
      final Element project) {

    final var artifactId = childText(project, "artifactId");
    return artifactId == null
        ? "unknown"
        : artifactId;

  }

  /**
   * Notes an address which is not the one we wrote, so that one run reports all of them instead
   * of one per run.
   */
  private static void checkAddress(
      final List<String> wrongAddresses,
      final String element,
      final String found,
      final String expected) {

    if (expected.equals(found)) {
      return;
    }
    wrongAddresses.add(
        "%s says '%s' instead of '%s'".formatted(
            element,
            found == null
                ? "nothing at all"
                : found,
            expected));

  }

  /**
   * One element of the project, read as a direct child. The whole document would answer with a
   * license url or a plugin url as well.
   */
  private static Element childOf(
      final Element parent,
      final String tagName) {

    if (parent == null) {
      return null;
    }
    final var children = parent.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if ((children.item(i) instanceof Element element) && tagName.equals(element.getTagName())) {
        return element;
      }
    }
    return null;

  }

  /** The text of a direct child element, or {@code null} where the POM has none. */
  private static String childText(
      final Element parent,
      final String tagName) {

    final var child = childOf(parent, tagName);
    return child == null
        ? null
        : child
            .getTextContent()
            .trim();

  }

  /**
   * The version the published POM declares for one dependency, or {@code null} where it
   * declares the dependency without one.
   */
  private String dependencyVersion(
      final Element project,
      final String groupId,
      final String artifactId) {

    final var dependencies = project.getElementsByTagName("dependency");
    for (var i = 0; i < dependencies.getLength(); i++) {
      final var dependency = (Element) dependencies.item(i);
      if (groupId.equals(textOf(dependency, "groupId")) && artifactId
          .equals(textOf(dependency, "artifactId"))) {
        return textOf(dependency, "version");
      }
    }
    throw new AssertionError(
        ("The POM published for module %s of release line %s declares no dependency on %s:%s. "
            + "Either this module stopped using the Camunda client, and then the release lines "
            + "are about something else than they were, or this test reads the wrong file.")
            .formatted(moduleOf(project), lineOfThisBuild(), groupId, artifactId));

  }

  private static String textOf(
      final Element dependency,
      final String tagName) {

    final var elements = dependency.getElementsByTagName(tagName);
    return elements.getLength() == 0
        ? null
        : elements
            .item(0)
            .getTextContent()
            .trim();

  }

}
