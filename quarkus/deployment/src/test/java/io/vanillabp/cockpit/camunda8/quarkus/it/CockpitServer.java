package io.vanillabp.cockpit.camunda8.quarkus.it;

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
 * A Quarkus extension test initializes its test class TWICE - once while the application is being
 * built, and again inside the class loader of the running application - and those two class
 * loaders share no static state. Whichever copy of this class gets there first starts the server
 * and publishes its port; the second copy finds that port and becomes a reader of the first one,
 * asking it over HTTP what it received. Without that, a test would read an empty list while the
 * application reported into the other copy, which looks exactly like an extension that never
 * reported anything.
 * <p>
 * The waits are generous: a report is written while the cluster hands out a listener job and
 * dispatched once the cluster's searchable storage caught up, and that pipeline is the slowest
 * part of every test here.
 */
public final class CockpitServer {

  /**
   * One request the cockpit server received.
   *
   * @param path What was addressed
   * @param body What was sent
   */
  public record Request(
                        String path,
                        String body) {
  }

  /** Where the port of the server is published, so that the second copy finds it. */
  private static final String PORT_PROPERTY = "businesscockpit.test.server.port";

  /** What a reading copy asks for, and what the server answers it with. */
  private static final String RECEIVED_PATH = "/__received";

  /** Separates the two fields of one recorded request in that answer. */
  private static final String FIELD_SEPARATOR = " -> ";

  private static final List<Request> RECEIVED = Collections.synchronizedList(new LinkedList<>());

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
    server
        .createContext(
            "/",
            exchange -> {
              if (RECEIVED_PATH.equals(exchange.getRequestURI().getPath())) {
                answerWhatWasReceived(exchange);
                return;
              }
              final var body = new String(
                  exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              RECEIVED.add(new Request(exchange.getRequestURI().getPath(), body));
              exchange.sendResponseHeaders(200, -1);
              exchange.close();
            });
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return server.getAddress().getPort();

  }

  private static void answerWhatWasReceived(
      final HttpExchange exchange) throws IOException {

    final String answer;
    synchronized (RECEIVED) {
      answer = String
          .join(
              "\n",
              RECEIVED
                  .stream()
                  .map(request -> request.path() + FIELD_SEPARATOR + request.body())
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
   * @return Everything the server received so far, asked of whichever copy runs it
   */
  public static List<Request> received() {

    try {
      final var answer = HttpClient
          .newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create(baseUrl() + RECEIVED_PATH)).build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
      if (answer.isBlank()) {
        return List.of();
      }
      return answer
          .lines()
          .map(line -> line.split(FIELD_SEPARATOR, 2))
          .map(fields -> new Request(fields[0], fields.length > 1
              ? fields[1]
              : ""))
          .toList();
    } catch (final IOException e) {
      throw new IllegalStateException("Could not read what the cockpit server received", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading the cockpit server", e);
    }

  }

  /**
   * Waits for a request of one kind which is about the thing the caller means.
   *
   * @param pathSuffix What the path has to end with
   * @param bodyPart What the body has to carry
   * @return The request
   */
  public static Request awaitRequest(
      final String pathSuffix,
      final String bodyPart) {

    final var deadline = System.currentTimeMillis() + 240000;
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
   * @param pathSuffix What the paths have to end with
   * @return Everything received so far whose path ends that way
   */
  public static List<Request> matching(
      final String pathSuffix) {

    return received()
        .stream()
        .filter(request -> request.path().endsWith(pathSuffix))
        .toList();

  }

  private static void sleep() {

    try {
      Thread.sleep(200);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the cockpit server", e);
    }

  }

}
