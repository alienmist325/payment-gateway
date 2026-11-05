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


## Tests

```
./gradlew build      # unit tests; no Docker needed
./gradlew e2eTest    # end-to-end suite; requires Docker
```

`e2eTest` builds the application image, starts it alongside the bank simulator with
Testcontainers, and sends real HTTP requests to the containerised gateway. The first run is
slow (several minutes) because the image build downloads Gradle and all dependencies inside
the container; later runs hit the Docker layer cache.

**A Docker daemon must already be running and reachable from the shell you run Gradle in** —
the suite does not start one. Check with `docker info`. If the daemon lives inside WSL, run
Gradle from there too, or point `DOCKER_HOST` at it; otherwise `e2eTest` stops immediately
with a message rather than hanging. BuildKit is not required.

The suite lives in `src/e2eTest` and is excluded from `./gradlew build`, which keeps the
default build fast and Docker-free. Running an E2E test from the IDE needs the image to exist
first, so run `./gradlew e2eTest` (or `./gradlew buildE2eImage`) at least once — otherwise
Testcontainers tries to pull `payment-gateway:e2e` from Docker Hub and fails.

## API Documentation
For documentation openAPI is included, and it can be found under the following url: **http://localhost:8090/swagger-ui/index.html**

**Feel free to change the structure of the solution, use a different library etc.**