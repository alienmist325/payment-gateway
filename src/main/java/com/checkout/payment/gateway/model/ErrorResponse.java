package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentOutcome;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankFailure;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public class ErrorResponse extends ProblemDetail {

  PaymentStatus paymentStatus;
  List<FieldViolation> errors;
  String code;
  PaymentOutcome paymentOutcome;

  public List<FieldViolation> getError() {
    return errors;
  }

  public PaymentStatus getPaymentStatus() {
    return paymentStatus;
  }

  /**
   * A stable, machine-readable identifier for the failure, so callers can branch on it rather than
   * on the prose in {@code detail}.
   */
  public String getCode() {
    return code;
  }

  /**
   * Whether the bank may have taken the payment. Only present on upstream failures, where the
   * answer is not implied by the status.
   */
  public PaymentOutcome getPaymentOutcome() {
    return paymentOutcome;
  }

  public void setError(List<FieldViolation> errors) {
    this.errors = errors;
  }

  public void setPaymentStatus(PaymentStatus paymentStatus) {
    this.paymentStatus = paymentStatus;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public void setPaymentOutcome(PaymentOutcome paymentOutcome) {
    this.paymentOutcome = paymentOutcome;
  }

  public record FieldViolation(String field, String message, Object rejectedValue) {

  }

  public static ErrorResponse validation(HttpStatus status, List<FieldViolation> errors) {
    ErrorResponse errorResponse = new ErrorResponse();
    errorResponse.setStatus(status);
    errorResponse.setTitle("Validation failed");
    errorResponse.setDetail("One or more fields are invalid");
    errorResponse.setError(errors);
    errorResponse.setPaymentStatus(PaymentStatus.REJECTED);

    return errorResponse;
  }

  public static ErrorResponse malformed(HttpStatus status, String detail) {
    ErrorResponse errorResponse = new ErrorResponse();
    errorResponse.setStatus(status);
    errorResponse.setTitle(status.is5xxServerError() ? "Request failed" : "Malformed request");
    errorResponse.setDetail(detail);
    errorResponse.setError(List.of());
    errorResponse.setPaymentStatus(PaymentStatus.REJECTED);

    return errorResponse;
  }

  /**
   * The response for a payment that could not be attempted or completed because the call to the
   * acquiring bank failed.
   *
   * <p>Deliberately carries no {@code payment_status}: the three payment statuses describe payments
   * that were created, and here none was. "Rejected" in particular would be misleading, since it
   * tells the merchant their input was at fault when the fault lies with us or the bank. The
   * merchant gets {@code code} to branch on and {@code payment_outcome} to decide whether
   * re-submitting is safe.
   */
  public static ErrorResponse upstream(BankFailure failure) {
    ErrorResponse errorResponse = new ErrorResponse();
    errorResponse.setStatus(failure.status());
    errorResponse.setTitle("Payment could not be processed");
    errorResponse.setDetail(failure.detail());
    errorResponse.setCode(failure.code());
    errorResponse.setPaymentOutcome(failure.outcome());

    return errorResponse;
  }
}
