# naqqa SSO overview

naqqa SSO is an identity stack made of three parts.

| Part | What it is |
|---|---|
| `naqqa-sso` 1.0.0 | Zero-dependency Java 17 core (JDK crypto only): JOSE, OIDC provider, SAML IdP, WebAuthn, MFA, SCIM, API tokens, rate limits, risk, audit, quotas and metering. |
| `naqqa-auth` 3.0.0 | Spring Boot 3 starter that wires the core to Mongo, Redis and HTTP endpoints under `/api/auth` (plus `/scim/v2` and `/saml2/idp/*`). Configured with `naqqa.sso.*`. |
| `@naqqa/auth` | Angular library with login, MFA, magic link and security settings UI. Reads available methods from `GET /api/auth/methods`. |

## Auth methods

| Method | Property switch | Notes |
|---|---|---|
| Password | `password.enabled` (on by default) | PBKDF2, legacy hashes rehashed on login |
| Magic link | `magic-link.enabled` | Bound to the requesting browser by default |
| Email OTP / SMS OTP | `email-otp.enabled`, `sms-otp.enabled` | Rate limited, attempt capped |
| TOTP + recovery codes | `totp.enabled`, `recovery.enabled` | Second factor |
| Passkey (usernameless) | `passkey.enabled` | WebAuthn discoverable credentials |
| Biometric passkey | `biometric.enabled` | Platform authenticator (Face ID, fingerprint) |
| Security key | `security-key.enabled` | Roaming FIDO2 key |
| Push approval | `push.enabled` | Number matching |
| Social login | `social.providers` | Link only on verified email |
| SAML (as SP of a corporate IdP) | `saml.enabled`, `saml.idps` | |
| OIDC provider, SAML IdP | `oidc-provider.enabled`, `saml.idp.*` | Your apps sign in through naqqa |
| SCIM 2.0 | `scim.enabled` | User and group provisioning |
| Device code, client credentials, API tokens | `device-code`, `client-credentials`, `api-tokens` | CLIs, TVs, server to server |

Every method can be overridden per client under `naqqa.sso.clients.<id>.*`.

## Architecture

```
Browser / app
  |  @naqqa/auth (Angular)  -- cookies + bearer, refresh on 401
  v
naqqa-auth (Spring Boot)  -- controllers, Mongo repositories, Redis counters
  v
naqqa-sso (pure Java)     -- protocol logic, crypto, policy; no framework
```

The core never opens a socket or a database. Storage, email, SMS and clock are injected, which is why it can be tested with vectors and run anywhere.

## Quotas and metering

`com.naqqa.sso.billing` provides `QuotaPolicy`, `PlanQuotas` (FREE, PRO, ENTERPRISE presets, all configurable), `UsageMeter` (distinct monthly active users stored as HMAC tokens, never raw ids) and `MeterSink` (in-memory or JSON lines file, or your billing hook). A soft limit only warns. A hard limit blocks provisioning, admin operations and API overuse. It never blocks sign-in to an already provisioned account.

## Next steps

Start with `spring-boot.md` and `angular.md`, then `go-live-checklist.md`.
