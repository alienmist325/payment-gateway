package com.checkout.payment.gateway.processor;

import com.checkout.payment.gateway.exception.BankException;
import com.checkout.payment.gateway.exception.BankFailure;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PaymentRequestData;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

/**
 * Sends payments to the acquiring bank.
 *
 * <p>Every failure mode of that call is translated into a {@link BankException} carrying a
 * {@link BankFailure}; nothing else escapes this class. See {@link BankFailure} for the full
 * taxonomy and the response each case produces.
 */
@Component
public class BankPaymentsProcessor implements PaymentsProcessor {

  /**
   * How much of the bank's error body we keep for logging. Enough to diagnose a contract mismatch,
   * bounded so a runaway error page cannot flood the logs.
   */
  private static final int MAX_LOGGED_BODY = 512;

  private final RestClient paymentClient;

  public BankPaymentsProcessor(RestClient bankRestClient) {
    this.paymentClient = bankRestClient;
  }

  @Override
  public BankPaymentResponse process(PaymentRequestData paymentRequestData) {
    return process(toBankPaymentRequest(paymentRequestData));
  }

  public BankPaymentResponse process(BankPaymentRequest bankPaymentRequest) {
    BankPaymentResponse response = exchange(bankPaymentRequest);
    return validate(response);
  }

