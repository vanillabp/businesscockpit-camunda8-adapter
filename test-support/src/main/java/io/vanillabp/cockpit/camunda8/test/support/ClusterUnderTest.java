package io.vanillabp.cockpit.camunda8.test.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;

/**
 * The Camunda 8 cluster the integration tests run against, on both platforms.
 * <p>
 * The image is not written into the tests. It is filtered into
 * {@code camunda8-cluster.properties} at build time from the Camunda client the active
 * release line pins ({@code camunda8.version}), so activating another line moves the
 * client and the cluster together. That is what makes the supported cluster versions of
 * the README provable instead of claimed: a line's tests meet the oldest cluster its
 * artifacts accept.
 * <p>
 * Override for a single run with {@code mvn verify -Dcamunda8.cluster.image=...}. The
 * property is resolved while the test resources are filtered, so a run from the IDE uses
 * whatever the last Maven build wrote.
 * <p>
 * The image is {@code camunda/camunda}, the orchestration cluster of Camunda 8, and not
 * the older {@code camunda/zeebe}: the latter received no tags beyond 8.9.11 and none at
 * all for 8.10, so a per-line matrix cannot be built on it.
 * <p>
 * Every cluster here brings secondary storage, because the adapter serves no other kind:
 * a cluster which cannot be searched ends the boot of the application under test. So there
 * are two flavours, and every test class pays for an Elasticsearch beside its Zeebe:
 * {@link #cluster} for a workflow module which needs no tenant, and
 * {@link #clusterWithTenants} for the default name-clash avoidance, whose tenants Camunda
 * only serves to an authenticated caller.
 */
public final class ClusterUnderTest {

  private static final String RESOURCE = "/camunda8-cluster.properties";

  private static final String IMAGE = readImage();

  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

  private ClusterUnderTest() {
    // static helper
  }

  /**
   * @return The image of the cluster under test, e.g. {@code camunda/camunda:8.8.31}.
   */
  public static DockerImageName image() {

    return DockerImageName.parse(IMAGE);

  }

