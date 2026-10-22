# naqqa Backend Architecture (Target)

Authoritative target architecture for `naqqa-backend-services`: a **modular monolith**
of reusable Spring Boot library modules (vertical feature slices), each shaped by
**hexagonal / ports-and-adapters**, each **contract-first** against the `naqqa-os-fe`
dev-data, each pluggable into a host app through **SPI host hooks**. This is the
blueprint the incremental refactor follows. It records what already exists versus
what the refactor will make uniform. It is not a rewrite mandate: see
`../naqqa-os-fe/REFACTOR-PLAN.md`.

Stack verified from source: Java 21, Spring Boot 3.4.x, Maven, one `pom.xml` per
module, published as auto-configuring library jars under group `com.naqqa`.

---

## 1. Modular monolith of library modules

Each feature is an independent Maven module with its own `pom.xml`, built as a
Spring Boot **library jar** (not an executable app) that auto-configures when placed
on a host application's classpath. A host service (for example OMY, or `naqqa-os-be`)
composes the modules it needs; a module never assumes it is the whole application.

Verified modules (top-level directories, each with `src/main/java/com/naqqa/<module>`):

| Module | Package | Role |
| --- | --- | --- |
| `observability` | `com.naqqa.observability` | token-gated OTLP/Sentry-style ingestion, logs, metrics, traces, issues with root cause, plus product analytics, APM, infra, SLOs, alerts, incidents, synthetics, catalog, AIOps |
| `registry` | `com.naqqa.registry` | token-gated read-only npm registry for naqqa-ui packages: per customer tokens (package sets, expiry, revocation), permission-gated admin API, npm metadata and tarballs served from a storage directory |
| `authenticator` | `com.naqqa.authenticator` | from-scratch TOTP second factor (RFC 6238 on an RFC 4226 core), recovery codes, MFA login challenges |
| `commons` | `com.naqqa.commons` | shared error contract, validation, paging, operation logging |
| `audit-logs` | `com.naqqa.auditlogs` | user activity log stream + paging |
| `acs` | `com.naqqa.acs` | AI Clone Shield in-app integration: attestation/action-token/request-signature verifier filter, pinned key ring, decision event reporting, monitor or enforce |
| `notifications` | `com.naqqa.notifications` | notification feed + categories |
| `translations` | `com.naqqa.translations` | multilingual translation catalogue (en/ro/ru) |
| `analytics` | `com.naqqa.analytics` | analytics events/reporting |
| `search` | `com.naqqa.search` | search API facade |
| `elasticsearch` | `com.naqqa.*` | Elasticsearch-backed search engine |
| `chatbot-ai` | `com.naqqa.chatbot` | AI chatbot endpoints |
| `chat` | `com.naqqa.chat` | chat / messaging |
| `auth` | `com.naqqa.auth` | authentication / SSO primitives |
| `sso` | `com.naqqa.sso` | naqqa SSO, zero-dependency identity core (artifact `naqqa-sso` 1.0.0): OIDC provider, OAuth social login, SAML, WebAuthn passkeys, TOTP/OTP/magic link, SCIM, sessions, tenant tokens, rate limiting, risk, audit, billing; docs in `sso/docs` |
| `entity` | `com.naqqa.entity` | dynamic entity engine |
| `file-storage` | `com.naqqa.filestorage` | file storage + transcoder |
| `storage` | `com.naqqa.storage` | object/blob storage |
| `scheduler` | `com.naqqa.scheduler` | scheduled jobs |
| `tts` | `com.naqqa.tts` | text-to-speech |

Module boundary rules:

- A module depends only on its own package plus (future) a shared `naqqa-commons`.
  **No cross-module `com.naqqa.<other>` imports.** Modules integrate through SPI
  ports and REST, never by reaching into each other's internals.
- A module ships safe dev defaults so it runs standalone; the host overrides for
  production (section 4).

---

## 2. Hexagonal / ports-and-adapters layout

The mature modules follow a consistent internal package layout. This is the target
for every module.

```
com.naqqa.<module>
  web/            <- inbound adapter: REST controllers, request mapping
  dto/            <- explicit data contracts (records) crossing the boundary
  service/        <- application + domain logic (pure, framework-light)
  spi/            <- OUTBOUND PORTS (interfaces) + Dev* default adapters
  config/         <- XxxAutoConfiguration + XxxProperties (composition root)
  (repository/)   <- outbound adapter for persistence-backed modules
```

Dependency direction (strict, inward):

```
web (api/dto)  ->  service (application/domain)  ->  spi port (interface)
                                                        ^
                                   adapter: Dev*Source (default) OR host bean
```

