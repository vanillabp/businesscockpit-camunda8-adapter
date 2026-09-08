package io.vanillabp.cockpit.camunda8.springboot.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

/**
 * The cockpit server, played by the test: it records every request and answers every one of
 * them with a 200.
 * <p>
 * It is started once for the whole JVM, before any application boots, because an application
 * is configured with its address.
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

  private static final HttpServer SERVER;

  private static final List<Request> RECEIVED = Collections.synchronizedList(new LinkedList<>());

  /**
   * Kept apart from the rest and never forgotten: a workflow module registers itself once while
   * the application starts, long before the test asserting it runs.
   */
  private static final List<Request> REGISTRATIONS = Collections
      .synchronizedList(new LinkedList<>());

  static {
    try {
      SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (final IOException e) {
      throw new IllegalStateException("Could not start the cockpit server of the test", e);
    }
    SERVER
        .createContext(
            "/",
            exchange -> {
              final var body = new String(
                  exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              final var request = new Request(exchange.getRequestURI().getPath(), body);
              RECEIVED.add(request);
              if (request.path().contains("/workflow-module/")) {
                REGISTRATIONS.add(request);
              }
              exchange.sendResponseHeaders(200, -1);
              exchange.close();
            });
    SERVER.start();
  }

  private CockpitServer() {
  }

  /**
   * @return Where an application has to send its reports
   */
  public static String baseUrl() {

    return "http://localhost:%d".formatted(SERVER.getAddress().getPort());

  }

  /**
   * Forgets everything received so far.
   */
  public static void forgetRequests() {

    RECEIVED.clear();

  }

  /**
   * Waits for the registration of a workflow module.
   *
   * @return The registration
   */
  public static Request awaitRegistration() {

    final var deadline = System.currentTimeMillis() + 240000;
    while (System.currentTimeMillis() < deadline) {
      synchronized (REGISTRATIONS) {
        if (!REGISTRATIONS.isEmpty()) {
          return REGISTRATIONS.getFirst();
        }
      }
      sleep();
    }
    throw new AssertionError("No workflow module was registered");

  }

  /**
   * @return Everything received so far
   */
  public static List<Request> received() {

    synchronized (RECEIVED) {
      return List.copyOf(RECEIVED);
    }

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
            .formatted(pathSuffix, bodyPart, matching(pathSuffix).stream().map(Request::body).toList()));

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

    final var deadline = System.currentTimeMillis() + 240000;
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
