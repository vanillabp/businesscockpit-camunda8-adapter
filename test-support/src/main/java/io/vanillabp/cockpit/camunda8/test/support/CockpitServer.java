package io.vanillabp.cockpit.camunda8.test.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The cockpit server, played by the test: it records every request and answers every one of them
 * with a 200.
 * <p>
 * Everything a test asks it is asked over HTTP, of whichever copy of this class started the
 * server. That detour exists for the Quarkus tests: an extension test initializes its test class
 * TWICE - once while the application is being built, and again inside the class loader of the
 * running application - and those two copies share no static state. Without it a test would read
 * an empty list while the application reported into the other copy, which looks exactly like an
 * extension which never reported anything. On Spring Boot there is one copy and the detour is a
 * call to localhost.
 * <p>
 * The waits are generous: a report is written while the cluster hands out a listener job and
 * dispatched once the cluster's searchable storage caught up, and that pipeline is the slowest
 * part of every test here.
 */
public final class CockpitServer {

  /**
   * One request the server received.
   *
   * @param path What was called
   * @param body What it carried
   */
  public record Request(
                        String path,
                        String body) {
  }

  /** Where the port of the server is published, so that a second copy finds it. */
  private static final String PORT_PROPERTY = "businesscockpit.test.server.port";

  /** What a copy asks for to read what arrived, and what the server answers it with. */
  private static final String RECEIVED_PATH = "/__received";

  /** What a copy asks for to read the registrations, which are never forgotten. */
  private static final String REGISTRATIONS_PATH = "/__registrations";

  /** What a copy asks for to forget everything received so far. */
  private static final String FORGET_PATH = "/__forget";

  /** Separates the two fields of one recorded request in those answers. */
  private static final String FIELD_SEPARATOR = " -> ";

  /** How long a test waits for something to arrive. */
  private static final long WAIT_MILLIS = 240000;

  private static final List<Request> RECEIVED = Collections.synchronizedList(new LinkedList<>());

  /**
   * Kept apart from the rest and never forgotten: a workflow module registers itself once while
   * the application starts, long before the test asserting it runs.
   */
  private static final List<Request> REGISTRATIONS = Collections
      .synchronizedList(new LinkedList<>());

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static final int PORT = startOrJoin();

  private CockpitServer() {
  }

  /**
   * Starts the server unless another copy of this class already did.
   *
   * @return The port every copy talks to
   */
  private static int startOrJoin() {

    final var published = System.getProperty(PORT_PROPERTY);
    if (published != null) {
      return Integer.parseInt(published);
    }
    final HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (final IOException e) {
      throw new IllegalStateException("Could not start the cockpit server of the test", e);
    }
    server.createContext("/", CockpitServer::answer);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return server.getAddress().getPort();

  }

  private static void answer(
      final HttpExchange exchange) throws IOException {

    final var path = exchange.getRequestURI().getPath();
    if (RECEIVED_PATH.equals(path)) {
      answerWith(exchange, RECEIVED);
      return;
    }
    if (REGISTRATIONS_PATH.equals(path)) {
      answerWith(exchange, REGISTRATIONS);
      return;
    }
    if (FORGET_PATH.equals(path)) {
      RECEIVED.clear();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
      return;
    }
    final var body = new String(
        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final var request = new Request(path, body);
    RECEIVED.add(request);
    if (path.contains("/workflow-module/")) {
      REGISTRATIONS.add(request);
    }
    exchange.sendResponseHeaders(200, -1);
    exchange.close();

  }

  private static void answerWith(
      final HttpExchange exchange,
      final List<Request> requests) throws IOException {

    final String answer;
    synchronized (requests) {
      answer = String
          .join(
              "\n",
              requests
                  .stream()
                  .map(
                      request -> request.path() + FIELD_SEPARATOR
                      // one request is one line, so a body which carries a line break of
                      // its own is written as text rather than as that break
                          + request.body().replace("\\", "\\\\").replace("\n", "\\n"))
                  .toList());
    }
    final var bytes = answer.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();

  }

  /**
   * @return Where an application has to send its reports
   */
  public static String baseUrl() {

    return "http://localhost:%d".formatted(PORT);

  }

  /**
   * Forgets everything received so far. The registrations are kept.
   */
  public static void forgetRequests() {

    ask(FORGET_PATH);

  }

  /**
   * @return Everything the server received since it was last asked to forget
   */
  public static List<Request> received() {

    return requestsOf(ask(RECEIVED_PATH));

  }

  /**
   * Waits for the registration of a workflow module.
   *
   * @return The registration
   */
  public static Request awaitRegistration() {

    final var deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      final var registrations = requestsOf(ask(REGISTRATIONS_PATH));
      if (!registrations.isEmpty()) {
        return registrations.getFirst();
      }
      sleep();
    }
    throw new AssertionError("No workflow module was registered");

  }

