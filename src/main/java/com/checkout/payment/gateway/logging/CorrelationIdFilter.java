package com.checkout.payment.gateway.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on the MDC, so every log line produced while handling a request can be tied
 * back to that request. The id is taken from the inbound header when the caller supplies one, and
 * echoed back on the response either way.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  public static final String CORRELATION_ID_KEY = "correlationId";

  private static final int MAX_LENGTH = 64;
  private static final String UNSAFE_CHARACTERS = "[^A-Za-z0-9_.:-]";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String correlationId = correlationIdOf(request);
    MDC.put(CORRELATION_ID_KEY, correlationId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(CORRELATION_ID_KEY);
    }
  }

  /**
   * A caller supplied id ends up in the logs, so anything that could forge a log line (or bloat
   * one) is stripped. An id left empty by that is replaced with a generated one.
   */
  private static String correlationIdOf(HttpServletRequest request) {
    String supplied = request.getHeader(CORRELATION_ID_HEADER);
    if (!StringUtils.hasText(supplied)) {
      return UUID.randomUUID().toString();
    }
    String sanitised = supplied.replaceAll(UNSAFE_CHARACTERS, "");
    if (sanitised.isEmpty()) {
      return UUID.randomUUID().toString();
    }
    return sanitised.length() > MAX_LENGTH ? sanitised.substring(0, MAX_LENGTH) : sanitised;
  }
}