  /**
   * The secondary storage of the cluster, reachable in {@code network} under the alias
   * {@code elasticsearch}. Every integration test class of this module declares one as a
   * {@code @Container} field of its own and hands it to {@link #cluster}, which is what makes
   * the pair start in the right order.
   *
   * @param network The network shared with the cluster container.
   * @return A container to be used as a Testcontainers {@code @Container} field.
   */
  public static GenericContainer<?> elasticsearch(
      final Network network) {

    return new GenericContainer<>(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.0"))
        .withNetwork(network)
        .withNetworkAliases("elasticsearch")
        .withEnv("discovery.type", "single-node")
        .withEnv("xpack.security.enabled", "false")
        .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g")
        .withExposedPorts(9200)
        .waitingFor(Wait
            .forHttp("/_cluster/health")
            .forPort(9200)
            .forStatusCode(200)
            .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /**
   * The cluster of a test, exporting into the Elasticsearch of {@link #elasticsearch}.
   * <p>
   * The readiness probe turns UP only once the partition leader accepts deployments,
   * which avoids a transient 503 on the first deploy at startup.
   *
   * @param network      The network shared with the Elasticsearch container.
   * @param elasticsearch The Elasticsearch container, started first.
   * @return A container to be used as a Testcontainers {@code @Container} field.
   */
  public static GenericContainer<?> cluster(
      final Network network,
      final Startable elasticsearch) {

    return new GenericContainer<>(image())
        .withLogConsumer(ClusterLog.of("cluster"))
        .withNetwork(network)
        .dependsOn(elasticsearch)
        .withExposedPorts(8080, 26500, 9600)
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", "elasticsearch")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_URL", "http://elasticsearch:9200")
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI", "true")
        .waitingFor(Wait
            .forHttp("/actuator/health/readiness")
            .forPort(9600)
            .forStatusCode(200)
            .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /**
   * The cluster of a test which needs TENANTS - what the default name-clash avoidance
   * (<code>by-adapter</code>) deploys into.
   * <p>
   * Camunda refuses to start with multi-tenancy on and its API unprotected, so this cluster
   * asks for credentials where the other one asks for none, and it is given one user with
   * the admin role. A test using it configures its adapter with {@link #USERNAME} and
   * {@link #PASSWORD} and creates the tenant with {@link #createTenant} BEFORE the
   * application boots: the adapter looks a tenant up before it deploys into it.
   *
   * @param network The network shared with the Elasticsearch container.
   * @param elasticsearch The Elasticsearch container, started first.
   * @return A container to be used as a Testcontainers {@code @Container} field.
   */
  public static GenericContainer<?> clusterWithTenants(
      final Network network,
      final Startable elasticsearch) {

    return new GenericContainer<>(image())
        .withLogConsumer(ClusterLog.of("cluster"))
        .withNetwork(network)
        .dependsOn(elasticsearch)
        .withExposedPorts(8080, 26500, 9600)
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", "elasticsearch")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_URL", "http://elasticsearch:9200")
        .withEnv("CAMUNDA_SECURITY_MULTITENANCY_CHECKSENABLED", "true")
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_METHOD", "BASIC")
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME", USERNAME)
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD", PASSWORD)
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME", "Demo")
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL", "demo@example.com")
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0", USERNAME)
        .waitingFor(Wait
            .forHttp("/actuator/health/readiness")
            .forPort(9600)
            .forStatusCode(200)
            .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /** The user of {@link #clusterWithTenants}, which is its only one. */
  public static final String USERNAME = "demo";

  /**
   * @see #USERNAME
   */
  public static final String PASSWORD = "demo";

  /**
   * Creates a tenant in a {@link #clusterWithTenants} and makes its user a member.
   * <p>
   * Both steps are needed: a tenant nobody belongs to accepts no command of that user, and
   * a deployment into a tenant which does not exist is refused by the adapter before it is
   * refused by the cluster.
   *
   * @param restAddress The cluster's REST address
   * @param tenantId The tenant, which is the workflow module id unless the adapter names
   *          one
   */
  public static void createTenant(
      final String restAddress,
      final String tenantId) {

    send(
        HttpRequest
            .newBuilder(URI.create(restAddress
                + "/v2/tenants"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers
                    .ofString(
                        "{\"tenantId\":\"%s\",\"name\":\"%s\"}".formatted(tenantId, tenantId))));
    send(
        HttpRequest
            .newBuilder(URI.create("%s/v2/tenants/%s/users/%s".formatted(restAddress, tenantId, USERNAME)))
            .PUT(HttpRequest.BodyPublishers.noBody()));

  }

  private static void send(
      final HttpRequest.Builder request) {

    final var credentials = Base64
        .getEncoder()
        .encodeToString("%s:%s".formatted(USERNAME, PASSWORD).getBytes(StandardCharsets.UTF_8));
    try {
      final var response = HttpClient
          .newHttpClient()
          .send(
              request.header("Authorization", "Basic "
                  + credentials).build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new IllegalStateException(
            "The cluster answered %d to '%s': %s"
                .formatted(response.statusCode(), request.build().uri(), response.body()));
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot reach the cluster under test", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while setting the cluster up", e);
    }

  }

  private static String readImage() {

    final var properties = new Properties();
    try (var resource = ClusterUnderTest.class.getResourceAsStream(RESOURCE)) {
      if (resource == null) {
        throw new IllegalStateException(
            "'%s' is missing from the test classpath. Maven filters it into the test resources of every module running integration tests, so build the module once ('mvn test-compile') before running one from the IDE."
                .formatted(RESOURCE));
      }
      properties.load(resource);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read '%s'".formatted(RESOURCE), e);
    }

    final var image = properties.getProperty("cluster.image");
    if ((image == null) || image.isBlank() || image.contains("${")) {
      throw new IllegalStateException(
          "'cluster.image' of '%s' is '%s' instead of an image. The test resources of this module have to be filtered: check the 'testResources' section of the module's pom.xml and the property 'camunda8.cluster.image' of the parent pom."
              .formatted(RESOURCE, image));
    }
    return image;

  }

}