- **`web`** translates HTTP to/from `dto`; it holds no business logic.
- **`dto`** are explicit records, one per contract shape. No entity/JPA objects and
  no `Map<String,Object>` leak across the boundary.
- **`service`** is the application core. It depends on **SPI interfaces**, never on
  a concrete data source, HTTP, or another module.
- **`spi`** declares the outbound ports (`...Source`, `...Store`) the service needs,
  plus a `Dev*` in-memory/deterministic default implementation so the module is
  self-contained in dev.
- **`config`** is the only place Spring wiring lives (auto-configuration + typed
  properties). The domain/service code has no Spring stereotype coupling beyond what
  the config needs to instantiate it.

### 2.1 Verified exemplars

**notifications** (full, canonical shape):
`web/NotificationController`, `service/NotificationService`,
`dto/{Notification, NotificationCategory, NotificationsPage}`,
`spi/{NotificationSource (port), DevNotificationSource (default adapter)}`,
`config/NotificationsAutoConfiguration`.

**audit-logs**: `web/AuditLogController`, `service/AuditLogService`,
`dto/{UserLog, UserLogDomain, UserLogType, UserLogsPage}`,
`spi/{AuditLogSource, DevAuditLogSource}`, `config/AuditLogsAutoConfiguration`.

**acs**: `AcsVerifier`, `AcsFilter`, `AcsRules`, `web/{AcsVerifierGate, AcsPaths}`,
`keys/AcsKeys`, `events/{AcsEventReporter, AcsRequestSigner}`, `spi/AcsEventSink`,
`config/{AcsAutoConfiguration, AcsProperties}`.

**translations**: `web/TranslationsController`, `service/TranslationsService`,
`dto/TranslationsDtos`, `spi/{TranslationsStore (port), InMemoryTranslationsStore
(default), TranslationsSeedProvider}`, `config/{TranslationsAutoConfiguration,
TranslationsProperties}`.

**observability**: `web/ObservabilityController`, `dto/ObsDtos`,
`service/{ObservabilityService, ObsBreadthService, ObsAnalyticsBuilder, Rng}`,
`config/{ObservabilityAutoConfiguration, ObservabilityProperties}`. (Generates
deterministically from `projectId` rather than via an SPI store; the deterministic
generator is its service-layer adapter.)

**chatbot-ai**: full set including `web/service/dto/spi/repository`.

---

## 3. Explicit DTOs, uniform errors / validation / logging

Target contract for every module boundary:

- **DTOs are records**, named by shape, grouped per module in `dto/`. Page responses
  follow the existing convention `XxxPage { items, total, nextCursor }` (verified in
  `UserLogsPage`, `NotificationsPage`, and the observability `logs`/`issues`
  responses). A paginated endpoint always returns this envelope.
- **Uniform errors.** A single error contract (`{ code, message, details? }`) and a
  shared `@ControllerAdvice` exception mapper, to live in the future
  `naqqa-commons`. Modules throw typed domain exceptions; the advice renders them.
- **Validation** at the `web` edge via Bean Validation on request DTOs; the service
  assumes validated input.
- **Logging** structured and uniform (module + operation + correlation id), provided
  by a shared logging aspect/filter in `naqqa-commons`, so every module logs the
  same way without per-module boilerplate.

> These three are the least uniform aspects today and are the backend refactor's
> primary target (backlog item BE1).

---

## 4. SPI host hooks (auto-configuration + conditional beans)

Every module is a Spring Boot auto-configuration. Verified pattern
(`NotificationsAutoConfiguration`):

```java
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.notifications", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class NotificationsAutoConfiguration {

  @Bean @ConditionalOnMissingBean
  public NotificationSource naqqaNotificationSource() { return new DevNotificationSource(); }

  @Bean @ConditionalOnMissingBean
  public NotificationService naqqaNotificationService(NotificationSource source) { ... }

  @Bean @ConditionalOnMissingBean
  public NotificationController naqqaNotificationController(NotificationService service) { ... }
}
```

Registered via `src/main/resources/META-INF/spring/
org.springframework.boot.autoconfigure.AutoConfiguration.imports`
(verified in `notifications`).

Host-hook contract:

- **Enable/disable** per module: `naqqa.<module>.enabled` (default on).
- **Scope**: `@ConditionalOnWebApplication` so library jars stay inert in non-web
  contexts.
- **Override-by-bean**: every bean is `@ConditionalOnMissingBean`. The host app
  supplies a real adapter (a production `NotificationSource`, `AuditLogSource`,
  `TranslationsStore`, ...) and it transparently replaces the shipped `Dev*` default.
  This **is** the SPI host hook: the port is the extension point, the `Dev*` class is
  the fallback, the host bean is the production adapter.
