# Naqqa Commons (`com.naqqa:naqqa-commons`)

Cross-cutting building blocks shared by the Naqqa backend modules, so the uniform error,
paging, validation and logging concerns described in `ARCHITECTURE.md` section 3 live in one
place instead of being copy-pasted per module. Dependency-light: Spring Web plus Bean Validation
only (Jackson annotations are `provided`, already present in every consumer via
`spring-boot-starter-web`).

Spring Boot 3.4.x library jar, own pom, Java 21. Auto-configures on a servlet web app.

## What it gives you

### Uniform API errors (`com.naqqa.commons.error` + `com.naqqa.commons.web`)

- `ApiError` - the error body `{ status, code, message, details? }`. `details` is omitted when
  null. The core `{status, code, message}` fields match what the analytics module already emits.
- `ErrorCode` - the canonical lowercase_snake code enum (`bad_request`, `unauthorized`,
  `forbidden`, `not_found`, `conflict`, `validation_failed`, `range_too_large`, `rate_limited`,
  `internal_error`, `unavailable`) each with a default HTTP status.
- `ApiException` - the typed exception the service layer throws, with `badRequest` / `unauthorized`
  / `forbidden` / `notFound` / `conflict` factories.
- `ApiExceptionHandler` - a `@RestControllerAdvice` (registered by the auto-configuration) that
  renders `ApiException` and the two standard Bean Validation failures as `ApiError`. It runs at
  `LOWEST_PRECEDENCE`, so a module's own `assignableTypes`-scoped advice (analytics, banners) still
  wins for its controllers, and it installs no catch-all `Exception` handler, so unrelated host
  exceptions keep their behaviour.

### Paging (`com.naqqa.commons.web`)

- `Pagination` - deterministic zero-based `window(...)` / `slice(...)` helpers that reproduce the
  exact `list(page, size)` arithmetic the notification and audit-log services hand-rolled
  (`size <= 0` falls back to a default, `page` clamps to `>= 0`, `totalPages` is at least 1).
- `PageResponse<T>` - the canonical `{ items, total, totalItems, totalPages, currentPage, size }`
  envelope for new list endpoints. Existing modules keep their own named envelope
  (`NotificationsPage`, `UserLogsPage`) for wire compatibility but compute it via `Pagination`.

### Validation (`com.naqqa.commons.validation`)

- `Validation` - `requireText` / `requireNonNull` / `requireRange` / `clamp` guards that throw
  `ApiException(bad_request)`, so the web edge validates uniformly and the service assumes valid
  input.

### Logging (`com.naqqa.commons.logging`)

- `OperationLogger` - wraps an SLF4J logger and puts `module` / `op` / `correlationId` on the MDC
  for each call, giving every module the same structured log context.

## Properties (`naqqa.commons.*`)

- `exception-handler` (default `true`) - register the shared `ApiExceptionHandler`.

## Consumers

`naqqa-notifications` computes its page via `Pagination`; `naqqa-observability` depends on commons
for the shared exception handler and uses `OperationLogger` for its issue mutations. Both keep
their exact existing wire contracts.

## Build

```
cd naqqa-backend-services/commons
mvn -q -o package
```
