# Instructions for candidates

This is the Java version of the Payment Gateway challenge. If you haven't already read this [README.md](https://github.com/cko-recruitment/) on the details of this exercise, please do so now.

## Requirements
- JDK 17
- Docker

## Template structure

src/ - A skeleton SpringBoot Application

test/ - Some simple JUnit tests

imposters/ - contains the bank simulator configuration. Don't change this

.editorconfig - don't change this. It ensures a consistent set of rules for submissions when reformatting code

docker-compose.yml - configures the bank simulator


## API Documentation
For documentation openAPI is included, and it can be found under the following url: **http://localhost:8090/swagger-ui/index.html**

**Feel free to change the structure of the solution, use a different library etc.**

## Bank failure handling

Everything that can go wrong when posting to the acquiring bank is enumerated in
`BankFailure`, which is the single place where "what went wrong" is mapped to "what the
merchant sees". `BankPaymentsProcessor` raises a `BankException` carrying one of these,
and `BankExceptionHandler` renders it; nothing else escapes.

Failures are reported as `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Payment could not be processed",
  "status": 504,
  "detail": "The acquiring bank did not respond in time. The payment may or may not have been taken; check its status with the bank before retrying.",
  "code": "bank_timeout",
  "payment_outcome": "unknown"
}
```

`code` is stable and meant to be branched on, so callers never parse `detail`.

`payment_outcome` answers the question that actually matters to a merchant: **may the bank
have taken the money?** `not_processed` means it definitely did not and the request can be
re-submitted safely. `unknown` means it might have, and re-submitting risks charging the
cardholder twice — so those cases deliberately carry no `Retry-After`.

| What happened | Status | `code` | `payment_outcome` |
|---|---|---|---|
| Connection refused, DNS failure, TLS failure | 503 | `bank_unreachable` | `not_processed` |
| Connect or read timeout | 504 | `bank_timeout` | `unknown` |
| Bank returned 502 / 503 / 504 | 503 | `bank_unavailable` | `not_processed` |
| Bank returned 500 or another 5xx | 502 | `bank_error` | `unknown` |
| Bank returned 429 | 503 | `bank_rate_limited` | `not_processed` |
| Bank returned 400 / 404 / 405 / 415 / 422 | 502 | `bank_rejected_request` | `not_processed` |
| Bank returned 401 / 403 | 502 | `bank_unauthorized` | `not_processed` |
| 2xx with malformed JSON or an unexpected content type | 502 | `bank_response_unreadable` | `unknown` |
| 2xx with an empty body, no `authorized` flag, or an authorisation with no code | 502 | `bank_response_invalid` | `unknown` |

Notes on the less obvious choices:

- **A bank 4xx is reported as 502, not passed through as 400.** By the time we call the bank
  the merchant's input has already passed our own validation, so a rejected request means
  our contract with the bank is wrong. Returning 400 would blame the merchant for our bug.
- **No `payment_status` is set on these responses.** The three payment statuses describe
  payments that were created, and here none was; `Rejected` in particular would wrongly imply
  the merchant supplied bad data.
- **A missing `authorized` field is an error, not a decline.** `BankPaymentResponse.authorized`
  is a boxed `Boolean` so that an absent field cannot silently default to `false` and turn a
  real authorisation into a decline.
- **The bank's own error text is logged but never returned.** It is written for us, not for the
  merchant, and can name internal endpoints or credentials.
- **Timeouts are configured and required.** `payments.bank.connect-timeout` and
  `payments.bank.read-timeout` (defaults 2s and 5s) are mandatory, because without them a bank
  that accepts connections but never answers would hold request threads open until the gateway
  runs out of them.