- **Typed properties**: `XxxProperties` (verified in observability, translations)
  bind `naqqa.<module>.*` (e.g. `naqqa.observability.path`).

---

## 5. Contract-first: REST mirrors the os-fe dev-data

The REST contract of each module is defined **first** by the `naqqa-os-fe` dev-data
and mirrored exactly by the backend. The frontend develops against
`src/app/core/dev-data/*` behind its dev-data interceptor; the backend module
reproduces the identical shapes so the frontend switches from mock to live with no
client change.

Verified reference: `observability/README.md` documents every endpoint
(`/metrics`, `/logs`, `/issues`, `/analytics/overview`, `/apm`, `/infra`, `/slos`,
`/alerts`, `/incidents`, `/synthetics`, `/catalog`, `/aiops`) with its top-level
response shape and the exact dev-data file it mirrors
(`devdata-observability.ts`, `devdata-obs-breadth.ts`), and states that generators
are deterministic per `projectId` (ported LCG + FNV-1a) so values match the mock.

Contract-first rules:

- Dev-data file in os-fe <-> DTO record set in the module <-> endpoint table in the
  module README. All three stay in lockstep.
- Base paths are configurable (`naqqa.<module>.path`, default `/api/<module>`),
  matching the os-fe `/api/...` interceptor prefix.
- A module's README is the human-readable contract. `observability/README.md` is the
  standard every module README is brought up to.

---

## 6. Current state vs target (verified layer coverage)

Layer folders present per module today:

| Module | web | service | dto | spi | repository |
| --- | --- | --- | --- | --- | --- |
| observability | yes | yes | yes | yes | yes (store) |
| authenticator | yes | yes | yes | yes | yes (store) |
| registry | yes | yes | yes | yes | yes (store) |
| notifications | yes | yes | yes | yes | - |
| translations | yes | yes | yes | yes | - |
| audit-logs | yes | yes | yes | yes | - |
| acs | yes | - | - | yes | - |
| chatbot-ai | yes | yes | yes | yes | yes |
| analytics | yes | yes | - | yes | - |
| auth | - | yes | yes | - | yes |
| entity | - | yes | yes | - | yes |
| chat | - | yes | - | yes | yes |
| elasticsearch | - | yes | - | - | yes |
| file-storage | - | yes | - | - | yes |
| scheduler | - | yes | - | - | yes |
| search | yes | - | - | - | - |
| tts | yes | - | - | - | - |
| storage | - | - | - | - | - |

Reading of the table:

- **Mature / canonical:** `notifications`, `translations`, `audit-logs`,
  `observability`, `chatbot-ai` - full web+service+dto with SPI ports (or a
  deterministic service adapter). These define the template.
- **Persistence-backed:** `auth`, `entity`, `chat`, `elasticsearch`, `file-storage`,
  `scheduler` use `repository` as their outbound adapter. Target: add the explicit
  `spi` port layer so the service depends on an interface, with the repository as one
  adapter behind it, and add the missing `web`/`dto` where they expose HTTP.
- **Thin / incomplete:** `search` and `tts` have only `web`; `storage` has no Java
  layer folders yet; `analytics` lacks `dto`. These are brought up to the template
  (backlog item BE2).

---

## 7. What the refactor changes (summary)

Existing and correct: the modular-monolith decomposition, the hexagonal layout in
the mature modules, auto-configuration + `@ConditionalOnMissingBean` SPI host hooks,
`AutoConfiguration.imports` registration, the contract-first dev-data mirror, typed
properties, the paging DTO envelope.

To be made uniform incrementally (full plan in
`../naqqa-os-fe/REFACTOR-PLAN.md`, phase P4):

1. Extract a shared **`naqqa-commons`** module: error contract + `@ControllerAdvice`,
   validation helpers, structured logging filter/aspect, the `XxxPage` envelope, and
   shared property conventions. Every module depends on it; nothing is copy-pasted.
2. Bring thin modules (`search`, `tts`, `storage`, `analytics`) up to the
   notifications/observability template (config + web + service + dto + spi,
   `AutoConfiguration.imports`, README contract mirror).
3. Introduce explicit `spi` ports in the persistence-backed modules so the service
   depends on an interface, not a repository.
4. Uniform errors/validation/logging across all modules via `naqqa-commons`.
5. Each module README documents its full endpoint contract against its os-fe
   dev-data file, to the `observability/README.md` standard.

Propagation policy: modules are **versioned artifacts**. A change to a shared
contract or to `naqqa-commons` is released as a new version; consumers adopt an
**always-latest-with-tests** policy (bump, run the module's tests + the host's tests,
ship green). This is the backend counterpart of the frontend's instant monorepo
propagation (see `../naqqa-os-fe/CONVENTIONS.md`).
