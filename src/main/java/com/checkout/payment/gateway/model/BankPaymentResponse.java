package com.checkout.payment.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * The acquiring bank's answer to a payment request.
 *
 * <p>{@code authorized} is a boxed {@link Boolean} on purpose. As a primitive it would default to
 * {@code false} whenever the bank omitted the field or returned an error-shaped body, silently
 * turning a missing answer into a decline and losing a real authorisation. Boxed, a missing field
 * is {@code null} and the processor rejects the response instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankPaymentResponse(
    @JsonProperty("authorized")
    Boolean authorized,
    @JsonProperty("authorization_code")
    String authorizationCode
) implements Serializable {

  /**
   * Whether the bank authorised the payment. Only safe to call once the response has been validated
   * as having an {@code authorized} field at all.
   */
  public boolean isAuthorized() {
    return Boolean.TRUE.equals(authorized);
  }
}
