package com.checkout.payment.gateway.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.checkout.payment.gateway.exception.BankException;
import com.checkout.payment.gateway.exception.BankFailure;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.properties.BankProperties;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Drives the real bankWebClient bean with only its transport swapped out, so the JSON asserted
 * here is the JSON the bank would receive in production.
 */
@SpringBootTest
class BankPaymentsProcessorTest {

  private static final String AUTHORISED =
      "{\"authorized\":true,\"authorization_code\":\"0bb07405-6d44-4b50-a14f-7ae0beff13ad\"}";

  @Autowired
  private WebClient bankWebClient;

  @Autowired
  private BankProperties bankProperties;

  /** The answer the stubbed transport gives to the next request. */
  private final AtomicReference<Supplier<Mono<ClientHttpResponse>>> bankAnswer =
      new AtomicReference<>();

  /** The request the stubbed transport last received, fully encoded. */
  private final AtomicReference<MockClientHttpRequest> sentRequest = new AtomicReference<>();

  private BankPaymentsProcessor processor;
  private String paymentsUrl;

  @BeforeEach
  void setUp() {
    this.sentRequest.set(null);
    this.bankAnswer.set(() -> Mono.just(response(HttpStatus.OK, AUTHORISED)));
    this.processor = new BankPaymentsProcessor(
        bankWebClient.mutate().clientConnector(stubbedBank()).build());
    this.paymentsUrl = bankProperties.apiUrl() + "/payments";
  }

  /**
   * Captures the encoded request, then hands back whatever answer the test staged.
   */
  private ClientHttpConnector stubbedBank() {
    return (method, uri, requestCallback) -> {
      MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
      sentRequest.set(request);
      return requestCallback.apply(request).then(Mono.defer(() -> bankAnswer.get().get()));
    };
  }

  private static MockClientHttpResponse response(HttpStatusCode status, String body) {
    MockClientHttpResponse response = new MockClientHttpResponse(status);
    if (body != null) {
      response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      response.setBody(body);
    }
    return response;
  }

  private static BankPaymentRequest request() {
    return new BankPaymentRequest("4111111111111111", "12/2030", "GBP", 1000, "123");
  }

  private void bankAnswers(HttpStatusCode status, String body) {
    bankAnswer.set(() -> Mono.just(response(status, body)));
  }

  private void bankFailsWith(Throwable transportFailure) {
    bankAnswer.set(() -> Mono.error(transportFailure));
  }

  private BankPaymentResponse process() {
    BankPaymentResponse response = processor.process(request()).block();
    verifyPostedToPayments();
    return response;
  }

  private void assertFailsWithBankFailure(BankFailure expected) {
    StepVerifier.create(processor.process(request()))
        .expectErrorSatisfies(thrown -> {
          assertTrue(thrown instanceof BankException, "expected a BankException, got " + thrown);
          assertEquals(expected, ((BankException) thrown).failure());
        })
        .verify();
    verifyPostedToPayments();
  }

  private void verifyPostedToPayments() {
    MockClientHttpRequest request = sentRequest.get();
    assertNotNull(request, "the bank was never called");
    assertEquals(HttpMethod.POST, request.getMethod());
    assertEquals(paymentsUrl, request.getURI().toString());
  }

  @Test
  @DisplayName("nothing is sent to the bank until the returned Mono is subscribed to")
  void requestIsNotSentBeforeSubscription() {
    Mono<BankPaymentResponse> pending = processor.process(request());

    assertNotNull(pending);
    assertNull(sentRequest.get(), "the bank was called before anyone subscribed");

    pending.block();

    verifyPostedToPayments();
  }

  @Test
  @DisplayName("the bank's field names come from the Jackson configuration, not from annotations")
  void outboundRequestUsesTheBanksFieldNames() throws Exception {
    process();

    JSONAssert.assertEquals("""
        {
          "card_number": "4111111111111111",
          "expiry_date": "12/2030",
          "currency": "GBP",
          "amount": 1000,
          "cvv": "123"
        }""", sentRequest.get().getBodyAsString().block(), true);
  }

  @Test
  void authorisedPaymentIsReturned() {
    BankPaymentResponse response = process();

    assertTrue(response.isAuthorized());
    assertEquals("0bb07405-6d44-4b50-a14f-7ae0beff13ad", response.authorizationCode());
  }

  @Test
  @DisplayName("a decline is a valid answer, not a failure")
  void declinedPaymentIsReturned() {
    bankAnswers(HttpStatus.OK, "{\"authorized\":false,\"authorization_code\":\"\"}");

    assertFalse(process().isAuthorized());
  }

  @Test
  void bankClientErrorIsAGatewayFault() {
    bankAnswers(HttpStatus.BAD_REQUEST,
        "{\"error_message\":\"Not all required properties were sent in the request\"}");

    assertFailsWithBankFailure(BankFailure.BANK_REJECTED_REQUEST);
  }

  @Test
  void bankServiceUnavailableResultsInBankUnavailableFailure() {
    bankAnswers(HttpStatus.SERVICE_UNAVAILABLE, "{}");

    assertFailsWithBankFailure(BankFailure.BANK_UNAVAILABLE);
  }

  @Test
  void bankInternalServerErrorResultsInBankUnavailableFailure() {
    bankAnswers(HttpStatus.INTERNAL_SERVER_ERROR, null);

    assertFailsWithBankFailure(BankFailure.BANK_UNAVAILABLE);
  }

  @Test
  void bankCannotBeReachedResultsInBankUnreachableFailure() {
    bankFailsWith(new ConnectException("Connection refused"));

    assertFailsWithBankFailure(BankFailure.BANK_UNREACHABLE);
  }

  @Test
  void malformedResponseBodyResultsInBankUnreachableFailure() {
    bankAnswers(HttpStatus.OK, "{\"authorized\":tru");

    assertFailsWithBankFailure(BankFailure.BANK_UNREACHABLE);
  }

  @Test
  void emptyResponseBodyResultsInBankUnreachableFailure() {
    bankAnswers(HttpStatus.OK, null);

    assertFailsWithBankFailure(BankFailure.BANK_UNREACHABLE);
  }

  @Test
  void responseWithoutAnAuthorisationDecisionResultsInBankUnreachableFailure() {
    bankAnswers(HttpStatus.OK, "{\"authorization_code\":\"abc\"}");

    assertFailsWithBankFailure(BankFailure.BANK_UNREACHABLE);
  }

  @Test
  void outboundRequestDoesNotPrintThePanOrCvv() {
    String printed = request().toString();

    assertTrue(printed.contains("****1111"));
    assertFalse(printed.contains("4111111111111111"));
    assertFalse(printed.contains("123"));
  }
}
