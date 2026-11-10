## Requirements

- JDK 17
- Docker

## Project structure

src/main/ - the Payment Gateway

src/test/ - unit tests: the controller and the bank processor

src/e2eTest/ - the end-to-end suite, run against containers.

imposters/ - contains the bank simulator configuration. Don't change this.

.editorconfig - don't change this. It ensures a consistent set of rules for submissions when
reformatting code

docker-compose.yml - configures the bank simulator and the gateway, selected by profile

.env.example - template for the compose settings

.github/workflows/ci.yml - runs the unit tests, then the end-to-end suite

## Running the application

Run `./gradlew bootRun` from the repository root. In IntelliJ, the `PaymentGatewayApplication` run
configuration should do the same thing.

The gateway listens on port 8090 and points to the acquiring bank at `http://localhost:8080` by
default.

## Running the container

Create a `.env` at the root, using `.env.example` as a guide.

Crucially, set `COMPOSE_PROFILES` as per your needs:

- `bank` can be used to run a container with the acquiring bank simulator.
- `gateway` can be used to run a container with the gateway
- `all` can be used to run both simultaneously.

Then run `docker compose up` from the repository root.

## Configuration

Every setting has a default, so the gateway technically needs no specific configuration to run. See
useful variables in the table below, but refer to `.env.example` for the comprehensive list.

| Variable                        | Default                 | Purpose                                  |
|---------------------------------|-------------------------|------------------------------------------|
| `PAYMENTS_BANK_API_URL`         | `http://localhost:8080` | Where the acquiring bank is reached      |
| `PAYMENTS_SUPPORTED_CURRENCIES` | `GBP,USD,EUR`           | Currencies accepted on a payment request |
| `PAYMENTS_LOG_LEVEL`            | `INFO`                  | Log level for the gateway's own packages |

Inside compose the gateway reaches the simulator at `http://bank-simulator:8080`. To run the
`gateway` profile alone against a bank on your host, point `PAYMENTS_BANK_API_URL` at
`http://host.docker.internal:8080`.

## Endpoints

Both endpoints live under `/payment`. Request and response fields are snake_case.

- `POST /payment` - submit a card payment. Returns 200 with the processed payment.
- `GET /payment/{id}` - fetch a payment processed earlier, or 404 if there is no such payment.

A request:

```json
{
  "card_number": "2222405343248877",
  "expiry_month": 4,
  "expiry_year": 2028,
  "currency": "GBP",
  "amount": 100,
  "cvv": "123"
}
```

And its response:

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "payment_status": "Authorized",
  "status": 200,
  "card_number_last_four": "8877",
  "expiry_month": 4,
  "expiry_year": 2028,
  "currency": "GBP",
  "amount": 100
}
```

A response never carries the full card number or the CVV, only `card_number_last_four`. The same
goes for errors: `card_number` and `cvv` are masked as `***` when showing/ sending validation
errors.

## API Documentation

For documentation openAPI is included, and it can be found under the following url:
**http://localhost:8090/swagger-ui/index.html**

## Storage

Processed payments are held by `PaymentsRepository`, a temporary in-memory map (thus payments are
lost on restart).

It can be swapped out by any class implementing the `PaymentsPersistence` interface.

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

## Responses

The Payment Gateway should always return a response, avoiding an internal error wherever possible.
For a valid, successful payment request or payment fetch, the processed payment details should be
returned with a `payment_status` of `Authorized` or `Declined`. Otherwise, the response should be an
error with relevant details, carrying `payment_status` `Rejected` and served as
`application/problem+json`.

The one exception is a payment that could not be found: a missing payment is not a rejected one, so
a 404 carries no `payment_status`.

Errors can occur primarily in two distinct situations:

- A payment request could not be parsed successfully
- The acquiring bank induced a failure

If any error does not fall into a known category, we return a 500 as a fallback.

### Request failures

- 400 if a field fails validation (e.g. wrong type, too long/ short, outside a reasonable range)
- 404 if a requested payment does not exist
- 405 if the method is not supported for that path
- 415 if the content type of the request is not supported

### Bank failures

Failures when contacting the acquiring bank fall into three buckets, listed in `BankFailure`.

| Case                                                      | Status | `detail`                                                   |
|-----------------------------------------------------------|--------|------------------------------------------------------------|
| Bank answered 4xx                                         | 502    | The acquiring bank rejected the request from this gateway. |
| Bank answered 5xx                                         | 503    | The acquiring bank is currently unavailable.               |
| Bank unreachable, timed out, or its response was unusable | 502    | The acquiring bank could not be reached.                   |

Connect and read timeouts default to 2s and 5s, overridable with `PAYMENTS_BANK_CONNECT_TIMEOUT`
and `PAYMENTS_BANK_READ_TIMEOUT`.

## Observability

The Payment Gateway has logs encompassing various log levels, for increasingly detailed debugging
needs. `PAYMENTS_LOG_LEVEL` sets the level for the gateway's own packages at startup, and
`/actuator/loggers` changes it on a running instance.

It also has an actuator. The following are exposed over HTTP:

| Path                                             | Purpose                                               |
|--------------------------------------------------|-------------------------------------------------------|
| `/actuator`                                      | Index, linking to everything exposed below            |
| `/actuator/health`                               | Liveness and readiness of the application             |
| `/actuator/health/liveness`, `/health/readiness` | The individual probes, for orchestrators              |
| `/actuator/metrics`, `/actuator/metrics/{name}`  | Metric names, and the measurements behind each one    |
| `/actuator/loggers`, `/actuator/loggers/{name}`  | Read a log level; `POST` changes it without a restart |

The container healthcheck and the end-to-end suite both wait on `/actuator/health`.
