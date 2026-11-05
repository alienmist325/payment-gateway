package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Last-resort handling.
 *
 * <p>Ordered last so that the specific advices ({@link ApiExceptionHandler},
 * {@link BankExceptionHandler}) always win; this one only sees what they did not claim.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class CommonExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CommonExceptionHandler.class);

  @ExceptionHandler(EventProcessingException.class)
  public ResponseEntity<ErrorResponse> handleException(EventProcessingException ex) {
    LOG.error("Exception happened", ex);
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ErrorResponse.malformed(HttpStatus.NOT_FOUND, "Page not found"));
  }

  /**
   * Catches anything the specific handlers did not, so no stack trace, SQL fragment or internal
   * class name can reach a merchant. The cause is logged in full for us instead.
   *
   * <p>Spring's own web exceptions carry a status and a safe message of their own and must keep
   * them: without this branch an unknown URL would be answered 500 rather than 404, because this
   * handler runs before Spring's {@code DefaultHandlerExceptionResolver} gets a chance.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    if (ex instanceof org.springframework.web.ErrorResponse springError) {
      HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
      LOG.warn("Request failed with {}", status, ex);
      return ResponseEntity.status(status)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(ErrorResponse.malformed(status, springError.getBody().getDetail()));
    }

    LOG.error("Unhandled exception while serving a request", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ErrorResponse.malformed(HttpStatus.INTERNAL_SERVER_ERROR,
            "The request could not be completed due to an unexpected error."));
  }
}
