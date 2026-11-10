package com.checkout.payment.gateway.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    name = "PaymentStatus",
    description = """
        What became of a payment.

        - `Authorized` — the bank approved the payment.
        - `Declined` — the bank refused the payment. The request itself was valid, so it is
          returned with a 200 and the payment is stored.
        - `Rejected` — the gateway refused the request, so nothing was stored. Returned with a
          400, 405, 415 or 500. A bank failure (502, 503) carries no status at all, since the
          outcome is unknown rather than rejected.""",
    example = "Authorized",
    allowableValues = {"Authorized", "Declined", "Rejected"})
public enum PaymentStatus {
  AUTHORIZED("Authorized"),
  DECLINED("Declined"),
  REJECTED("Rejected");

  private final String name;

  PaymentStatus(String name) {
    this.name = name;
  }

  @JsonValue
  public String getName() {
    return this.name;
  }
}
