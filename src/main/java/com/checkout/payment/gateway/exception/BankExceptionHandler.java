package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a failed call to the acquiring bank into the merchant-facing response described by its
 * {@link BankFailure}.
 *
 * <p>The bank's own status and error body are logged here and go no further: they are written for
 * us, can name internal endpoints, and would tell a merchant nothing they can act on.
 */
@RestControllerAdvice
public class BankExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(BankExceptionHandler.class);

  @ExceptionHandler(BankException.class)
  public ResponseEntity<ErrorResponse> handleBankFailure(BankException ex) {
    BankFailure failure = ex.failure();

    LOG.error("Bank call failed [{}] -> responding {}; bankStatus={}, bankBody={}",
        failure.code(), failure.status().value(), ex.bankStatusCode(), ex.bankBody(), ex);

    ResponseEntity.BodyBuilder builder = ResponseEntity.status(failure.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON);

    if (failure.retryAfterSeconds() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(failure.retryAfterSeconds()));
    }

    return builder.body(ErrorResponse.upstream(failure));
  }
}
