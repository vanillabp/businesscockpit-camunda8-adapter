package io.vanillabp.cockpit.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Camunda 8 adapter on the classpath belongs to the line this build produces, and the Camunda
 * client on the classpath is the one that adapter was built against.
 * <p>
 * This repository names no client version. The client arrives through the adapter, so the adapter
 * decides it. That only works while the adapter is the one of this line. Every line reads the
 * adapter snapshot of its own line. But a version set by hand, or an adapter somebody installed
 * into a shared local repository, can still put the adapter of another line on the classpath. A
 * build of line 8.8 against the adapter of 8.10 compiles, and it runs the 8.10 client while its
 * version says 8.8. This test makes that build stop and say what to do.
 * <p>
 * The second test catches the other way to lose the client: something in this build managing
 * <code>io.camunda:camunda-client-java</code> again, a BOM or an entry somebody adds. Then the
 * client on the classpath is no longer the adapter's, and the cluster the integration tests start
 * is still the adapter's. See decision 32 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AdapterOfThisLineTest {

  /** Where the Camunda client keeps its own version, written by Maven when it built the jar. */
  private static final String CLIENT_POM_PROPERTIES = "/META-INF/maven/io.camunda/camunda-client-java/pom.properties";

  @Test
  @DisplayName("the Camunda 8 adapter on the classpath was built for this line")
  public void theAdapterWasBuiltForThisLine() {

    final var line = System.getProperty("camunda8.line");
    assertEquals(
        line,
        Camunda8ReleaseLine.id(),
        () -> ("This build produces line %s, but the Camunda 8 adapter %s on the classpath was "
            + "built for line %s. The Camunda client comes from that adapter, so this build "
            + "would compile and test line %s against the client %s. The adapter of line %s "
            + "is the snapshot 2.0.0-%s-SNAPSHOT: build again with '--update-snapshots', or "
            + "install the adapter of that line from its clone, as AGENTS.md shows.")
            .formatted(
                line,
                System.getProperty("camunda8-adapter.version"),
                Camunda8ReleaseLine.id(),
                line,
                Camunda8ReleaseLine.clientVersion(),
                line,
                line));

  }

  @Test
  @DisplayName("the Camunda client on the classpath is the one of the adapter")
  public void theClientIsTheOneOfTheAdapter() throws IOException {

    assertEquals(
        Camunda8ReleaseLine.clientVersion(),
        theClientOnTheClasspath(),
        "The Camunda 8 adapter on the classpath was built against the client named first, "
            + "and this build runs the client named second. Something in this build manages "
            + "io.camunda:camunda-client-java, and nothing here may: the cluster the "
            + "integration tests start follows the adapter's client, so the tests would run "
            + "one version against the other. Find it with "
            + "'mvn -pl core -am dependency:tree -Dverbose -Dincludes=io.camunda'.");

  }

  /** The version of the Camunda client jar this test was loaded with. */
  private static String theClientOnTheClasspath() throws IOException {

    final var properties = new Properties();
    try (var file = CamundaClient.class.getResourceAsStream(CLIENT_POM_PROPERTIES)) {
      if (file == null) {
        throw new AssertionError(
            "The Camunda client jar carries no '%s', so this test cannot tell its version."
                .formatted(CLIENT_POM_PROPERTIES));
      }
      properties.load(file);
    }
    return properties.getProperty("version");

  }

}
