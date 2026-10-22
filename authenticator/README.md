# Naqqa Authenticator (`com.naqqa:naqqa-authenticator`)

From-scratch second factor for Naqqa apps. No third-party OTP library: HOTP (RFC 4226), TOTP (RFC 6238), Base32 (RFC 4648), AES-256-GCM secret sealing, HMAC-SHA256 recovery-code hashing and opaque MFA login challenges are implemented on the JDK only (`javax.crypto`, `java.security`). The REST contract mirrors `naqqa-os-fe` (`@naqqa/auth` `MfaService`, `SSO_DEFAULT_ENDPOINTS`) and its dev mock `src/app/core/dev-data/devdata-mfa.ts`.

Spring Boot 3.4.x library jar, Java 21, depends on `naqqa-commons` for the error contract `{status, code, message}`. Auto-configures on a servlet web app unless `naqqa.authenticator.enabled=false`.

## Endpoints

Base path `naqqa.authenticator.path` (default `/api/auth/mfa`).

| Method | Path | Caller | Body | Response |
| --- | --- | --- | --- | --- |
| POST | `/totp/enroll` | signed-in user | - | `{enrollmentId, otpauthUri, secret, expiresAt}` (`Cache-Control: no-store`) |
| POST | `/totp/confirm` | signed-in user | `{enrollmentId, code}` | `{recoveryCodes: [10 x "xxxx-xxxx"]}` (shown once) |
| GET | `/factors` | signed-in user | - | `[{id, type:"totp", label, createdAt, lastUsedAt}]` (never the secret) |
| DELETE | `/factors/{id}` | signed-in user | - | `{status:"ok"}` |
| POST | `/recovery/regenerate` | signed-in user | - | `{recoveryCodes}` (old set invalidated) |
| POST | `/challenge` | login step | `{mfaToken, method}` | `{status:"sent"}` |
| POST | `/verify` | login step | `{mfaToken, method:"totp"\|"recovery", code, rememberDevice}` | `{status:"ok", user}` (host-issued session) |

Error codes: `UNAUTHORIZED` 401, `MFA_TOKEN_EXPIRED` 401, `OTP_INVALID` 400, `SETUP_EXPIRED` 400, `METHOD_UNAVAILABLE` 400, `INVALID_REQUEST` 400, `NOT_FOUND` 404, `TOTP_ALREADY_ENROLLED` 409, `RATE_LIMITED` 429, `NOT_CONFIGURED` 503.

## Security properties

- Replay protection (RFC 6238 section 5.2): every factor stores the last accepted time-step counter; a code is accepted only for a strictly newer counter, written with a compare-and-set (`MfaStore.recordUse`), so a reused or concurrently replayed code fails. The code typed at enrollment cannot be replayed at login.
- Skew window of +-1 step (configurable 0-3); constant-time comparison of candidates.
- TOTP secrets: 160 random bits, sealed with AES-256-GCM, additional data `naqqa-authenticator:totp:<userId>` (a ciphertext copied to another user does not decrypt).
- Recovery codes: 10 single-use codes, stored only as HMAC-SHA256(pepper, userId:code); consumed atomically.
- Login challenges: 256-bit opaque tokens stored only as SHA-256, 5 minute TTL, single use, 5 attempts then 429, a new challenge supersedes the previous one.
- Enrollment: 10 minute TTL, 5 confirmation attempts.

## Host integration (SPI)

| Port | Default | Host provides |
| --- | --- | --- |
| `MfaStore` | `MongoMfaStore` when a `MongoOperations` bean exists (collections `<prefix>factors/enrollments/challenges/recovery_codes`, TTL indexes), else `InMemoryMfaStore` | optional custom store |
| `AuthenticatorUserResolver` | `SecurityContextUserResolver` (authentication name) | account label for the otpauth URI |
| `MfaLoginCompleter` | none (`/verify` answers 503) | issues the real session after a verified challenge |
| `AuthenticatorEventListener` | no-op | audit/log of enroll, remove, reset, failures, replays |

The host also gates its password login: when the user has an active factor and the password is correct, it calls `AuthenticatorService.beginChallenge` and answers `{status:"mfa_required", mfaToken, methods}` instead of issuing cookies. `AuthenticatorService.resetFactors` backs admin resets.

## Configuration (`naqqa.authenticator.*`)

`encryption-key` (required, base64 of 32 bytes), `issuer` (`naqqa`), `factor-label`, `algorithm` (`SHA1`), `digits` (6), `period-seconds` (30), `skew-steps` (1), `secret-bytes` (20), `enrollment-ttl` (10m), `challenge-ttl` (5m), `max-attempts` (5), `recovery-code-count` (10), `collection-prefix` (`naqqa_mfa_`), `path`.

## Tests

`RfcVectorsTest` (RFC 4226 Appendix D, RFC 6238 Appendix B for SHA1/256/512, RFC 4648), `TotpMatchTest`, `SecretsTest`, `AuthenticatorServiceTest` (flows, replay, limits, expiry, reset), `AuthenticatorControllerTest` (contract), `AuthenticatorAutoConfigurationTest`.
