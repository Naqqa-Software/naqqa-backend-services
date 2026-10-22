# Spring Boot setup

## Dependency

```xml
<dependency>
  <groupId>com.naqqa</groupId>
  <artifactId>naqqa-auth</artifactId>
  <version>3.0.0</version>
</dependency>
```

Packages are on GitHub Packages (`https://maven.pkg.github.com/Naqqa-Software/naqqa-backend-services`); add the server credentials to `settings.xml`. `naqqa-sso` 1.0.0 comes in transitively.

## Requirements

- Java 17, Spring Boot 3.5.
- MongoDB: users, credentials, sessions, tokens, audit.
- Redis: rate limits, lockouts, short-lived challenges (magic link, OTP, WebAuthn, push). Required in any multi-instance deployment.

## Secrets (environment variables only)

| Variable | Purpose |
|---|---|
| `NAQQA_SSO_PEPPER` | Password and token hashing pepper. Required. |
| `NAQQA_SSO_SEALING_KEY` | 32 byte key that encrypts TOTP seeds and similar. Falls back to a key derived from the pepper if absent or not 32 bytes. |
| `NAQQA_SSO_SEALING_KEY_ID` | Id of the sealing key, for rotation. |
| `NAQQA_SSO_AUDIT_KEY` | HMAC key for the tamper-evident audit chain. |
| `NAQQA_SSO_SIGNING_KEY` | Private key for token signing. |

The variable names themselves can be changed with `naqqa.sso.secrets.*-env`. Never put secret values in `application.properties`.

## Minimal configuration

```properties
naqqa.sso.enabled=true
naqqa.sso.issuer=https://auth.example.com
naqqa.sso.rp-id=example.com
naqqa.sso.rp-name=Example
naqqa.sso.allowed-origins=https://app.example.com
naqqa.sso.password.enabled=true
naqqa.sso.magic-link.enabled=true
```

## Property reference (defaults from `SsoProperties`)

Top level: `enabled=false`, `issuer`, `rp-id`, `rp-name`, `allowed-origins=[]`.

| Prefix | Defaults |
|---|---|
| `password` | `enabled=true`, `min-length=8`, `max-length=128`, `iterations=210000`, `rehash-legacy=true` |
| `magic-link` | `enabled=false`, `ttl=15m`, `bind-to-browser=true` |
| `email-otp` | `enabled=false`, `length=6`, `ttl=10m`, `max-attempts=5`, `resend-interval=60s`, `max-sends-per-hour=5` |
| `sms-otp` | `enabled=false`, `length=6`, `ttl=5m`, `max-attempts=5`, `resend-interval=60s`, `max-sends-per-hour=5` |
| `totp` | `enabled=true`, `algorithm=SHA1`, `digits=6`, `step-seconds=30`, `window=1`, `pending-ttl=15m` |
| `recovery` | `enabled=true`, `count=10`, `max-failed-attempts=10` |
| `passkey` | `enabled=false`, `usernameless=true`, `require-user-verification=true`, `attestation=none`, `challenge-ttl=5m` |
| `biometric` | `enabled=false` |
| `security-key` | `enabled=false`, `require-user-verification=false` |
| `push` | `enabled=false`, `ttl=2m`, `max-mismatches=2` |
| `social` | `providers=[]`, `link-verified-email-only=true` |
| `saml` | `enabled=false`, `idps=[]`, `clock-skew=60s`, `assertion-ttl=5m` |
| `saml.idp` | `entity-id`, `certificate`, `private-key`, `login-url`, `want-authn-requests-signed=false`, `pending-ttl=10m`, `service-providers=[]` |
| `oidc-provider` | `enabled=false`, `signing-algorithm=RS256`, `access-token-ttl=15m`, `id-token-ttl=15m`, `authorization-code-ttl=1m`, `require-pkce=true` |
| `scim` | `enabled=false`, `max-results=100`, `max-scan=2000`, `max-start-index=10000`, `base-url` |
| `device-code` | `enabled=false`, `ttl=10m`, `poll-interval=5s` |
| `client-credentials` | `enabled=false`, `access-token-ttl=15m` |
| `api-tokens` | `enabled=false`, `max-failed-attempts=10`, `lock-duration=15m`, `rotation-grace=24h` |
| `dev-mode` | `false`. Dev-only switches (`social.test-mode`, `oidc-provider.backchannel.test-mode`, `risk.signal-override`, `ldap.directories.*.allow-plain-for-tests`, `session.cookie-secure=false`, `app.cookie.secure=false`) refuse to start unless it is `true`; never set it in production |
| `mfa` | `required-roles=[]`, `required-for-all=false`, `required-for-admin=true`, `admin-roles=[ADMIN]`, `admin-authorities=[SSO_ADMIN_*, SSO_IMPERSONATE, SSO_AUDIT, SSO_AUDIT_EXPORT, SSO_API_TOKENS, users/roles/sub_roles write]`, `enrollment-allowed-routes=[GET /api/profile/me]`, `enrollment-profile-routes=[GET /api/profile/me]`, `step-up-max-age=10m`, `mfa-token-ttl=5m`, `remember-device=false`, `remember-device-ttl=30d` |
| `session` | `idle-timeout=8h`, `absolute-timeout=30d`, `access-token-ttl=15m`, `refresh-lifetime=30d`, `bind-to-device=true`, `cookie-secure=true`, `cookie-same-site=Lax` |
| `rate-limits` | `login=10/1m`, `otp=5/1m`, `magic-link=5/10m`, `mfa-verify=10/5m`, `token=60/1m`, `lockout-threshold=10`, `lockout-base=30s`, `lockout-max=1h` |
| `risk` | `enabled=false`, `step-up-threshold=50`, `deny-threshold=90`, `new-device-score=30`, `new-ip-range-score=20`, `impossible-travel-score=60`, `failure-velocity-score=40` |
| `secrets` | `pepper-env`, `sealing-key-env`, `sealing-key-id-env`, `audit-key-env`, `signing-key-env` (names shown above) |

