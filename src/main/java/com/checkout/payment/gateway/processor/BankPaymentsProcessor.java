package com.checkout.payment.gateway.processor;

import com.checkout.payment.gateway.exception.BankException;
import com.checkout.payment.gateway.exception.BankFailure;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PaymentRequestData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class BankPaymentsProcessor implements PaymentsProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(BankPaymentsProcessor.class);

  private final WebClient paymentClient;

  public BankPaymentsProcessor(WebClient bankWebClient) {
    this.paymentClient = bankWebClient;
  }

  @Override
  public Mono<BankPaymentResponse> process(PaymentRequestData paymentRequestData) {
    return process(toBankPaymentRequest(paymentRequestData));
  }

  /**
   * Posts the payment to the bank. Nothing is sent until the returned {@link Mono} is subscribed
   * to, and no thread waits on the bank's answer.
   */
  public Mono<BankPaymentResponse> process(BankPaymentRequest bankPaymentRequest) {
    return Mono.defer(() -> {
      // The request masks the PAN and the CVV when printed, so it is safe to log.
      LOG.debug("POST /payments to the bank with {}", bankPaymentRequest);

      return paymentClient
          .post()
          .uri("/payments")
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .bodyValue(bankPaymentRequest)
          .retrieve()
          .onStatus(HttpStatusCode::isError, errorResponse -> {
            LOG.warn("The bank responded {} to POST /payments", errorResponse.statusCode());
            return Mono.error(new BankException(
                BankFailure.ofBankStatus(errorResponse.statusCode()),
                "Bank returned " + errorResponse.statusCode()));
          })
          .bodyToMono(BankPaymentResponse.class)
          .onErrorMap(ex -> !(ex instanceof BankException), BankPaymentsProcessor::unreachable)
          .switchIfEmpty(Mono.error(BankPaymentsProcessor::noDecision))
          .flatMap(response -> response.authorized() == null
              ? Mono.error(noDecision())
              : Mono.just(response))
          .doOnNext(response -> LOG.debug(
              "The bank responded to POST /payments with authorized={}", response.authorized()));
    });
  }

  private static BankException unreachable(Throwable ex) {
    LOG.warn("Could not complete POST /payments to the bank: {}", ex.getMessage());
    return new BankException(BankFailure.BANK_UNREACHABLE, "Call to the bank failed", ex);
  }

  private static BankException noDecision() {
    LOG.warn("The bank responded to POST /payments without an authorization decision");
    return new BankException(BankFailure.BANK_UNREACHABLE,
        "Bank returned no authorization decision");
  }

  private static BankPaymentRequest toBankPaymentRequest(PaymentRequestData paymentRequest) {
    return new BankPaymentRequest(paymentRequest.cardNumber(), paymentRequest.getExpiryDate(),
        paymentRequest.currency(),
        paymentRequest.amount(), paymentRequest.cvv());
  }
}
