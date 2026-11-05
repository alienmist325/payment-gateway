package com.checkout.payment.gateway.exception;

/**
 * A call to the acquiring bank failed.
 *
 * <p>The {@link BankFailure} decides what the merchant sees. The diagnostic fields on this
 * exception ({@link #bankStatusCode()} and {@link #bankBody()}) are for our logs only and are never
 * serialised into a response, because the bank's own error text is written for us, not for the
 * merchant, and may name internal endpoints.
 */
public class BankException extends RuntimeException {

  private final transient BankFailure failure;
  private final Integer bankStatusCode;
  private final String bankBody;

  public BankException(BankFailure failure, String diagnostic, Throwable cause) {
    this(failure, diagnostic, cause, null, null);
  }

  public BankException(BankFailure failure, String diagnostic, Throwable cause,
      Integer bankStatusCode, String bankBody) {
    super(diagnostic, cause);
    this.failure = failure;
    this.bankStatusCode = bankStatusCode;
    this.bankBody = bankBody;
  }

  public static BankException of(BankFailure failure, String diagnostic) {
    return new BankException(failure, diagnostic, null);
  }

  public BankFailure failure() {
    return failure;
  }

  /**
   * The status the bank returned, or {@code null} when it never answered.
   */
  public Integer bankStatusCode() {
    return bankStatusCode;
  }

  /**
   * A truncated snippet of the bank's error body, for logs only. May be {@code null}.
   */
  public String bankBody() {
    return bankBody;
  }
}