Rate limit entries are `max` and `window`, for example `naqqa.sso.rate-limits.login.max=10` and `naqqa.sso.rate-limits.login.window=PT1M`. Durations accept `15m` or ISO-8601.

## Enabling methods per client

```properties
naqqa.sso.clients.web.enabled=true
naqqa.sso.clients.web.password=true
naqqa.sso.clients.web.passkey=true
naqqa.sso.clients.web.magic-link=false
naqqa.sso.clients.web.social-providers=google
naqqa.sso.clients.web.mfa-required-for-all=true
naqqa.sso.clients.web.allowed-origins=https://app.example.com
naqqa.sso.clients.web.redirect-uris=https://app.example.com/callback
```

Unset values inherit the global setting. Other per-client keys: `email-otp`, `sms-otp`, `totp`, `recovery`, `biometric`, `security-key`, `push`, `saml`, `saml-sp`, `mfa-required-roles`, `ldap-directory`. Session, refresh and step-up lifetimes are global only (`naqqa.sso.session.*`, `naqqa.sso.mfa.step-up-max-age`): a session is shared by every client.

## Biometric passkeys

```properties
naqqa.sso.passkey.enabled=true
naqqa.sso.biometric.enabled=true
naqqa.sso.rp-id=example.com
naqqa.sso.allowed-origins=https://app.example.com,https://www.example.com
naqqa.sso.passkey.require-user-verification=true
```

`rp-id` must be the registrable domain (or a parent of the page host) and cannot be changed later without invalidating every registered passkey. Origins must match exactly, scheme and port included. Passkeys need HTTPS except on `localhost`. Native apps need the platform association files (`assetlinks.json`, `apple-app-site-association`) for the same rp-id.

## Using naqqa-sso directly

naqqa-auth sets the values below for you. A service that builds the core objects itself must set them explicitly:

- `SafeHttp.Options.forTests()`, `LdapConfig.plainForTests` and `withAllowPlainForTests(true)` are test switches. Use `SafeHttp.production()` and LDAPS or StartTLS in production. naqqa-auth refuses its test switches unless `naqqa.sso.dev-mode=true`.
- `WebAuthnPolicy.builder()` defaults to user verification `PREFERRED` and accepts `none` attestation (the WebAuthn defaults). For passwordless sign-in set `userVerification(REQUIRED)` or use `WebAuthnPreset.BIOMETRIC`; `WebAuthnPreset.SECURITY_KEY` is for a second factor only.
- `ProviderConfig.microsoft` with tenant `common` (or none) and an empty `allowedTenants` accepts every Entra tenant and personal Microsoft accounts. Pass a tenant id or the allowed tenant ids to restrict it. Microsoft emails are never trusted for linking by default.
- `OidcProvider.builder(clock, config)` without a rate-limit store uses an in-memory one (limits per node) and without an audit sink records nothing. Set both in any multi-instance deployment.
- `SamlSpConfig` and `SamlIdpConfig` default `clockSkew` to 3 minutes (naqqa-auth passes `naqqa.sso.saml.clock-skew`, 60s). Lifetimes, skews and attempt limits in `OidcConfig`, `SamlSpConfig`, `SamlIdpConfig`, `OtpConfig`, `ThrottleConfig`, `MagicLink`, `PushApproval` and `RefreshTokenService` are taken as given: keep them at the documented defaults.
- `ApiTokenService` reports `token.throttled` but does not block by itself; put a `LoginThrottle` in front of it as `ApiTokenAuthenticationFilter` does.
- `ServiceProvider` (SAML IdP) and OIDC client registrations refuse non-https redirect, ACS and SLO URLs (http only on loopback).
