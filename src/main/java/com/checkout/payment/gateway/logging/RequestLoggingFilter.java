package com.checkout.payment.gateway.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs one line as a request arrives and one as its response leaves, with the status and how long
 * the request took. Bodies are never logged: they carry the PAN and the CVV.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
  private static final String ACTUATOR_PATH = "/actuator";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long startedAt = System.nanoTime();
    LOG.info("--> {} {}", request.getMethod(), request.getRequestURI());
    try {
      filterChain.doFilter(request, response);
    } finally {
      LOG.info("<-- {} {} responded {} in {} ms", request.getMethod(), request.getRequestURI(),
          response.getStatus(), elapsedMillis(startedAt));
    }
  }

  /**
   * Health probes are polled constantly and would drown out the payment traffic.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith(ACTUATOR_PATH);
  }

  private static long elapsedMillis(long startedAt) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
  }
}
