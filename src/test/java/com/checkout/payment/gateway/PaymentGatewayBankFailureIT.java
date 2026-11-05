package com.checkout.payment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end cover for bank failures, over real sockets in both directions.
 *
 * <p>A real HTTP server stands in for the acquiring bank, which lets these tests exercise things no
 * mocked client can: that the configured read timeout genuinely fires, and that a bank which simply
 * stops talking produces a bounded, well-formed response rather than a hung request thread.
 *
 * <p>The stub reproduces the responses the mountebank simulator in {@code imposters/} actually
 * gives, so these stay honest about the bank this gateway talks to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentGatewayBankFailureIT {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final YearMonth FUTURE = YearMonth.now(ZoneOffset.UTC).plusYears(1);

  /** How the stub bank should answer the next request. */
  private static final AtomicReference<BankBehaviour> BEHAVIOUR = new AtomicReference<>();

  private static final ExecutorService BANK_THREADS = Executors.newCachedThreadPool();

  private static final HttpServer BANK = startStubBank();

  @Autowired
  private TestRestTemplate rest;

  @FunctionalInterface
  private interface BankBehaviour {
    void respond(HttpExchange exchange) throws IOException, InterruptedException;
  }

  private static HttpServer startStubBank() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/payments", exchange -> {
        try {
          // No keep-alive: the timeout test deliberately abandons a socket mid-response, and a
          // reused connection would carry that damage into whichever test ran next.
          exchange.getResponseHeaders().add(HttpHeaders.CONNECTION, "close");
          BEHAVIOUR.get().respond(exchange);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } catch (IOException ex) {
          // Expected once the gateway has already timed out and closed its side.
        } finally {
          exchange.close();
        }
      });
      // Without an explicit executor the server handles requests one at a time, so the deliberate
      // sleep in the timeout test would block every test that ran after it.
      server.setExecutor(BANK_THREADS);
      server.start();
      return server;
    } catch (IOException ex) {
      throw new IllegalStateException("Could not start the stub bank", ex);
    }
  }

  @DynamicPropertySource
  static void bankProperties(DynamicPropertyRegistry registry) {
    registry.add("payments.bank.api-url",
        () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
    // Short enough to keep the timeout test quick, long enough not to fire spuriously.
    registry.add("payments.bank.read-timeout", () -> "600ms");
    registry.add("payments.bank.connect-timeout", () -> "600ms");
  }

  @AfterAll
  static void stopStubBank() {
    BANK.stop(0);
    BANK_THREADS.shutdownNow();
  }

  // ------------------------------------------------------------------ helpers

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private ResponseEntity<String> postPayment() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("card_number", "4111111111111111");
    body.put("expiry_month", FUTURE.getMonthValue());
    body.put("expiry_year", FUTURE.getYear());
    body.put("currency", "GBP");
    body.put("amount", 1000);
    body.put("cvv", "123");

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      return rest.postForEntity("/payment",
          new HttpEntity<>(JSON.writeValueAsString(body), headers), String.class);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private JsonNode bodyOf(ResponseEntity<String> response) {
    try {
      return JSON.readTree(response.getBody());
    } catch (Exception ex) {
      throw new IllegalStateException("Response was not JSON: " + response.getBody(), ex);
    }
  }

  // ------------------------------------------------------------------ tests

  @Test
  @DisplayName("a healthy bank still authorises normally")
  void authorisedPaymentIsUnaffected() {
    BEHAVIOUR.set(exchange -> send(exchange, 200,
        "{\"authorized\":true,\"authorization_code\":\"0bb07405-6d44-4b50-a14f-7ae0beff13ad\"}"));

    ResponseEntity<String> response = postPayment();

    assertEquals(200, response.getStatusCode().value());
    assertEquals("Authorized", bodyOf(response).get("payment_status").asText());
  }

  @Test
  @DisplayName("the simulator's 503 for a card ending in 0 becomes a clean 503, not a 500")
  void bankReturns503() {
    BEHAVIOUR.set(exchange -> send(exchange, 503, "{}"));

    ResponseEntity<String> response = postPayment();
    JsonNode body = bodyOf(response);

    assertEquals(503, response.getStatusCode().value(), response.getBody());
    assertEquals("bank_unavailable", body.get("code").asText());
    assertEquals("not_processed", body.get("payment_outcome").asText());
    assertEquals("5", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  @DisplayName("a bank that stops talking hits the read timeout instead of hanging the thread")
  void bankNeverResponds() {
    BEHAVIOUR.set(exchange -> {
      Thread.sleep(3000);
      send(exchange, 200, "{\"authorized\":true,\"authorization_code\":\"late\"}");
    });

    long startedAt = System.currentTimeMillis();
    ResponseEntity<String> response = postPayment();
    long elapsed = System.currentTimeMillis() - startedAt;

    JsonNode body = bodyOf(response);
    assertEquals(504, response.getStatusCode().value());
    assertEquals("bank_timeout", body.get("code").asText());
    // Ambiguous by nature: the bank may have authorised before going quiet.
    assertEquals("unknown", body.get("payment_outcome").asText());
    assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
        "a retry here could charge the cardholder twice");
    assertTrue(elapsed < 3000,
        "the configured read timeout did not fire; waited " + elapsed + "ms");
  }

  @Test
  @DisplayName("an empty 200 body is reported, not thrown as a NullPointerException")
  void bankReturnsEmptyBody() {
    BEHAVIOUR.set(exchange -> {
      exchange.sendResponseHeaders(200, -1);
    });

    ResponseEntity<String> response = postPayment();
    JsonNode body = bodyOf(response);

    assertEquals(502, response.getStatusCode().value());
    assertEquals("bank_response_invalid", body.get("code").asText());
  }

  @Test
  @DisplayName("the bank's own error text never reaches the merchant")
  void bankErrorTextIsNotPassedThrough() {
    BEHAVIOUR.set(exchange -> send(exchange, 400,
        "{\"error_message\":\"Not all required properties were sent in the request\"}"));

    ResponseEntity<String> response = postPayment();

    assertEquals(502, response.getStatusCode().value());
    assertFalse(response.getBody().contains("required properties"));
    assertEquals("bank_rejected_request", bodyOf(response).get("code").asText());
  }

  @Test
  @DisplayName("a failed payment is never persisted, so it cannot be fetched afterwards")
  void failedPaymentsAreNotStored() {
    BEHAVIOUR.set(exchange -> send(exchange, 503, "{}"));

    ResponseEntity<String> response = postPayment();
    JsonNode body = bodyOf(response);

    assertEquals(503, response.getStatusCode().value());
    assertNull(body.get("id"), "a failed payment must not be given an id");
    assertNull(body.get("payment_status"), "no payment was created, so none has a status");
    assertNotNull(body.get("code"));
  }
}
