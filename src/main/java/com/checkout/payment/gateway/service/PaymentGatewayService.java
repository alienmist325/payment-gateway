package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.model.PaymentData;
import com.checkout.payment.gateway.model.PaymentRequestData;
import com.checkout.payment.gateway.processor.PaymentsProcessor;
import com.checkout.payment.gateway.repository.PaymentsPersistence;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final PaymentsPersistence paymentsRepository;
  private final PaymentsProcessor paymentsProcessor;

  public PaymentGatewayService(PaymentsRepository paymentsRepository,
      PaymentsProcessor paymentsProcessor) {
    this.paymentsRepository = paymentsRepository;
    this.paymentsProcessor = paymentsProcessor;
  }

  public Optional<PaymentData> getPaymentById(UUID id) {
    return paymentsRepository.get(id);
  }

  /**
   * Submits the payment to the bank and stores the outcome, completing with the stored payment's
   * ID once the bank has answered.
   */
  public Mono<UUID> processPayment(PaymentRequestData paymentRequest) {
    String cardLastFour = paymentRequest.getCardNumberLastFour();

    return Mono.defer(() -> {
      LOG.info("Submitting a payment of {} {} on the card ending {} to the bank",
          paymentRequest.amount(), paymentRequest.currency(), cardLastFour);
      return paymentsProcessor.process(paymentRequest);
    }).map(bankPaymentResponse -> {
      LOG.info("The bank {} the payment on the card ending {}",
          bankPaymentResponse.isAuthorized() ? "authorized" : "declined", cardLastFour);

      PaymentData paymentData = PaymentData.createPaymentData(bankPaymentResponse.isAuthorized(),
          cardLastFour,
          paymentRequest.expiryMonth(), paymentRequest.expiryYear(),
          paymentRequest.currency(), paymentRequest.amount(),
          bankPaymentResponse.authorizationCode());
      paymentsRepository.add(paymentData);
      LOG.info("Stored payment {} as {}", paymentData.id(),
          paymentData.authorized() ? "authorized" : "declined");

      return paymentData.id();
    });
  }

}
