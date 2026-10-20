# Naqqa ACS (`com.naqqa:naqqa-acs`)

AI Clone Shield integration for Naqqa Spring Boot backends. The ACS engine (edge guard, acs-api, browser SDK) stays a separate product in `ai-clone-shield`; this module is only the in-app side:

- `com.naqqa.acs`: the servlet verifier ported from `ai-clone-shield/integrations/spring` (`AcsVerifier`, `AcsFilter`, `PathCanon`, `IpUtil`, `NonceStore`, `Json`) plus `AcsRules` (rule specs from configuration). Pure JDK crypto (Ed25519, HMAC, HKDF). Conformance: `AcsVectorsTest` and `AcsFilterTest` run against `src/test/resources/acs-vectors.json`, generated from the TypeScript core (`ai-clone-shield`: `node scripts/gen-vectors.mjs`).
- `keys/AcsKeys`: pinned, refreshed attestation key ring (F-059). Every key must match a pin; https only (plain http only for loopback in dev mode); a failed or empty refresh keeps the last good set.
- `web/AcsVerifierGate`: runs the verifier. Monitor: refusals are only recorded (`request attribute acs.verifier.refused`, header `x-acs-would: challenge`). Enforce: refusals are returned (401 + `x-acs-challenge`). Without a trusted key, the fail-open paths (login, register, password reset) always pass and other protected routes answer 503 in enforce mode.
- `events/AcsEventReporter`: ships decision events to the ACS API (`POST /v1/events`, key-id HMAC signature derived from the secret key; the secret is never sent or logged). Bounded queue, batches of 500, never blocks a request.
- `spi/AcsEventSink`: the event reporting hook. A host bean of this type replaces the default reporter; host glue (for example OMY's scoring filter) injects it to report its own decisions.

Spring Boot 3.4.x library jar, own pom, Java 21. Off unless `naqqa.acs.enabled=true`.

## Properties (`naqqa.acs.*`)

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | turns the module on |
| `mode` | `monitor` | `monitor` records refusals, `enforce` returns them |
| `site-id` | - | ACS site id the tokens are issued for (required for token checks) |
| `api-url` | - | ACS API base (`/v1/keys`, `/v1/events`); https only |
| `secret-key` | - | `acs_sk_...` key for event reporting |
| `keys-pin` | - | comma separated base64url SHA-256 pins of trusted attestation keys |
| `public-keys` | - | optional static keys `kid:base64url,...` (still pinned) |
| `keys-refresh-seconds` | `300` | key ring refresh period |
| `session-secret` | - | guard session secret (base64url); enables `signed` rules and the guard assertion |
| `dev-mode` | `false` | allows plain http to a loopback `api-url` |
| `clock-tolerance-ms` | `5000` | token clock tolerance |
| `trusted-proxies` | `127.0.0.1/32,::1/128` | peers allowed to set `X-Forwarded-For` (bare addresses become /32 or /128) |
| `strict-ip-binding` | `false` | refuse instead of flag attestations used from another IP class |
| `verifier.enabled` | `true` | registers the verifier gate |
| `verifier.url-patterns` | `/api/*` | servlet URL patterns of the gate |
| `verifier.order` | `HIGHEST_PRECEDENCE + 10` | filter order |
| `verifier.sensitive` | - | `METHOD PATH ACTION` list: attestation + single-use action token |
| `verifier.signed` | - | `METHOD PATH` list: request signature (only with `session-secret`) |
| `verifier.fail-open-paths` | login, register, forgot/reset password | paths that pass when no trusted key is available |
| `events.enabled` | `true` | creates the default `AcsEventReporter` when `api-url` and a valid `secret-key` are set |

Paths use globs (`*` one segment, `**` any) and the method `*` matches every method.

Example (OMY, `promotion-server`):

```
naqqa.acs.enabled=${ACS_ENABLED:false}
naqqa.acs.mode=${ACS_MODE:monitor}
naqqa.acs.site-id=${ACS_SITE_ID:omy}
naqqa.acs.verifier.sensitive=POST /api/auth/login login,POST /api/contact form
naqqa.acs.verifier.signed=GET /api/promotions
```

## Build

```
cd naqqa-backend-services/acs
mvn install
```

Then depend on `com.naqqa:naqqa-acs:0.0.1`. Publishing to GitHub Packages (`mvn deploy`) is needed before CI builds of a consumer can resolve it.