  /**
   * Performs the call, turning transport-level and HTTP-level failures into {@link BankException}.
   */
  private BankPaymentResponse exchange(BankPaymentRequest bankPaymentRequest) {
    try {
      return paymentClient
          .post()
          .uri("/payments")
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(bankPaymentRequest)
          .retrieve()
          // Handles every non-2xx, so nothing falls through to RestClient's default handler.
          .onStatus(HttpStatusCode::isError, (request, response) -> {
            throw fromErrorStatus(response);
          })
          .body(BankPaymentResponse.class);
    } catch (ResourceAccessException ex) {
      // This type means the exchange never completed at all, so whatever the cause, it is a
      // transport failure rather than anything to do with the content of a response.
      throw fromTransportFailure(ex.getCause(), ex);
    } catch (RestClientResponseException ex) {
      // Defensive: our onStatus handler covers every error status, so this should be unreachable.
      // If RestClient ever does raise one, classify it rather than letting it become a bare 500.
      throw new BankException(BankFailure.ofBankStatus(ex.getStatusCode().value()),
          "Bank returned " + ex.getStatusCode(), ex,
          ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString()));
    } catch (UnknownContentTypeException ex) {
      // A 2xx whose content type we have no converter for, e.g. an HTML error page from a proxy.
      // Checked before RestClientException, which it extends.
      throw new BankException(BankFailure.BANK_RESPONSE_UNREADABLE,
          "Bank returned unreadable content type " + ex.getContentType(), ex,
          ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString()));
    } catch (RestClientException ex) {
      // A socket can also die *while we are reading the body*, which is exactly what a read timeout
      // does. RestClient reports that as a plain RestClientException wrapping the
      // SocketTimeoutException - the same wrapper it uses for genuine parse failures. Classifying
      // on the wrapper type alone would report the commonest bank failure, a timeout, as an
      // unreadable response, so here we have to look at the cause.
      IOException transportCause = findTransportCause(ex);
      if (transportCause != null) {
        throw fromTransportFailure(transportCause, ex);
      }
      // Whatever is left is a body we could not deserialise, or a request we could not serialise.
      throw new BankException(BankFailure.BANK_RESPONSE_UNREADABLE,
          "Could not exchange messages with the bank: " + ex.getMessage(), ex);
    }
  }

  /**
   * Finds the socket-level cause of a failure, or {@code null} if there is none.
   *
   * <p>Matches specific socket exception types rather than {@link IOException} as a whole, because
   * Jackson's parse failures are themselves {@code IOException}s and must stay classified as an
   * unreadable body, not as a transport failure.
   */
  private static IOException findTransportCause(Throwable throwable) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SocketTimeoutException
          || cause instanceof ConnectException
          || cause instanceof UnknownHostException
          || cause instanceof NoRouteToHostException
          || cause instanceof SSLException
          || cause instanceof SocketException
          || cause instanceof EOFException) {
        return (IOException) cause;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return null;
  }

  /**
   * Checks that a 2xx response is actually usable.
   *
   * <p>Each of these would otherwise fail silently and wrongly: a null body throws
   * {@link NullPointerException} downstream, a missing {@code authorized} flag would be read as a
   * decline, and an authorisation with no code would be stored as authorised but unusable.
   */
  private BankPaymentResponse validate(BankPaymentResponse response) {
    if (response == null) {
      throw BankException.of(BankFailure.BANK_RESPONSE_INVALID,
          "Bank returned a successful status with an empty body");
    }
    if (response.authorized() == null) {
      throw BankException.of(BankFailure.BANK_RESPONSE_INVALID,
          "Bank response is missing the 'authorized' field");
    }
    if (response.isAuthorized() && isBlank(response.authorizationCode())) {
      throw BankException.of(BankFailure.BANK_RESPONSE_INVALID,
          "Bank authorized the payment but returned no authorization code");
    }
    return response;
  }

  /**
   * Classifies a socket-level failure by what it tells us about whether the bank saw the request.
   *
   * @param cause    the socket exception found in the chain
   * @param reported the exception Spring actually raised, kept as the cause for the logs
   */
  private BankException fromTransportFailure(Throwable cause, RestClientException reported) {
    if (cause instanceof ConnectException
        || cause instanceof UnknownHostException
        || cause instanceof NoRouteToHostException
        || cause instanceof SSLException) {
      // No connection was ever established, so the bank cannot have seen the request. Checked
      // before SocketException, which ConnectException extends.
      return new BankException(BankFailure.BANK_UNREACHABLE,
          "Could not connect to the bank: " + cause, reported);
    }
    if (cause instanceof SocketTimeoutException) {
      // Connect and read timeouts both surface as SocketTimeoutException and cannot be told apart
      // without parsing the message, so we assume the worse of the two: the request may have
      // reached the bank and been authorised.
      return new BankException(BankFailure.BANK_TIMEOUT,
          "Timed out calling the bank: " + cause, reported);
    }
    // A reset or a truncated response: the request was already in flight, so the outcome is
    // likewise unknown.
    return new BankException(BankFailure.BANK_TIMEOUT,
        "Lost the connection to the bank: " + cause, reported);
  }

  /**
   * Classifies a non-2xx response, capturing a bounded snippet of the body for the logs.
   */
  private BankException fromErrorStatus(ClientHttpResponse response) {
    int statusCode = readStatusCode(response);
    return new BankException(BankFailure.ofBankStatus(statusCode),
        "Bank returned status " + statusCode, null, statusCode, readBodySafely(response));
  }

  private static int readStatusCode(ClientHttpResponse response) {
    try {
      return response.getStatusCode().value();
    } catch (IOException ex) {
      // We know the status was an error because this handler matched on it; we just cannot read it
      // back. Report it as a bank error rather than letting the IOException become an
      // UncheckedIOException that RestClient would rewrap as an unreadable body.
      return 500;
    }
  }

  /**
   * Reads the error body without ever throwing. An {@link IOException} escaping a status handler is
   * rewrapped by RestClient into a misleading "could not extract response" error, which would hide
   * the real status we just classified.
   */
  private static String readBodySafely(ClientHttpResponse response) {
    try (InputStream body = response.getBody()) {
      return truncate(new String(body.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }

  private static String truncate(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    return body.length() <= MAX_LOGGED_BODY ? body : body.substring(0, MAX_LOGGED_BODY) + "...";
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static BankPaymentRequest toBankPaymentRequest(PaymentRequestData paymentRequest) {
    return new BankPaymentRequest(paymentRequest.cardNumber(), paymentRequest.getExpiryDate(),
        paymentRequest.currency(),
        paymentRequest.amount(), paymentRequest.cvv());
  }
}
