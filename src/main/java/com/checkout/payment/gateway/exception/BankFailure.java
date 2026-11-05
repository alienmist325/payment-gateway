package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.enums.PaymentOutcome;
import org.springframework.http.HttpStatus;

/**
 * Every way a call to the acquiring bank can fail, and the response each one should produce.
 *
 * <p>This enum is the single place where the mapping from "what went wrong talking to the bank" to
 * "what the merchant sees" is decided. Each constant carries:
 *
 * <ul>
 *   <li>the HTTP status we return to the merchant;
 *   <li>a {@code code} the merchant can branch on programmatically, so they never have to parse
 *       our prose;
 *   <li>a {@code detail} sentence that is safe to show, naming no internal host, credential or
 *       stack frame;
 *   <li>the {@link PaymentOutcome}, which tells the merchant whether re-submitting is safe;
 *   <li>{@code retryAfterSeconds}, set only where we can honestly suggest when to come back.
 * </ul>
 *
 * <p>The status choices follow the usual proxy semantics: 504 when the bank ran out of time, 503
 * when the bank is reachable-but-down or we could not reach it at all (both transient, so the
 * merchant should retry later), and 502 when the bank answered but the exchange was broken in a way
 * retrying will not fix — a contract, configuration or payload fault that needs us to act.
 *
 * <p>No payment is ever persisted for any of these, because
 * {@code PaymentGatewayService} only stores a payment after the bank has answered successfully.
 */
public enum BankFailure {

  /**
   * The request never left our side: connection refused, DNS failure, or a TLS handshake that
   * failed. Nothing reached the bank, so the merchant can safely retry.
   */
  BANK_UNREACHABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "bank_unreachable",
      "The acquiring bank could not be reached. No payment was taken and the request can be "
          + "safely retried.",
      PaymentOutcome.NOT_PROCESSED,
      5),

  /**
   * We gave up waiting. A read timeout is genuinely ambiguous: the bank may have authorised the
   * payment and simply been too slow to tell us, so the outcome is unknown and the merchant must
   * reconcile rather than blindly retry.
   */
  BANK_TIMEOUT(
      HttpStatus.GATEWAY_TIMEOUT,
      "bank_timeout",
      "The acquiring bank did not respond in time. The payment may or may not have been taken; "
          + "check its status with the bank before retrying.",
      PaymentOutcome.UNKNOWN,
      null),

  /**
   * The bank answered 502, 503 or 504. It is telling us it is not serving requests, so it did not
   * authorise anything.
   */
  BANK_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "bank_unavailable",
      "The acquiring bank is temporarily unavailable. No payment was taken and the request can be "
          + "safely retried.",
      PaymentOutcome.NOT_PROCESSED,
      5),

  /**
   * The bank answered 500 or some other 5xx. Unlike {@link #BANK_UNAVAILABLE} this can be thrown
   * after the bank has already done work, so the outcome is unknown.
   */
  BANK_ERROR(
      HttpStatus.BAD_GATEWAY,
      "bank_error",
      "The acquiring bank returned an error. The payment may or may not have been taken; check "
          + "its status with the bank before retrying.",
      PaymentOutcome.UNKNOWN,
      null),

  /**
   * The bank is throttling us. It rejected the request outright, so nothing was processed.
   */
  BANK_RATE_LIMITED(
      HttpStatus.SERVICE_UNAVAILABLE,
      "bank_rate_limited",
      "The acquiring bank is currently rate limiting requests. No payment was taken; please retry "
          + "shortly.",
      PaymentOutcome.NOT_PROCESSED,
      10),

  /**
   * The bank rejected the shape of our request (400, 404, 405, 415, 422 and friends). By this point
   * the merchant's input has already passed our own validation, so this is a gateway-to-bank
   * contract fault, not the merchant's mistake — hence 502 rather than passing a 400 through.
   */
  BANK_REJECTED_REQUEST(
      HttpStatus.BAD_GATEWAY,
      "bank_rejected_request",
      "The acquiring bank rejected the request sent by this gateway. No payment was taken. This is "
          + "a gateway fault and retrying is unlikely to help.",
      PaymentOutcome.NOT_PROCESSED,
      null),

  /**
   * The bank refused our credentials (401 or 403). A deployment or configuration fault on our side.
   * The detail deliberately says nothing about which credential or endpoint was refused.
   */
  BANK_UNAUTHORIZED(
      HttpStatus.BAD_GATEWAY,
      "bank_unauthorized",
      "This gateway is not authorised with the acquiring bank. No payment was taken. This is a "
          + "gateway fault and retrying is unlikely to help.",
      PaymentOutcome.NOT_PROCESSED,
      null),

  /**
   * A 2xx whose body we could not read at all: malformed JSON, or a content type we have no
   * converter for (typically an HTML error page served by something in between). The bank may have
   * authorised the payment, so the outcome is unknown.
   */
  BANK_RESPONSE_UNREADABLE(
      HttpStatus.BAD_GATEWAY,
      "bank_response_unreadable",
      "The response from the acquiring bank could not be read. The payment may or may not have "
          + "been taken; check its status with the bank before retrying.",
      PaymentOutcome.UNKNOWN,
      null),

  /**
   * A 2xx we could read but cannot act on: an empty body, a missing {@code authorized} flag, or an
   * authorisation with no authorisation code. Treating any of these as a decline would be a silent
   * data-loss bug, so we fail loudly instead.
   */
  BANK_RESPONSE_INVALID(
      HttpStatus.BAD_GATEWAY,
      "bank_response_invalid",
      "The acquiring bank returned a response this gateway cannot interpret. The payment may or "
          + "may not have been taken; check its status with the bank before retrying.",
      PaymentOutcome.UNKNOWN,
      null);

  private final HttpStatus status;
  private final String code;
  private final String detail;
  private final PaymentOutcome outcome;
  private final Integer retryAfterSeconds;

  BankFailure(HttpStatus status, String code, String detail, PaymentOutcome outcome,
      Integer retryAfterSeconds) {
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.outcome = outcome;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * Classifies an error status returned by the bank.
   */
  public static BankFailure ofBankStatus(int statusCode) {
    if (statusCode == HttpStatus.UNAUTHORIZED.value()
        || statusCode == HttpStatus.FORBIDDEN.value()) {
      return BANK_UNAUTHORIZED;
    }
    if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
      return BANK_RATE_LIMITED;
    }
    if (statusCode == HttpStatus.BAD_GATEWAY.value()
        || statusCode == HttpStatus.SERVICE_UNAVAILABLE.value()
        || statusCode == HttpStatus.GATEWAY_TIMEOUT.value()) {
      return BANK_UNAVAILABLE;
    }
    if (statusCode >= 500) {
      return BANK_ERROR;
    }
    if (statusCode >= 400) {
      return BANK_REJECTED_REQUEST;
    }
    // A 1xx or 3xx reaching here means the client followed no redirect and we have no body to
    // read; it is as unusable as a malformed one.
    return BANK_RESPONSE_INVALID;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String detail() {
    return detail;
  }

  public PaymentOutcome outcome() {
    return outcome;
  }

  /**
   * Seconds to advertise in a {@code Retry-After} header, or {@code null} when we have no honest
   * basis for suggesting one.
   */
  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
