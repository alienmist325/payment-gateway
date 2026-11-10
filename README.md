## Requirements

- JDK 17
- Docker

## Project structure

main/ - the Payment Gateway

test/ - Some simple JUnit tests

imposters/ - contains the bank simulator configuration. Don't change this

.editorconfig - don't change this. It ensures a consistent set of rules for submissions when
reformatting code

docker-compose.yml - configures the bank simulator

## Running the application

The `PaymentGatewayApplication` run configuration launches the gateway (running the
`payment-gateway-challenge-java.main` module).

Typically, this can be run against the mock acquiring bank simulator, typically launched from a
docker container.

## Running the container

Create a `.env` at the root, using `.env.example` as a guide.

Crucially, set the profile as per your needs:

- `bank` can be used to run a container with the acquiring bank simulator.
- `gateway` can be used to run a container with the gateway
- `all` can be used to run both simultaneously.

Then run `docker compose up` from the repository root.

## Tests

We have the following tests:

- Unit tests (e.g. testing controller, processor etc. in isolation)
- E2E tests (i.e. testing the flow from request to response, using a mock storage repository and
  mock bank system)

### Unit Tests

Uses Mockito. Run the following:

```
./gradlew build      # unit tests; no Docker needed
```

### E2E Tests

Uses Testcontainers for the app and bank mock. Run the following:

```
./gradlew e2eTest    # end-to-end suite; requires Docker
```

## API Documentation

For documentation openAPI is included, and it can be found under the following url:
**http://localhost:8090/swagger-ui/index.html**

## Responses

The Payment Gateway should always return a response, avoiding an internal error wherever possible.
For a valid, successful payment request or payment fetch, the processed payment details should be
returned with a `payment_status` of `Authorized` or `Declined`. Otherwise, the response should be an
error with `payment_status` `Rejected` and relevant details.

Errors can occur primarily in two distinct situations:

- A payment request could not be parsed successfully
- The acquiring bank induced a failure

If any error does not fall into a known category, we return a 500 as a fallback.

### Request failures

- 400 if a field fails validation (e.g. wrong type, too long/ short, outside a reasonable range)
- 405 if the method does not exist
- 404 if a requested payment does not exist

### Bank failures

Failures when contacting the acquiring bank fall into three buckets, listed in `BankFailure`. Each
is
returned as `application/problem+json` with `payment_status: Rejected`.

| Case                                                      | Status | `detail`                                                   |
|-----------------------------------------------------------|--------|------------------------------------------------------------|
| Bank answered 4xx                                         | 502    | The acquiring bank rejected the request from this gateway. |
| Bank answered 5xx                                         | 503    | The acquiring bank is currently unavailable.               |
| Bank unreachable, timed out, or its response was unusable | 502    | The acquiring bank could not be reached.                   |

Connect and read timeouts default to 2s and 5s, overridable with `PAYMENTS_BANK_CONNECT_TIMEOUT`
and `PAYMENTS_BANK_READ_TIMEOUT`.

## Observability

The Payment Gateway has logs encompassing various log levels, for increasingly detailed debugging
needs.

It also has an actuator, which exposes typical metrics, found at http://localhost:8090/actuator/.