  /**
   * Waits for a request whose path ends with the given text.
   *
   * @param pathSuffix What the path has to end with
   * @return The request
   */
  public static Request awaitRequest(
      final String pathSuffix) {

    return awaitRequests(pathSuffix, 1).getFirst();

  }

  /**
   * Waits for a request of one kind which is about the thing the caller means.
   * <p>
   * Every test of a class shares this server, and a report of an earlier test may arrive after
   * that test forgot what it had seen - the dispatch of an entry outlives the test which caused
   * it. So a test which asserts content waits for the request carrying it rather than for the
   * next one of its kind.
   *
   * @param pathSuffix What the path has to end with
   * @param bodyPart What the body has to carry
   * @return The request
   */
  public static Request awaitRequest(
      final String pathSuffix,
      final String bodyPart) {

    final var deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      final var match = matching(pathSuffix)
          .stream()
          .filter(request -> request.body().contains(bodyPart))
          .findFirst();
      if (match.isPresent()) {
        return match.get();
      }
      sleep();
    }
    throw new AssertionError(
        "No request ending in '%s' carried '%s'. Received: %s"
            .formatted(pathSuffix, bodyPart, received().stream().map(Request::path).toList()));

  }

  /**
   * Waits until the given number of requests of one kind arrived.
   *
   * @param pathSuffix What the paths have to end with
   * @param count How many are expected
   * @return The requests, in the order they arrived
   */
  public static List<Request> awaitRequests(
      final String pathSuffix,
      final int count) {

    final var deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      final var matches = matching(pathSuffix);
      if (matches.size() >= count) {
        return matches;
      }
      sleep();
    }
    throw new AssertionError(
        "Fewer than %d requests ending in '%s' arrived. Received: %s"
            .formatted(count, pathSuffix, received().stream().map(Request::path).toList()));

  }

  /**
   * @param pathSuffix What the paths have to end with
   * @return What arrived so far and ends in that
   */
  public static List<Request> matching(
      final String pathSuffix) {

    return received()
        .stream()
        .filter(request -> request.path().endsWith(pathSuffix))
        .toList();

  }

  /**
   * Waits until nothing arrived for a moment, so that a test asserting that something is NOT
   * reported does not pass by being quick.
   */
  public static void awaitQuiet() {

    final var deadline = System.currentTimeMillis() + 5000;
    var lastCount = -1;
    while (System.currentTimeMillis() < deadline) {
      final var count = received().size();
      if (count == lastCount) {
        return;
      }
      lastCount = count;
      sleep();
    }

  }

  private static String ask(
      final String path) {

    try {
      return CLIENT
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl() + path)).build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (final IOException e) {
      throw new IllegalStateException("Could not read what the cockpit server received", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading the cockpit server", e);
    }

  }

  private static List<Request> requestsOf(
      final String answer) {

    if (answer.isBlank()) {
      return List.of();
    }
    return answer
        .lines()
        .map(line -> line.split(FIELD_SEPARATOR, 2))
        .map(
            fields -> new Request(
                fields[0], fields.length > 1
                    ? unescaped(fields[1])
                    : ""))
        .toList();

  }

  /**
   * The inverse of what {@link #answerWith} writes, read left to right - a body which carried a
   * backslash of its own is escaped there as well, so replacing the two sequences one after the
   * other would decode a body containing <code>\n</code> into a line break.
   *
   * @param line What one request's body was written as
   * @return The body
   */
  private static String unescaped(
      final String line) {

    final var body = new StringBuilder(line.length());
    var escaped = false;
    for (final var character : line.toCharArray()) {
      if (escaped) {
        body.append(character == 'n'
            ? '\n'
            : character);
        escaped = false;
      } else if (character == '\\') {
        escaped = true;
      } else {
        body.append(character);
      }
    }
    return body.toString();

  }

  private static void sleep() {

    try {
      Thread.sleep(250);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the cockpit server", e);
    }

  }

}
