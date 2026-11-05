package com.checkout.payment.gateway.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a payment may have been taken by the acquiring bank when the call to it failed.
 *
 * <p>This is the single most important thing to tell a merchant about a failed payment, because it
 * decides whether they may safely re-submit. Re-submitting a payment the bank already authorised
 * charges the cardholder twice, so anything we are not certain about is reported as
 * {@link #UNKNOWN}.
 */
public enum PaymentOutcome {

  /**
   * The bank definitely did not process the payment: the request never reached it, or it was
   * refused before any authorisation was attempted. The merchant may safely re-submit.
   */
  NOT_PROCESSED("not_processed"),

  /**
   * The bank may or may not have processed the payment. The merchant must reconcile before
   * re-submitting, or they risk charging the cardholder twice.
   */
  UNKNOWN("unknown");

  private final String name;

  PaymentOutcome(String name) {
    this.name = name;
  }

  @JsonValue
  public String getName() {
    return this.name;
  }
}
