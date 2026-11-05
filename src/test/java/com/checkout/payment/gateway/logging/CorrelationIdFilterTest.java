package com.checkout.payment.gateway.logging;

import static com.checkout.payment.gateway.logging.CorrelationIdFilter.CORRELATION_ID_HEADER;
import static com.checkout.payment.gateway.logging.CorrelationIdFilter.CORRELATION_ID_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The correlation id that ties a request's log lines together.
 */
class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("An id is generated when the caller does not supply one")
  void generatesAnIdWhenNoneIsSupplied() throws Exception {
    MockHttpServletResponse response = filterWith(null).response();

    String correlationId = response.getHeader(CORRELATION_ID_HEADER);
    assertNotNull(correlationId);
    assertDoesNotThrow(() -> UUID.fromString(correlationId));
  }

  @Test
  @DisplayName("The caller's own id is kept, so a trace spans both systems")
  void keepsTheSuppliedId() throws Exception {
    Captured captured = filterWith("order-4921");

    assertEquals("order-4921", captured.mdcValue());
    assertEquals("order-4921", captured.response().getHeader(CORRELATION_ID_HEADER));
  }

  @Test
  @DisplayName("An id that could forge a log line is stripped of the offending characters")
  void stripsCharactersThatCouldForgeALogLine() throws Exception {
    Captured captured = filterWith("abc\n2026-01-01 INFO Payment authorized");

    String correlationId = captured.mdcValue();
    assertFalse(correlationId.contains("\n"));
    assertFalse(correlationId.contains(" "));
    assertTrue(correlationId.startsWith("abc"));
  }

  @Test
  @DisplayName("An id left empty by that sanitising is replaced with a generated one")
  void replacesAnIdThatSanitisesToNothing() throws Exception {
    String correlationId = filterWith("\n\t ").mdcValue();

    assertDoesNotThrow(() -> UUID.fromString(correlationId));
  }

  @Test
  @DisplayName("An over-long id is truncated rather than bloating every line")
  void truncatesAnOverLongId() throws Exception {
    String correlationId = filterWith("x".repeat(500)).mdcValue();

    assertEquals(64, correlationId.length());
  }

  @Test
  @DisplayName("The MDC is cleared afterwards, so the id cannot leak onto the next request")
  void clearsTheMdcAfterTheRequest() throws Exception {
    filterWith("order-4921");

    assertNull(MDC.get(CORRELATION_ID_KEY));
  }

  @Test
  @DisplayName("The MDC is cleared even when handling the request fails")
  void clearsTheMdcWhenTheRequestFails() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payment");
    FilterChain failing = (req, res) -> {
      throw new ServletException("handler blew up");
    };

    assertThrows(ServletException.class,
        () -> filter.doFilter(request, new MockHttpServletResponse(), failing));
    assertNull(MDC.get(CORRELATION_ID_KEY));
  }

  private Captured filterWith(String suppliedId) throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payment");
    if (suppliedId != null) {
      request.addHeader(CORRELATION_ID_HEADER, suppliedId);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenOnTheMdc = new AtomicReference<>();

    filter.doFilter(request, response,
        (req, res) -> seenOnTheMdc.set(MDC.get(CORRELATION_ID_KEY)));

    return new Captured(response, seenOnTheMdc.get());
  }

  private record Captured(MockHttpServletResponse response, String mdcValue) {

  }
}
