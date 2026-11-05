package com.checkout.payment.gateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.exception.BankException;
import com.checkout.payment.gateway.exception.BankFailure;
import com.checkout.payment.gateway.service.RequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * What the merchant actually receives over HTTP when the call to the bank fails.
 *
 * <p>{@link com.checkout.payment.gateway.processor.BankPaymentsProcessorErrorTest} covers the
 * classification; this covers the wire format that classification produces.
 */
@WebMvcTest(PaymentGatewayController.class)
@TestPropertySource(properties = "payments.currencies.supported-currencies=GBP,USD,EUR")
class PaymentGatewayControllerBankFailureTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final YearMonth FUTURE = YearMonth.now(ZoneOffset.UTC).plusYears(1);

  @Autowired
  private MockMvc mvc;

  @MockBean
  private RequestService requestService;

  private static String validBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("card_number", "4111111111111111");
    body.put("expiry_month", FUTURE.getMonthValue());
    body.put("expiry_year", FUTURE.getYear());
    body.put("currency", "GBP");
    body.put("amount", 1000);
    body.put("cvv", "123");
    try {
      return JSON.writeValueAsString(body);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private ResultActions postPaymentFailingWith(BankException ex) throws Exception {
    when(requestService.processPayment(any())).thenThrow(ex);
    return mvc.perform(MockMvcRequestBuilders.post("/payment")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validBody()));
  }

  @Test
  @DisplayName("bank unreachable -> 503, safe to retry, with a Retry-After")
  void bankUnreachable() throws Exception {
    postPaymentFailingWith(BankException.of(BankFailure.BANK_UNREACHABLE, "refused"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
        .andExpect(jsonPath("$.code").value("bank_unreachable"))
        .andExpect(jsonPath("$.payment_outcome").value("not_processed"))
        .andExpect(jsonPath("$.title").value("Payment could not be processed"))
        .andExpect(jsonPath("$.status").value(503));
  }

  @Test
  @DisplayName("timeout -> 504, outcome unknown, and no Retry-After inviting a double charge")
  void bankTimeout() throws Exception {
    postPaymentFailingWith(BankException.of(BankFailure.BANK_TIMEOUT, "timed out"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
        .andExpect(jsonPath("$.code").value("bank_timeout"))
        .andExpect(jsonPath("$.payment_outcome").value("unknown"));
  }

  @Test
  @DisplayName("the simulator's 503 for a card ending in 0 reaches the merchant as a clean 503")
  void bankUnavailable() throws Exception {
    postPaymentFailingWith(BankException.of(BankFailure.BANK_UNAVAILABLE, "503 from bank"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("bank_unavailable"))
        .andExpect(jsonPath("$.payment_outcome").value("not_processed"));
  }

  @Test
  @DisplayName("a bank 400 becomes 502: our contract is broken, not the merchant's request")
  void bankRejectedOurRequest() throws Exception {
    postPaymentFailingWith(new BankException(BankFailure.BANK_REJECTED_REQUEST,
        "Bank returned status 400", null, 400,
        "{\"error_message\":\"Not all required properties were sent in the request\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("bank_rejected_request"))
        // The merchant's own input was valid, so blaming them with a 400 would be wrong.
        .andExpect(jsonPath("$.status").value(502));
  }

  @Test
  @DisplayName("nothing about our credentials or the bank's internals is surfaced")
  void credentialFailuresLeakNothing() throws Exception {
    postPaymentFailingWith(new BankException(BankFailure.BANK_UNAUTHORIZED,
        "Bank returned status 401", null, 401, "{\"message\":\"api key sk_live_xyz invalid\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("bank_unauthorized"))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("sk_live_xyz"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("401"))));
  }

  @Test
  @DisplayName("no payment_status is claimed, because no payment was ever created")
  void noPaymentStatusOnUpstreamFailures() throws Exception {
    postPaymentFailingWith(BankException.of(BankFailure.BANK_RESPONSE_INVALID, "empty body"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.payment_status").doesNotExist())
        .andExpect(jsonPath("$.id").doesNotExist());
  }

  @Test
  @DisplayName("an unexpected runtime fault is still a clean 500, never a stack trace")
  void unexpectedFailuresAreNotLeaked() throws Exception {
    when(requestService.processPayment(any()))
        .thenThrow(new IllegalStateException("repository connection pool exhausted"));

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBody()))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("pool exhausted"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("IllegalStateException"))));
  }

  @Test
  @DisplayName("the catch-all does not turn an unknown URL into a 500")
  void unknownUrlsStillAnswer404() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/not-a-real-endpoint"))
        .andExpect(status().isNotFound());
  }
}
