package com.checkout.payment.gateway.processor;

import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PaymentRequestData;
import reactor.core.publisher.Mono;


public interface PaymentsProcessor {

  /**
   * Submits the payment, completing with the bank's answer. The call is only made once the
   * returned {@link Mono} is subscribed to.
   */
  Mono<BankPaymentResponse> process(PaymentRequestData paymentRequestData);
}
