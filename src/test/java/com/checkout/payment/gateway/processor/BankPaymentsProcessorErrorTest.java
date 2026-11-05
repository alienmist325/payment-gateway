package com.checkout.payment.gateway.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.enums.PaymentOutcome;
import com.checkout.payment.gateway.exception.BankException;
import com.checkout.payment.gateway.exception.BankFailure;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * Covers every way the call to the acquiring bank can fail.
 *
 * <p>These drive a real {@link RestClient} through {@link MockRestServiceServer} rather than
 * stubbing the client, so each case exercises the exception type Spring genuinely raises.
 */
class BankPaymentsProcessorErrorTest {

  private static final String BANK_URL = "http://bank.test/payments";

  private BankPaymentsProcessor processor;
  private MockRestServiceServer bank;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://bank.test");
    this.bank = MockRestServiceServer.bindTo(builder).build();
    this.processor = new BankPaymentsProcessor(builder.build());
  }

  private static BankPaymentRequest request() {
    return new BankPaymentRequest("4111111111111111", "12/2030", "GBP", 1000, "123");
  }

  private BankException failWith(ResponseCreator response) {
    bank.expect(requestTo(BANK_URL)).andExpect(method(HttpMethod.POST)).andRespond(response);
    return assertThrows(BankException.class, () -> processor.process(request()));
  }

  private void assertClassifiedAs(BankException ex, BankFailure expected) {
    assertEquals(expected, ex.failure());
    bank.verify();
  }

  // ------------------------------------------------------------------ happy paths

  @Nested
  @DisplayName("responses the bank is expected to give")
  class SuccessfulExchanges {

    @Test
    void anAuthorisationIsReturnedAsIs() {
      bank.expect(requestTo(BANK_URL)).andRespond(withSuccess(
          "{\"authorized\":true,\"authorization_code\":\"0bb07405-6d44-4b50-a14f-7ae0beff13ad\"}",
          MediaType.APPLICATION_JSON));

      BankPaymentResponse response = processor.process(request());

      assertTrue(response.isAuthorized());
      assertEquals("0bb07405-6d44-4b50-a14f-7ae0beff13ad", response.authorizationCode());
      bank.verify();
    }

    @Test
    @DisplayName("a decline is a valid answer, not an error: the simulator sends a blank code")
    void aDeclineIsNotTreatedAsAFailure() {
      bank.expect(requestTo(BANK_URL)).andRespond(withSuccess(
          "{\"authorized\":false,\"authorization_code\":\"\"}", MediaType.APPLICATION_JSON));

      BankPaymentResponse response = processor.process(request());

      assertFalse(response.isAuthorized());
      bank.verify();
    }

    @Test
    void anUnexpectedExtraFieldFromTheBankIsTolerated() {
      bank.expect(requestTo(BANK_URL)).andRespond(withSuccess(
          "{\"authorized\":true,\"authorization_code\":\"abc\",\"new_field\":1}",
          MediaType.APPLICATION_JSON));

      assertTrue(processor.process(request()).isAuthorized());
      bank.verify();
    }
  }

  // ------------------------------------------------------------------ transport failures

  @Nested
  @DisplayName("the exchange never completes")
  class TransportFailures {

    @Test
    @DisplayName("connection refused: the bank never saw the request, so a retry is safe")
    void connectionRefused() {
      BankException ex = failWith(withException(new ConnectException("Connection refused")));

      assertClassifiedAs(ex, BankFailure.BANK_UNREACHABLE);
      assertEquals(PaymentOutcome.NOT_PROCESSED, ex.failure().outcome());
    }

    @Test
    void unknownHost() {
      assertClassifiedAs(failWith(withException(new UnknownHostException("bank.test"))),
          BankFailure.BANK_UNREACHABLE);
    }

    @Test
    void tlsHandshakeFailure() {
      assertClassifiedAs(failWith(withException(new SSLHandshakeException("bad certificate"))),
          BankFailure.BANK_UNREACHABLE);
    }

    @Test
    @DisplayName("a timeout is ambiguous: the bank may have authorised before going quiet")
    void timeout() {
      BankException ex = failWith(withException(new SocketTimeoutException("Read timed out")));

      assertClassifiedAs(ex, BankFailure.BANK_TIMEOUT);
      assertEquals(PaymentOutcome.UNKNOWN, ex.failure().outcome());
    }

    @Test
    @DisplayName("a connection reset mid-exchange is treated as ambiguous, not as unreachable")
    void connectionResetMidExchange() {
      assertClassifiedAs(failWith(withException(new IOException("Connection reset by peer"))),
          BankFailure.BANK_TIMEOUT);
    }

    @Test
    @DisplayName("a read timeout that fires while reading the body is still a timeout")
    void timeoutWhileReadingTheBody() {
      // Regression test. A read timeout almost always fires here rather than before the response
      // starts, and RestClient reports it as a plain RestClientException wrapping the
      // SocketTimeoutException - the same wrapper it uses for genuine parse failures. Classifying
      // on the wrapper reported this, the commonest bank failure, as an unreadable response.
      BankException ex = failWith(request -> {
        MockClientHttpResponse response = new MockClientHttpResponse(new InputStream() {
          @Override
          public int read() throws IOException {
            throw new SocketTimeoutException("Read timed out");
          }
        }, HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
      });

      assertClassifiedAs(ex, BankFailure.BANK_TIMEOUT);
      assertEquals(PaymentOutcome.UNKNOWN, ex.failure().outcome());
    }

    @Test
    @DisplayName("malformed JSON is not mistaken for a transport failure, though Jackson's "
        + "exceptions are IOExceptions too")
    void parseFailuresAreNotClassifiedAsTransportFailures() {
      assertClassifiedAs(failWith(withSuccess("{\"authorized\":tru", MediaType.APPLICATION_JSON)),
          BankFailure.BANK_RESPONSE_UNREADABLE);
    }
  }

  // ------------------------------------------------------------------ error statuses

  @Nested
  @DisplayName("the bank answers with an error status")
  class ErrorStatuses {

    @Test
    @DisplayName("503: the simulator's response for a card ending in 0")
    void serviceUnavailable() {
      BankException ex = failWith(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .body("{}").contentType(MediaType.APPLICATION_JSON));

      assertClassifiedAs(ex, BankFailure.BANK_UNAVAILABLE);
      assertEquals(PaymentOutcome.NOT_PROCESSED, ex.failure().outcome());
      assertEquals(503, ex.bankStatusCode());
    }

    @Test
    @DisplayName("400: the simulator's response when a required property is missing")
    void badRequestIsAGatewayFaultNotTheMerchants() {
      BankException ex = failWith(withStatus(HttpStatus.BAD_REQUEST)
          .body("{\"error_message\":\"Not all required properties were sent in the request\"}")
          .contentType(MediaType.APPLICATION_JSON));

      assertClassifiedAs(ex, BankFailure.BANK_REJECTED_REQUEST);
      assertEquals(HttpStatus.BAD_GATEWAY, ex.failure().status());
    }

    @Test
    @DisplayName("the bank's error text is kept for logs but never becomes the merchant's detail")
    void bankErrorBodyIsCapturedForDiagnosisOnly() {
      BankException ex = failWith(withStatus(HttpStatus.BAD_REQUEST)
          .body("{\"errorMessage\":\"The request supplied is not supported by the simulator\"}")
          .contentType(MediaType.APPLICATION_JSON));

      assertTrue(ex.bankBody().contains("not supported by the simulator"));
      assertFalse(ex.failure().detail().contains("simulator"));
      bank.verify();
    }

    @ParameterizedTest(name = "{0} is a gateway fault")
    @ValueSource(ints = {404, 405, 415, 422})
    void otherClientErrors(int status) {
      assertClassifiedAs(failWith(withStatus(HttpStatus.valueOf(status))),
          BankFailure.BANK_REJECTED_REQUEST);
    }

    @ParameterizedTest(name = "{0} means credentials, never surfaced to the merchant")
    @ValueSource(ints = {401, 403})
    void authenticationFailures(int status) {
      assertClassifiedAs(failWith(withStatus(HttpStatus.valueOf(status))),
          BankFailure.BANK_UNAUTHORIZED);
    }

    @Test
    void rateLimited() {
      BankException ex = failWith(withStatus(HttpStatus.TOO_MANY_REQUESTS));

      assertClassifiedAs(ex, BankFailure.BANK_RATE_LIMITED);
      assertEquals(10, ex.failure().retryAfterSeconds());
    }

    @Test
    @DisplayName("500 is ambiguous; 502/503/504 mean the bank never got to the payment")
    void serverErrorsAreSplitByWhetherWorkCouldHaveHappened() {
      assertEquals(BankFailure.BANK_ERROR, BankFailure.ofBankStatus(500));
      assertEquals(PaymentOutcome.UNKNOWN, BankFailure.BANK_ERROR.outcome());

      assertEquals(BankFailure.BANK_UNAVAILABLE, BankFailure.ofBankStatus(502));
      assertEquals(BankFailure.BANK_UNAVAILABLE, BankFailure.ofBankStatus(504));
      assertEquals(PaymentOutcome.NOT_PROCESSED, BankFailure.BANK_UNAVAILABLE.outcome());
    }

    @Test
    void internalServerError() {
      assertClassifiedAs(failWith(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)),
          BankFailure.BANK_ERROR);
    }
  }

  // ------------------------------------------------------------------ unusable 2xx bodies

  @Nested
  @DisplayName("the bank answers 200 with something we cannot act on")
  class UnusableSuccessBodies {

    @Test
    @DisplayName("an empty body would otherwise NPE downstream")
    void emptyBody() {
      assertClassifiedAs(failWith(withSuccess()), BankFailure.BANK_RESPONSE_INVALID);
    }

    @Test
    @DisplayName("a missing 'authorized' flag must not be read as a decline")
    void missingAuthorizedFlag() {
      BankException ex = failWith(withSuccess("{\"authorization_code\":\"abc\"}",
          MediaType.APPLICATION_JSON));

      assertClassifiedAs(ex, BankFailure.BANK_RESPONSE_INVALID);
    }

    @Test
    @DisplayName("an error-shaped body returned with 200 has no 'authorized' flag either")
    void errorShapedBodyWithSuccessStatus() {
      assertClassifiedAs(
          failWith(withSuccess("{\"error_message\":\"nope\"}", MediaType.APPLICATION_JSON)),
          BankFailure.BANK_RESPONSE_INVALID);
    }

    @Test
    @DisplayName("an authorisation with no code would be stored as authorised but unusable")
    void authorisedWithoutAnAuthorisationCode() {
      assertClassifiedAs(
          failWith(withSuccess("{\"authorized\":true,\"authorization_code\":\"\"}",
              MediaType.APPLICATION_JSON)),
          BankFailure.BANK_RESPONSE_INVALID);
    }

    @Test
    void malformedJson() {
      assertClassifiedAs(failWith(withSuccess("{\"authorized\":tru", MediaType.APPLICATION_JSON)),
          BankFailure.BANK_RESPONSE_UNREADABLE);
    }

    @Test
    @DisplayName("an HTML error page from something in between is not JSON we can convert")
    void unexpectedContentType() {
      assertClassifiedAs(
          failWith(withSuccess("<html><body>502 Bad Gateway</body></html>", MediaType.TEXT_HTML)),
          BankFailure.BANK_RESPONSE_UNREADABLE);
    }
  }

  // ------------------------------------------------------------------ taxonomy invariants

  @Nested
  @DisplayName("the taxonomy itself")
  class TaxonomyInvariants {

    @Test
    @DisplayName("no merchant-facing detail leaks internal wording")
    void detailsAreSafeToShow() {
      for (BankFailure failure : BankFailure.values()) {
        String detail = failure.detail();
        assertFalse(detail.isBlank(), failure + " has no detail");
        assertFalse(detail.toLowerCase().contains("exception"), failure + " leaks a type name");
        assertFalse(detail.contains("http://"), failure + " leaks a URL");
      }
    }

    @Test
    @DisplayName("every failure is a 5xx: none of these are the merchant's fault")
    void allFailuresAreServerSide() {
      for (BankFailure failure : BankFailure.values()) {
        assertTrue(failure.status().is5xxServerError(),
            failure + " blames the merchant with " + failure.status());
      }
    }

    @Test
    @DisplayName("Retry-After is only advertised where a retry is actually safe")
    void retryAfterOnlyWhereRetryingIsSafe() {
      for (BankFailure failure : BankFailure.values()) {
        if (failure.retryAfterSeconds() != null) {
          assertEquals(PaymentOutcome.NOT_PROCESSED, failure.outcome(),
              failure + " invites a retry that could double-charge");
        }
      }
    }

    @Test
    @DisplayName("codes are stable, lower snake case identifiers")
    void codesAreMachineReadable() {
      for (BankFailure failure : BankFailure.values()) {
        assertTrue(failure.code().matches("[a-z_]+"), failure + " has code " + failure.code());
      }
    }
  }

  // ------------------------------------------------------------------ data hygiene

  @Test
  @DisplayName("the outbound request never prints the PAN or the CVV")
  void requestToStringIsSafeToLog() {
    String printed = request().toString();

    assertTrue(printed.contains("****1111"));
    assertFalse(printed.contains("4111111111111111"));
    assertFalse(printed.contains("123"), "CVV must never reach the logs");
  }

  @Test
  @DisplayName("a null 'authorized' never silently becomes false")
  void boxedAuthorizedFlagPreservesAbsence() {
    assertNull(new BankPaymentResponse(null, "abc").authorized());
    assertFalse(new BankPaymentResponse(null, "abc").isAuthorized());
  }
}
