# naqqa SSO - Plan

naqqa SSO is the identity platform of naqqa. It lives in the two shared library repos:

- `naqqa-backend-services/sso` - `com.naqqa:naqqa-sso`, pure Java 17, zero runtime dependencies (JDK only). All protocol logic, parsers and flows. Framework-agnostic, storage behind interfaces, in-memory implementations for tests.
- `naqqa-backend-services/auth` - `com.naqqa:naqqa-auth`, the Spring Boot integration: HTTP endpoints, Mongo/Redis stores, Spring Security filter, configuration. It depends on `naqqa-sso` and drops jjwt, Nimbus JOSE, spring-security-oauth2-client and spring-security-oauth2-resource-server.
- `naqqa-ui/projects/auth` - `@naqqa/auth`, the Angular library: login, MFA, passkeys (biometrics), magic link, OTP, account security, OIDC client with PKCE. Browser crypto only (WebCrypto, WebAuthn API).

## 1. Decisions

| # | Decision | Why |
|---|----------|-----|
| D1 | Crypto only from JDK (`java.security`, `javax.crypto`) and WebCrypto. No custom primitives. | Handoff rule. |
| D2 | Password hashing: PBKDF2-HMAC-SHA512, 210000 iterations, 16-byte salt, HMAC-SHA256 pepper pre-hash, PHC-style string `$pbkdf2-sha512$v=1$i=210000$<salt>$<hash>`. Legacy BCrypt hashes (existing users) verified by the auth module via Spring Security and rehashed on login. | JDK has no scrypt/Argon2; PBKDF2-SHA512 at 210k is the OWASP setting. Hand-rolling Salsa20 or Blake2 would be a custom primitive. |
| D3 | Spring Boot, Spring Web, Spring Data and Spring Security core remain as the host framework of the auth module. Every auth protocol library is removed. | The consuming apps run on Spring and use `@PreAuthorize`; the rule targets auth libraries. |
| D4 | Biometrics (fingerprint, face) are done through WebAuthn platform authenticators with `userVerification=required`. Biometric data never leaves the device; the server verifies a signature. | Server-side face matching cannot be done safely from scratch and would store biometric templates (GDPR special category). |
| D5 | SAML XML parser and XML-DSig are written from scratch, strict: no DTD, no entities, no PI except declaration, size and depth limits, Exclusive C14N only, RSA-SHA256 / ECDSA-SHA256 only, signature must cover the exact element that is consumed. | Handoff; removes XXE and signature wrapping classes. |
| D6 | Access tokens: JWS compact, RS256 default (compatible with current apps), EdDSA and ES256 available. `alg` fixed by key, never by header. Refresh tokens opaque, SHA-256 at rest, rotated each use, reuse revokes family. | Handoff 3.4. |
| D7 | MFA secrets and other secrets at rest: AES-256-GCM with key id, 96-bit random nonce, AAD bound to tenant+user+purpose, per-key message cap, key ring rotation. | Handoff 3.4. |
| D8 | SMS / email delivery behind `Transport` interface; implementations: noop, dev-log, and adapters in auth (SendGrid over plain HTTP, SMTP via JDK sockets is out of scope). | Transport is config, not a library. |
| D9 | Every auth method is enabled/disabled and tuned per application (client) via `naqqa.sso.*` properties and per-client policy in the store. | Owner request R03. |

## 2. Package layout of `naqqa-sso`

| Package | Content |
|---------|---------|
| `util` | `Bytes` (base64url canonical, CSPRNG, constant-time equals), `Hashes` (SHA-2, HMAC, HKDF). Done. |
| `json` | Strict JSON parser and writer: duplicate keys rejected, depth and size limits, no NaN, canonical output. |
| `crypto` | `KeyRing` (kid, active signer, verify-only keys, rotation), `SigningKey` for RS256/ES256/EdDSA, JWK import/export, `SecretBox` AES-256-GCM, PEM parsing. |
| `jose` | `Jws` sign/verify compact, `Jwt` claims with validation (iss, aud, exp, nbf, iat, leeway), `Jwks`, `Jwe` (dir + A256GCM, ECDH-ES+A256KW optional). Rejects `none`, alg mismatch, `crit`, `jku`/`x5u`/`jwk` headers. |
| `password` | `PasswordHasher` (D2), `PasswordPolicy`, breached-password bloom filter loader. |
| `mfa` | `Totp`, `Hotp` (RFC 6238/4226 vectors), `RecoveryCodes`, `MfaPolicy` (step-up, remembered device). |
| `otp` | Email/SMS codes, magic links, `Transport`, single-use, TTL, attempt cap. |
| `webauthn` | `Cbor` strict decoder, `Cose` keys, registration and assertion verification, attestation `none`/`packed`/`fido-u2f`/`apple`/`android-key` (formats parsed; trust anchors optional), sign counter clone detection, RP-ID and origin binding, user verification policy. |
| `session` | Opaque sessions (absolute and idle timeout, device binding), refresh token families, CSRF tokens. |
| `tokens` | Tenant API tokens `sso_pk_...` / `sso_sk_...` with CRC, PBKDF2 over HMAC(pepper) at rest, scopes, rotation grace, revocation, dummy hash for unknown ids. |
| `oidc` | Provider: authorize (code + PKCE S256 only), token (authorization_code, refresh_token, client_credentials, device_code), userinfo, discovery, JWKS, end_session, back-channel logout token, consent records, exact redirect_uri match. |
| `oauth` | Relying party for social login: Google, Apple, Microsoft, GitHub, Facebook, generic OIDC. state + nonce + PKCE, JWKS fetch with SSRF guard and cache. |
| `saml` | `Xml` strict parser, `C14n` exclusive, `XmlDsig`, IdP (AuthnRequest parse, Response + Assertion signed), SP verify, replay cache. |
| `scim` | SCIM 2.0 Users/Groups resources, filter parser (subset), PATCH. |
| `authz` | Roles, permissions, groups, policy evaluation. |
| `risk` | Adaptive auth: new device, new IP range, impossible travel input, failure velocity; returns allow / step-up / deny. |
| `ratelimit` | Token bucket and lockout with the anti-DoS rule (lockout slows, never hard-blocks a correct second factor from a known device). |
| `audit` | Hash-chained, HMAC-signed audit events, export and verify. |
| `store` | Interfaces for every persisted entity plus `memory` implementations. |

## 3. Conventions (every agent)

- Java 17, `--release 17`, no dependency outside the JDK in `naqqa-sso` (enforcer fails the build otherwise).
- No code comments (repo style). No em dash anywhere. No `System.out`.
- Throw `SsoException` (in `com.naqqa.sso`) with a stable `code` string; never include secret or key material in messages.
- Immutable value types as `record`. Inject `java.time.Clock` everywhere time is used.
- Constant-time compare via `Bytes.constantTimeEquals`. Random via `Bytes.random`.
- Tests: JUnit 5 under `src/test/java`, each test method has `@DisplayName("SSO-T-<pkg>-<nnn> ...")`. Include RFC vectors and attack cases from the threat model.
- Build: `JAVA_HOME=~/.local/jdk/jdk-17.0.20.1+1 ./mvnw -q -B test` from `sso/`. Targeted: `-Dtest=ClassName`.
- Report progress to the live page: `node /home/alex/naqqa/sso-progress/status.mjs <cmd> ...` (see that file for commands: feed, phase, agent, finding, fix, tests, auth).

## 4. Auth methods matrix (all configurable per app)

Password; magic link; email OTP; SMS OTP (weakest, documented); TOTP; HOTP; recovery codes; passkeys / WebAuthn as first factor and as 2FA, including fingerprint and face via platform authenticators; security keys (roaming FIDO2); push approve via transport; social login (Google, Apple, Microsoft, GitHub, Facebook, generic OIDC); enterprise OIDC; SAML 2.0 IdP and SP; LDAP connector interface; API keys; OAuth client credentials; device code; SSO session across apps; step-up and adaptive risk auth; account linking (verified email only); support impersonation with audit.

Defaults with `naqqa.sso.enabled=true` (F-CONF-16): password, TOTP and recovery codes are on (each can be switched off); MFA is required for every account holding an administrative role or authority (`naqqa.sso.mfa.required-for-admin=true`, `admin-roles`, `admin-authorities`) besides `required-roles` and `required-for-all`. Such an account without an enrolled factor (TOTP, HOTP, passkey or security key) signs in to a restricted session that can only enroll its first factor (section 7). Possession-weak methods (email OTP, SMS OTP, push, magic link) never satisfy an administrator's MFA (F-CONF-19): with a strong factor only strong factors are offered; without one an enabled email or SMS code is a second-channel proof before the restricted session (push is never offered to administrators), and with no channel available the restricted session follows the first factor alone (documented residual).

## 5. Phases

| Phase | Scope |
|-------|-------|
| P0 | Plan, requirements, live report, toolchain. |
| P1 | `json`, `crypto`, `jose`, `password`, `tokens`. |
| P2 | `mfa`, `otp`, `session`, `ratelimit`, `risk`, `audit`, `authz`. |
| P3 | `webauthn` (passkeys, biometrics). |
| P4 | `oidc` provider. |
| P5 | `oauth` social RP. |
| P6 | `saml`, `scim`. |
| P7 | `auth` module: replace jjwt/Nimbus/oauth2 deps, endpoints for every method, Mongo stores, `naqqa.sso.*` config, legacy BCrypt rehash. |
| P8 | `@naqqa/auth` (naqqa-ui): MFA, passkeys, magic link, OTP, security settings page, OIDC PKCE client, RO/RU/EN. |
| P9 | Consumers: naqqa-os-be/fe and OMY (promotion-server/client) wired, builds green. |
| P-RED | Red/blue loop on local fixtures until a full round finds nothing new. |
| P-FINAL | Every requirement done or residual; full suites green; owner approval; push. |

## 6. Honest limits

- SMS and email need a provider; the tenant supplies it.
- Social login calls the providers' endpoints; that is the protocol, not a library.
- Hardware-backed keys and HSM/KMS are integration points (`KeyStore` interface).
- Attestation trust anchors (FIDO MDS) are optional data the tenant loads; by default attestation is accepted as `none` with policy allowing it.
- Owner item (F-P5): the auth library used to ship `src/main/resources/application.properties` with a Redis Cloud password and a bootstrap admin password inherited from the naqqa-os-be branch. The file is removed and excluded from the jar, but the values remain in git history (history is not rewritten). The owner must rotate the Redis Cloud password and change that admin password wherever it was used, and treat both as compromised.

## 7. HTTP contract (naqqa-auth, base path `${auth.api.base-path:/api/auth}`)

Existing routes stay compatible: `/register`, `/confirm-email`, `/login`, `/refresh`, `/logout`, `/logout/device`, `/forgot-password`, `/reset-password`, `/change-password`. Cookies stay `access_token` / `refresh_token` (HttpOnly, Secure, SameSite=Lax). All JSON. Errors: `{ "code": "<STABLE_CODE>", "message": "<safe text>" }`. Enumeration-safe: request endpoints always answer 202 `{ "status": "sent" }`.

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/methods` | - | `{ password, magicLink, emailOtp, smsOtp, passkey, biometric, securityKey, totp, recovery, push, social: [providers], saml: [idps], samlSp, mfaRequired, passwordPolicy: {minLength,maxLength} }` per app config |
| POST | `/login` | `{ email, password, rememberMe }` | `{ status: "ok", user }` (plus `mfaEnrollmentRequired: true` for a restricted session) or `{ status: "mfa_required", mfaToken, methods: ["totp","webauthn","recovery","email_otp","sms_otp","push"] }` |
| POST | `/mfa/verify` | `{ mfaToken, method, code?, credential?, rememberDevice }` | `{ status: "ok", user }` |
| POST | `/mfa/challenge` | `{ mfaToken, method }` (sends email/sms/push, or returns webauthn options) | `{ status: "sent" }` or `{ publicKey: <PublicKeyCredentialRequestOptionsJSON> }` |
| GET | `/mfa/factors` | - | `[{ id, type, label, createdAt, lastUsedAt }]` |
| POST | `/mfa/totp/enroll` | - | `{ enrollmentId, otpauthUri, secret }` |
| POST | `/mfa/totp/confirm` | `{ enrollmentId, code }` | `{ recoveryCodes: [..] }` |
| POST | `/mfa/hotp/enroll` | - (step-up required, `naqqa.sso.hotp.enabled`, one authenticator app per account) | `{ enrollmentId, otpauthUri, secret, counter }` (otpauth hotp URI with `counter=0`) |
| POST | `/mfa/hotp/confirm` | `{ enrollmentId, code }` (look-ahead window) | `{ recoveryCodes: [..] }` |
| POST | `/mfa/hotp/resync` | `{ code, nextCode }` (two consecutive codes, wider window, own session) | `{ status: "ok" }` |
| DELETE | `/mfa/factors/{id}` | step-up required | 204 |
| POST | `/mfa/recovery/regenerate` | step-up required | `{ recoveryCodes }` |
| POST | `/webauthn/register/options` | `{ mode: "biometric" or "security_key", label }` | `{ publicKey: <PublicKeyCredentialCreationOptionsJSON> }` |
| POST | `/webauthn/register/verify` | `{ credential: <RegistrationResponseJSON>, label }` | `{ id, label }`, plus `recoveryCodes` when it lifted an enrollment restriction |
| POST | `/webauthn/login/options` | `{ email? }` (empty = usernameless passkey) | `{ publicKey: <PublicKeyCredentialRequestOptionsJSON> }` |
| POST | `/webauthn/login/verify` | `{ credential: <AuthenticationResponseJSON> }` | `{ status: "ok", user }` |
| GET | `/webauthn/credentials` | - | `[{ id, label, kind, createdAt, lastUsedAt, backedUp }]` |
| PATCH/DELETE | `/webauthn/credentials/{id}` | `{ label }` | 200 / 204 |
| POST | `/otp/request` | `{ channel: "email" or "sms", destination }` | 202 |
| POST | `/otp/verify` | `{ channel, destination, code }` | `{ status: "ok", user }` or mfa_required |
| POST | `/magic-link/request` | `{ email, redirect }` | 202 |
| POST | `/magic-link/consume` | `{ token }` | `{ status: "ok", user, redirect }` |
| GET | `/push/{id}` | poll | `{ state: "pending" or "approved" or "denied" or "expired" }` |
| POST | `/step-up` | `{ method, code?, credential? }` | `{ status: "ok", acr, authTime }` |
| GET | `/sessions` | - | `[{ id, current, device, ip, createdAt, lastSeenAt, apps }]` |
| DELETE | `/sessions/{id}` | - | 204 |
| POST | `/sessions/revoke-others` | - | 204 |
| GET | `/oauth2/authorization/{provider}` | social start (path kept); `?link=<linkIntent>` finishes a link from the session that started it | 302 |

Facebook (F-E19). Facebook never asserts that an email is verified, so its identity always carries `email_verified=false`. With `create-accounts` on it may create a new account whose email is stored unverified (`email_verified=false` in the id_token, userinfo, SCIM and federation views; link origin `unverified`). It never links to or adopts an existing account by email: a collision answers `LINK_REQUIRES_LOGIN`. An existing account links Facebook only through `POST /api/auth/account/links/facebook/start` (own session, step-up, CSRF) followed by `/oauth2/authorization/facebook?link=<linkIntent>` in the same session; that link needs no provider email and gets origin `user`. The email becomes verified only through a magic link or email OTP sign-in; that verification ends every session, refresh family, OIDC session and grant opened before it (F-CONF-21, `credentials.revoke_all` reason `email_verified`), and after it an `unverified` Facebook link stops signing in (`LINK_REQUIRES_LOGIN`) until it is linked again from a session signed in after the verification. The debug_token endpoint is `naqqa.sso.social.registrations.facebook.debug-token-endpoint` (default `https://graph.facebook.com/v19.0/debug_token`, F-E18; https only, `127.0.0.1` in test mode).

MFA enrollment restriction (F-CONF-16). An account that needs MFA (`mfa.required-for-admin`, `required-roles`, `required-for-all`) and has no enrolled factor gets, from any sign-in (password, LDAP, social, SAML SP, magic link, OTP, `/mfa/verify`, remembered device), `{ status: "ok", user, mfaEnrollmentRequired: true }` and a session restricted server side: its access tokens carry `mfa_enroll: true` and every request authenticated by it (cookie or Bearer) answers 403 `{ code: "MFA_ENROLLMENT_REQUIRED", message }` except `GET /methods`, `GET /mfa/factors`, `POST /mfa/totp/enroll`, `POST /mfa/totp/confirm`, `POST /mfa/hotp/enroll`, `POST /mfa/hotp/confirm`, `POST /webauthn/register/options`, `POST /webauthn/register/verify`, `GET /webauthn/credentials`, `POST /mfa/recovery/regenerate` (once a factor exists), `POST /step-up`, `POST /logout`, `POST /refresh`, `GET /sessions`, public metadata and `naqqa.sso.mfa.enrollment-allowed-routes` (default `GET /api/profile/me`). OIDC authorize, consent, resume and device approval answer the same 403 for a restricted `sso_session`; SAML IdP, impersonation, admin APIs and API token issuance are refused. Social and SAML SP success redirects add `#mfaEnrollment=1`. A refresh keeps the restriction, restricts a session whose account became administrative without a factor, and refuses a sessionless legacy refresh of such an account. The client the caller names never lifts it (F-CONF-20): a client whose policy enables no enrollable factor still gets the restricted session when the global policy enables one, and the factor is then enrolled through a client that enables it. The first confirmed factor (TOTP or HOTP confirm, WebAuthn registration) lifts the restriction in place, adds the factor to the amr and returns recovery codes. While the session is restricted the JSON answer of the app profile routes (`naqqa.sso.mfa.enrollment-profile-routes`, default `GET /api/profile/me`, served through the app chain with `AccessTokenAuthenticationFilter`) also carries `mfaEnrollmentRequired: true` (`MfaEnrollmentProfileAdvice`), so an app that loads the profile right after a federated return, which has no login body, detects the restriction.

OIDC provider (SSO across naqqa apps): `/.well-known/openid-configuration`, `/oauth2/authorize`, `/oauth2/par`, `/oauth2/register` (off by default), `/oauth2/device` (user code entry page), `/oauth2/token`, `/oauth2/userinfo`, `/oauth2/jwks`, `/oauth2/revoke`, `/oauth2/introspect`, `/oauth2/device_authorization`, `/oauth2/logout`. SAML IdP: `/saml2/idp/metadata`, `/saml2/idp/sso`, `/saml2/idp/slo`. SCIM: `/scim/v2/Users`, `/scim/v2/Groups`.

SAML SP (enterprise login, naqqa is the service provider and the tenant's IdP authenticates; F-E11). Active only with `naqqa.sso.enabled=true` and `naqqa.sso.saml.sp.enabled=true` (default false), per client `methods.samlSp` (property `naqqa.sso.clients.<id>.saml-sp`, inherit by default), per tenant the IdP `enabled` flag (default false). `/methods` adds `samlSp: true|false`.

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/saml2/sp/{tenant}/login?relay=/path` | - (`X-Client-Id` or `client_id` selects the client) | 302 to the IdP (HTTP-Redirect, AuthnRequest signed when `naqqa.sso.saml.sp.private-key` is set). Sets `sso_saml_sp` (HttpOnly, Secure, SameSite=None, Path=`/saml2/sp/{tenant}/acs`, lifetime `saml.sp.request-ttl`, 10m) bound to a single-use flow holding the request id, relay and client. 400 `RELAY_INVALID` unless `relay` is a relative path starting with one `/`; 404 `SAML_IDP_UNKNOWN`; 403 `TENANT_SUSPENDED`; 404 `METHOD_DISABLED` |
| POST | `/saml2/sp/{tenant}/acs` | form `SAMLResponse`, `RelayState?` (HTTP-POST binding) | 302 to `saml.sp.success-url` (default `frontend.url`) + relay, or to `saml.sp.failure-url` (default `frontend.url/login`) `#mfaToken=..&methods=..&redirect=..` when a local second factor is needed, or `?error=<CODE>` (`SAML_FAILED`, `DOMAIN_NOT_ALLOWED`, `EMAIL_MISSING`, `SIGNUP_DISABLED`, `LINK_REQUIRES_LOGIN`, `ACCOUNT_BLOCKED`, `SAML_NAMEID_FORMAT`, `METHOD_DISABLED`) |
| POST | `/api/auth/account/links/saml/{tenant}/start` | - (own session, step-up with the account's strongest factor class, CSRF) | `{ linkIntent, provider: "saml:<tenant>", url }` where `url` is `<issuer>/saml2/sp/{tenant}/login?link=<linkIntent>` (add `&relay=/path`); 409 `IDENTITY_ALREADY_LINKED` when the account already has an identity of this IdP, 403 `LINK_NOT_ALLOWED` for administrative or directory-managed accounts, 401 `STEP_UP_REQUIRED` (F-SP-5) |
| GET | `/saml2/sp/{tenant}/metadata` | - | SP metadata (`application/samlmetadata+xml`): entity id `<issuer>/saml2/sp/{tenant}/metadata`, ACS `<issuer>/saml2/sp/{tenant}/acs`, signing and encryption keys when configured |

ACS rules: the response must answer the request id of the flow bound to this browser (`InResponseTo` on the response when present and on the bearer `SubjectConfirmationData`); the request id is consumed once (`MongoSamlRequestStore`, TTL) and the assertion id is recorded in the Mongo replay cache (`sso_saml_replay`, TTL); a response without a flow is unsolicited and is refused unless the IdP sets `allowUnsolicited` (then `RelayState` is used only when it is a relative path). Signature, issuer, audience, recipient, destination, time window, one assertion per document and the configured certificates (several, for rotation) are checked by core `SamlSp`. The ACS POST is the only cross-site route: it is outside CORS (its own chain, like the Apple callback) and outside `SsoCsrfFilter`; it is protected by the single-use request id plus the binding cookie. Rate limit `naqqa.sso.rate-limits.saml-sp` (30 per minute per IP) on login and ACS; the tenant `authRequestsPerMinute` quota counts each login start.

Link flow (F-SP-5): the security settings call the start route, then navigate to `url`. The login route with `link` needs the session that started the intent (401 `UNAUTHORIZED` without one, 403 `LINK_SESSION_MISMATCH` for another session or user, 400 `LINK_INTENT_INVALID` when used or expired, 400 `LINK_PROVIDER_MISMATCH` for another tenant); the intent is consumed there and the flow (bound to this browser by `sso_saml_sp`) carries the user and session. At the ACS the session must still be live and the account enabled; the NameID rules and `allowedDomains` apply to the asserted email (which may differ from the account email); the account must not be administrative or directory managed and must not already hold an identity of this IdP; the NameID must not be linked to another account (`IDENTITY_ALREADY_LINKED`). The link is stored with origin `user`, audited `saml.sp.link`, the user gets the `account_linked` security notice, and the browser returns to `success-url + relay` with `linked=saml:<tenant>` (failures go to `failure-url?error=<CODE>`). No session is issued by a link flow. Later sign-ins through such a link keep the local MFA rules and do not re-sync roles. Linking by email match alone never happens for an account with TOTP or a passkey.

Identity: an existing link (`sso_linked_identities`, provider `saml:<tenant>`, subject = NameID) signs in. Otherwise the email (attribute `attributes.email`, or the NameID when its format is emailAddress) must be in `allowedDomains`. An existing local account is linked only when the domain is in `authoritativeDomains`, the account is enabled (`ACCOUNT_BLOCKED` otherwise, F-A13-AUTH-7), the local email is verified, the account has no TOTP or passkey (`AssuranceEngine.mayAutoLink`), holds no administrative authority, is not directory managed and has no other link to this IdP; otherwise `LINK_REQUIRES_LOGIN`. Without a local account the user is created only when `jitProvisioning` is on, with `emailVerified` true only for an authoritative domain and the roles of `groupRoles` (or `defaultRole`); roles are re-synced on later sign-ins when a mapping exists. Administrative roles are never granted. A link created by JIT with an unverified email stops signing in once the address is verified by another channel (F-A12-AUTH-1), an account holding an administrative authority never signs in through a tenant IdP (F-A12-AUTH-3), nor does an account the LDAP directory manages (F-A13-AUTH-2); all answer `LINK_REQUIRES_LOGIN`. A sign-in through an existing link also needs the asserted email in `allowedDomains` (`DOMAIN_NOT_ALLOWED` otherwise), so links never outlive the platform grant, and removing the IdP, or its last allowed domain and the assurance trust, removes its links (F-A13-AUTH-1, F-A13-AUTH-6). Adoption notifies the owner (`account_linked`). Federation across social, SAML SP and LDAP: `docs/security/federation.md`. The session amr is `fed` (strength 1): the account's MFA policy applies as for social login. Only an IdP with `trustedForAssurance=true` whose AuthnContext is in `mfaAuthnContexts` (default `https://refeds.org/profile/mfa`) yields amr `fed mfa` (strength 3, acr 2). Transient NameIDs are refused.

## 8. End-to-end verification (P-E2E, before owner approval)

1. Full unit suites: `naqqa-sso` (`mvn test`), `naqqa-auth`, naqqa-os-be, promotion-server, `ng build` + tests for naqqa-ui, naqqa-os-fe, promotion-client. Zero failures, zero-dep enforcer green.
2. Real stack locally: naqqa-os-be with naqqa-auth 3.0.0 + naqqa-os-fe with re-vendored `@naqqa/auth`; OMY with `naqqa.sso.enabled=true` and once with it false (current behavior unchanged).
3. Browser E2E (Playwright as dev-only tool, Chromium, software GL) for every method in section 4: register, email confirm, password login, logout, refresh rotation, TOTP enroll + login + replay rejected, recovery code single use, email/SMS OTP via DevLogTransport, magic link single use, passkey biometric register + usernameless login through the CDP virtual authenticator (`isUserVerified=true`) and UV-missing rejected, security key, push approve, step-up, session list + remote revoke, SSO: login in app A then app B without credentials via the OIDC provider, RP and back-channel logout, social login against a local mock IdP, SAML IdP to a local SP, device code, client credentials, SCIM create/patch/delete, API token auth.
4. Regression: every pre-existing endpoint and screen still works for existing users (legacy BCrypt users log in and get rehashed).
5. Requirements sweep: every R row done or documented residual; every auth matrix row done; no open finding.
6. Then the report is marked ready for owner approval; push only after approval.

### 7.1 Contract details fixed by the frontend (P8)
- `/mfa/challenge` with method push returns `{ status: "sent", pushId, number }`; client polls `/push/{pushId}` and displays `number` for number matching.
- Step-up with passkey: `/webauthn/login/options { email }` then `/step-up { method: "webauthn", credential }`. Step-up by email: `/otp/request { channel: "email", destination }` then `/step-up { method: "email_otp", code }`.
- A request needing fresh authentication returns `401 { code: "STEP_UP_REQUIRED" }`.
- Step-up must use a factor at least as strong as the strongest one the user has enrolled (passkey > TOTP, recovery code, push > email or SMS OTP > password); a weaker method answers `400 { code: "STEP_UP_FACTOR_TOO_WEAK" }` and a session whose latest authentication used a weaker factor gets `STEP_UP_REQUIRED` even when fresh.
- HOTP is an authenticator app factor of the same strength class as TOTP (AssuranceEngine treats both as possession-strong). An account has either TOTP or HOTP (409 `TOTP_ALREADY_ENROLLED` otherwise). Sign-in, `/mfa/verify`, `/mfa/challenge` and `/step-up` keep the method name `totp` for a HOTP account (`hotp` is accepted as an alias); `/mfa/factors` lists it as `{ id: "hotp", type: "hotp" }` and `DELETE /mfa/factors/hotp` removes it; `/methods` adds `hotp`. The counter is stored per account and advanced atomically; a code at or behind the counter is refused.
- Magic link `redirect` must be a relative path starting with a single `/`; the server rejects anything else.
- MFA enrollment restriction (F-CONF-16): `/login`, `/mfa/verify`, `/magic-link/consume` and the OTP sign-in answer `{ status: "ok", user, mfaEnrollmentRequired: true }`; federated returns carry `#mfaEnrollment=1` and the app profile route (`mfa.enrollment-profile-routes`, default `GET /api/profile/me`) answers with `mfaEnrollmentRequired: true` while restricted; any other route answers `403 { code: "MFA_ENROLLMENT_REQUIRED" }`; TOTP/HOTP confirm and the first WebAuthn registration return `recoveryCodes` and lift the restriction in place.
- Administrators and weak methods (F-CONF-19): an administrator without a strong factor may first get `{ status: "mfa_required", methods: ["email_otp" | "sms_otp" ...] }` (never `push`); `/mfa/verify` with that code answers `{ status: "ok", user, mfaEnrollmentRequired: true }`, so the frontend continues to the enrollment screen. An administrator with TOTP, HOTP or WebAuthn gets only `totp`, `webauthn`, `recovery` (or `password` after a security key without user verification); `email_otp`, `sms_otp` and `push` answer `400 { code: "METHOD_NOT_ALLOWED" }` at `/mfa/challenge` and `/mfa/verify`.

### 7.2 Admin API (P10, naqqa-auth, base path `/api/sso/admin`, only when `naqqa.sso.enabled=true`)

Common rules: JSON in and out, errors `{ code, message }`. 401 `UNAUTHORIZED` without a session, 403 `FORBIDDEN` without the listed authority, 401 `STEP_UP_REQUIRED` when a mutation (marked S) runs on an authentication older than `naqqa.sso.mfa.step-up-max-age`, 403 `IMPERSONATION_FORBIDDEN` while impersonating, 403 `CSRF_INVALID` for cookie sessions without `X-XSRF-TOKEN`. API tokens (`sso_sk`) never reach admin mutations (403 `INTERACTIVE_LOGIN_REQUIRED`). Every mutation writes an audit event (`admin.<area>.<action>`). Tenant scope: a holder of `SSO_ADMIN_TENANTS` is a platform operator and sees every tenant; anyone else sees only tenants whose `admins` list holds their user id. A tenant or a resource of another tenant answers 404 `NOT_FOUND` (no cross-tenant oracle). Users and signing keys are platform scope (users live in tenant `default`; acting on them needs access to `default`). Secrets (client secret, API token) appear exactly once, in the create or rotate response, field `clientSecret` / `token`; lists and views never contain secrets, hashes or private keys. Unlimited quota values are `null`.

| Method | Path | Authority | S | Body | Response |
|--------|------|-----------|---|------|----------|
| GET | `/tenants` | `SSO_ADMIN_TENANTS` or `SSO_ADMIN_CLIENTS` or `SSO_ADMIN_TOKENS` | | - | `[Tenant]` visible to the caller |
| POST | `/tenants` | `SSO_ADMIN_TENANTS` | S | `{ id, name, plan: "FREE" or "PRO" or "ENTERPRISE", admins?: [userId] }` (`id` `[a-z0-9][a-z0-9-]{1,62}`) | 201 `Tenant`; 409 `TENANT_EXISTS` |
| PATCH | `/tenants/{tenantId}` | `SSO_ADMIN_TENANTS` | S | `{ name?, plan?, status?: "active" or "suspended", admins?: [userId] }` | `Tenant` |
| GET | `/tenants/{tenantId}/usage` | as GET `/tenants` | | - | `{ tenantId, plan, month, monthlyActiveUsers, mauSaturated, authEvents, tokensIssued, authRequestsThisMinute, apiTokens, oidcClients, quotas }` |
| GET | `/tenants/{tenantId}/clients` | `SSO_ADMIN_CLIENTS` | | - | `[Client]` (configured clients appear with `managed: false`, read-only except settings) |
| POST | `/tenants/{tenantId}/clients` | `SSO_ADMIN_CLIENTS` | S | `ClientInput` | 201 `Client` + `clientSecret` (confidential only, shown once); 409 `QUOTA_EXCEEDED`, 501 `NOT_CONFIGURED` when the OIDC provider is off |
| GET | `/tenants/{tenantId}/clients/{clientId}` | `SSO_ADMIN_CLIENTS` | | - | `Client` |
| PATCH | `/tenants/{tenantId}/clients/{clientId}` | `SSO_ADMIN_CLIENTS` | S | partial `ClientInput` (`type` and `clientId` immutable) | `Client`; 409 `CLIENT_CONFIGURED` when an OIDC field of a property-configured client is changed |
| DELETE | `/tenants/{tenantId}/clients/{clientId}` | `SSO_ADMIN_CLIENTS` | S | - | 204 (managed clients only; also revokes its publishable keys) |
| POST | `/tenants/{tenantId}/clients/{clientId}/secret` | `SSO_ADMIN_CLIENTS` | S | - | `{ clientId, clientSecret }` shown once; the old secret stops at once |
| GET | `/keys` | `SSO_ADMIN_KEYS` | | - | `{ rings: [{ ring: "access" or "oidc" or "audit", keys: [{ kid, alg, status: "active" or "retiring" or "retired", notAfter, createdAt }] }] }` public data only |
| POST | `/keys/{ring}/rotate` | `SSO_ADMIN_KEYS` | S | `{ graceSeconds? }` default 86400, max 604800; `audit` keeps old keys verify-only for the whole retention | `{ ring, kid, previousKid, previousNotAfter }`; 501 `NOT_CONFIGURED` for `oidc` when the provider is off |
| GET | `/tenants/{tenantId}/api-tokens` | `SSO_ADMIN_TOKENS` | | - | `[ApiToken]` |
| POST | `/tenants/{tenantId}/api-tokens` | `SSO_ADMIN_TOKENS` | S | `{ kind: "secret" or "publishable", env: "live" or "test", name, scopes?: [..], allowlist?: [CIDR], clientId? }` (`clientId` required for publishable, must belong to the tenant) | 201 `ApiToken` + `token` shown once; 409 `QUOTA_EXCEEDED` |
| POST | `/tenants/{tenantId}/api-tokens/{id}/rotate` | `SSO_ADMIN_TOKENS` | S | `{ graceSeconds? }` default `naqqa.sso.api-tokens.rotation-grace` | `ApiToken` + `token` shown once; old secret valid until `graceUntil` |
| DELETE | `/tenants/{tenantId}/api-tokens/{id}` | `SSO_ADMIN_TOKENS` | S | - | 204, immediate |
| GET | `/users?q=&limit=` | `SSO_ADMIN_USERS` | | `q` email or name fragment (min 2 chars), `limit` max 50 | `[{ id, email, fullName, enabled, blocked }]` |
| GET | `/users/{userId}` | `SSO_ADMIN_USERS` | | - | `{ id, email, fullName, enabled, blocked, factors: [{ id, type, label, createdAt, lastUsedAt }], sessions: [{ id, device, ip, createdAt, lastSeenAt, apps, impersonatedBy? }] }` |
| POST | `/users/{userId}/factors/reset` | `SSO_ADMIN_USERS` | S | `{ reason }` | 204; removes TOTP, passkeys, security keys, recovery codes, remembered devices; revokes every session, refresh family and OIDC grant (forces re-enrollment) |
| DELETE | `/users/{userId}/sessions` | `SSO_ADMIN_USERS` | S | - | 204, every session and grant revoked |
| DELETE | `/users/{userId}/sessions/{sessionId}` | `SSO_ADMIN_USERS` | S | - | 204 |
| POST | `/users/{userId}/unlock` | `SSO_ADMIN_USERS` | S | - | 204, clears login, MFA, OTP and step-up throttles of the account |
| DELETE | `/users/{userId}` | `SSO_ADMIN_USERS` | S | - | `{ reference }` (AccountErasureService, mode admin) |
| POST | `/impersonate` | `SSO_IMPERSONATE` | S | `{ userId, reason }` | as `/api/auth/admin/impersonate` |
| DELETE | `/impersonate/{sessionId}` | `SSO_IMPERSONATE` | | - | 204 |
| GET | `/tenants/{tenantId}/saml-idp` | `SSO_ADMIN_CLIENTS` | | - | `SamlIdp` (`configured: false` and defaults when none); only when `naqqa.sso.saml.sp.enabled=true` |
| PUT | `/tenants/{tenantId}/saml-idp` | `SSO_ADMIN_CLIENTS` | S | `SamlIdpInput` (absent fields keep their value) | `SamlIdp`; revoking the last allowed domain and the assurance trust removes every `saml:<tenant>` link (F-A13-AUTH-6); 403 `PLATFORM_ONLY`, 409 `DOMAIN_CLAIMED`, 409 `IDP_CONFIGURED` (property-configured IdP), 400 validation codes below |
| DELETE | `/tenants/{tenantId}/saml-idp` | `SSO_ADMIN_CLIENTS` | S | - | 204, every `saml:<tenant>` link removed; 404 `NOT_FOUND`; 403 `PLATFORM_ONLY` when the IdP has allowed domains or assurance trust and the caller is not a platform operator (F-A13-AUTH-1) |
| GET | `/audit/verify` | `SSO_AUDIT` | | `?full=` | `{ ok, reason, badSeq, checkedFrom, checkedTo, checkpoint }` |
| GET | `/audit/export` | `SSO_AUDIT_EXPORT` | | `?from=&limit=` | NDJSON + trailer |

User targets holding any `SSO_ADMIN_*`, `SSO_AUDIT*` or `SSO_IMPERSONATE` authority can be changed only by a platform operator (`SSO_ADMIN_TENANTS`), otherwise 403 `TARGET_PROTECTED`.

`Tenant` = `{ id, name, plan, status, admins: [userId], quotas: { monthlyActiveUsers, authRequestsPerMinute, apiTokens, oidcClients, samlServiceProviders, scimOpsPerDay, features: [..] }, createdAt, updatedAt }`.

`ClientInput` = `{ clientId?, name, type: "public" or "confidential", redirectUris: [..], postLogoutRedirectUris?: [..], backchannelLogoutUri?, grantTypes?: ["authorization_code","refresh_token","client_credentials","urn:ietf:params:oauth:grant-type:device_code"], scopes?: [..], firstParty?, enabled?, methods?: { password, magicLink, emailOtp, smsOtp, totp, recovery, passkey, biometric, securityKey, push, saml, samlSp } (each true, false or null = inherit), socialProviders?: [..] or null, allowedOrigins?: [origin], branding?: { name, logoUrl } }`. Validation codes (400): `REDIRECT_URI_INVALID` (exact string, `https`, or `http` only for `localhost`, `127.0.0.1`, `[::1]`; no wildcard, fragment, userinfo; max 20), `POST_LOGOUT_URI_INVALID` (same rules), `BACKCHANNEL_URI_INVALID` (https only), `BACKCHANNEL_URI_BLOCKED` (resolves to loopback, private, link-local or other blocked range, core `SafeHttp` rules), `ORIGIN_INVALID` (`scheme://host[:port]` only, https or http on localhost, no `*`), `LOGO_URL_INVALID` (https only), `GRANT_TYPE_INVALID`, `SCOPE_INVALID`, `CLIENT_ID_INVALID`, `CLIENT_EXISTS` (409).

`Client` = `{ clientId, tenantId, name, type, authMethod, managed, redirectUris, postLogoutRedirectUris, backchannelLogoutUri, grantTypes, scopes, firstParty, pkceRequired: true, enabled, methods (effective, same shape as `/methods`), methodOverrides, socialProviders, allowedOrigins, branding: { name, logoUrl } }`.

`SamlIdpInput` = `{ enabled?, name?, entityId?, ssoUrl?, signingCertificates?: [PEM] (1 to 5, RSA 2048+ or EC; several for rotation), nameIdFormat?: persistent or emailAddress or unspecified, attributes?: { email, name, groups }, allowedDomains?: [domain], authoritativeDomains?: [domain] (subset of allowed, unique across IdPs), jitProvisioning?, groupRoles?: { group: role }, defaultRole?, trustedForAssurance? (default false), mfaAuthnContexts?: [classRef], allowUnsolicited? (default false), requireSignedAssertion? (default true), requireSignedResponse? (default false), requireEncryptedAssertion? (needs the SP key) }`. Platform-only fields (a caller without `SSO_ADMIN_TENANTS` gets 403 `PLATFORM_ONLY` when the value changes): `allowedDomains`, `authoritativeDomains`, `jitProvisioning`, `groupRoles`, `defaultRole`, `trustedForAssurance`, `mfaAuthnContexts`; and, once the stored IdP has an allowed domain or assurance trust, `entityId`, `signingCertificates`, `attributes.email` and `nameIdFormat` (F-A12-AUTH-2: certificate rotation of a granted IdP goes through a platform operator). Validation codes (400): `ENTITY_ID_INVALID`, `SSO_URL_INVALID` (https, http only for localhost), `CERTIFICATE_INVALID`, `NAMEID_FORMAT_INVALID` (transient refused), `ATTRIBUTE_INVALID`, `DOMAIN_INVALID`, `ROLE_INVALID`, `ROLE_PROTECTED` (role carries an administrative authority), `AUTHN_CONTEXT_INVALID`, `SIGNATURE_POLICY_INVALID`, `ENCRYPTION_UNAVAILABLE`, `IDP_INCOMPLETE` (enabled without entity id, SSO URL and certificate). Audit `admin.saml_idp.update` (also `denied`), `admin.saml_idp.delete`.

`SamlIdp` = `{ tenantId, configured, managed, enabled, name, entityId, ssoUrl, signingCertificates: [{ pem, subject, notAfter, sha256 }], nameIdFormat, attributes: { email, name, groups }, allowedDomains, authoritativeDomains, jitProvisioning, groupRoles, defaultRole, trustedForAssurance, mfaAuthnContexts, allowUnsolicited, requireSignedAssertion, requireSignedResponse, requireEncryptedAssertion, updatedAt, nameIdFormats, sp: { entityId, acsUrl, metadataUrl, loginUrl, signsRequests, decrypts } }`. Operator-configured IdPs (`naqqa.sso.saml.sp.tenants.<tenant>.*`, same fields in kebab case) appear with `managed: false` and are read-only.

`ApiToken` = `{ id, prefix, tenantId, clientId, name, kind: "sk" or "pk", env, scopes, allowlist, createdAt, rotatedAt, graceUntil, revokedAt, lastUsedAt }`; `prefix` is `sso_<kind>_<env>_<id>`, the non-secret part.

Publishable keys (F-K1): browser calls to the public endpoints (`GET /api/auth/methods`, login and login-option endpoints under `/api/auth`) may send `X-SSO-Key: sso_pk_...`. The key selects tenant and client (it replaces `X-Client-Id`; a different `X-Client-Id` is 400 `CLIENT_MISMATCH`). The request `Origin` must equal one of the client's `allowedOrigins` (scheme, host and port), else 403 `PUBLISHABLE_KEY_ORIGIN_DENIED`; an unknown, revoked or `sso_sk` value is 401 `PUBLISHABLE_KEY_INVALID`; a suspended tenant is 403 `TENANT_SUSPENDED`. A publishable key never authenticates anyone. CORS on `/api/auth/**` and `/api/sso/**`: an `Origin` in `naqqa.sso.allowed-origins` or any client's `allowedOrigins` gets `Access-Control-Allow-Origin: <that origin>`, `Access-Control-Allow-Credentials: true`, `Vary: Origin`; preflight answers 204 with methods `GET, POST, PUT, PATCH, DELETE` and headers `Content-Type, Authorization, X-Client-Id, X-SSO-Key, X-XSRF-TOKEN, X-Device-Id` plus the header names the preflight asks for (plain tokens only). Never `*`. Other origins get no CORS headers from naqqa-auth.

## 9. Remaining handoff items mapped to this codebase

| Handoff | Here | Decision |
|---------|------|----------|
| P10 admin console + account UI | Account UI done in `@naqqa/auth` (security settings). Admin: `naqqa-auth` admin API (`/api/sso/admin/*`: tenants, clients, redirect URIs, enabled methods per client, signing key rotation, API tokens issue/rotate/revoke, users' factors reset, sessions revoke, audit export and verify) + an "SSO" section in naqqa-os-fe built from `@naqqa/auth` admin components. | Phase P10. |
| P11 adapters | Angular (`@naqqa/auth`) and Spring (`naqqa-auth`) are the stacks naqqa apps use. Tokens are standard RS256 JWT + JWKS, so any stack verifies them. Shared cross-language vectors in `sso/vectors/*.json` (JWT/JWKS, TOTP, PKCE, WebAuthn, API token format) so future adapters prove equality. | Other-language middleware not built: no naqqa app uses those stacks (residual, documented). |
| P12 false positives, performance, L7 DoS | Performance budgets measured in P-E2E (login p95, PBKDF2 cost, token verify throughput), throttle false-positive tests (NAT/corporate proxy many users one IP, screen reader slow typing, slow devices). | Phase P12 inside P-E2E. |
| P13 productization | Tenant plans/quotas hooks (`QuotaPolicy` interface), usage metering events, SBOM (CycloneDX JSON written by our own script from the pom, zero deps), onboarding docs per platform. | Phase P13. Signed releases: owner item (keys). |
| P14 legal | `sso/docs/legal/`: GDPR + Moldova Law 133/2011 notes, DPA template, ToS, privacy copy RO/RU/EN, retention, erasure, audit admissibility. | Phase P14. Counsel review: owner item. |

P14 docs: sso/docs/legal/

## 10. Final security campaign (P-ATTACK, owner request 2026-10-15)

After P7c, the GDPR fixes, P9 backend and P10: a full security assessment of every layer (core, naqqa-auth Spring layer, @naqqa/auth and the consumer apps), white-box review plus testing against the locally running stack only (127.0.0.1, own fixtures, no external systems). Rounds repeat until one complete round over all layers finds nothing new. Each confirmed issue gets a finding, a failing regression test, a fix, and a re-test.

## 11. Residuals (documented exceptions)

| Item | Decision |
|------|----------|
| F-B4 credential changes vs refresh families | Factor or passkey removal, unlinking a social or SAML identity, password reset, email verification and SCIM email change, LDAP adoption, removal of a tenant IdP's links, SCIM deactivation/deletion and account erasure revoke every OIDC grant and refresh family of the user, `offline_access` included, plus their access tokens (identity control matrix, `docs/security/identity-lifecycle.md` section 6). One exception is kept: on factor or passkey removal and on unlink the naqqa-auth session that performed the change (fresh step-up) keeps its own refresh family so the user is not logged out of the page they are on. OIDC grants of that same browser are revoked too; relying parties re-authorize silently through the still valid SSO session. |
| F-P7 migration from auth 2.0.0 refresh tokens | A 2.0 refresh token (jjwt HS256) is accepted only as an exact match of a row still stored in `refresh_tokens`. The row is claimed with an atomic find-and-remove, compared in constant time (SHA-256 of both values), checked for expiry and for an existing enabled user, and exchanged once for a new opaque refresh family plus a new access token. The HS256 signature is never verified or trusted, and 2.0 access tokens are not accepted (they expired after 15 minutes). Migration window: legacy rows age out naturally within 30 days of their issue (their `expiryDate`), after which every 2.0 session has migrated or must sign in again. |

Scope decision (2026-10-15): the clean-round criterion for OMY applies to the SSO, authentication, session and account-management surface. Business-logic issues found along the way (promo codes, auctions, cart, counters) are fixed when found but do not block the campaign end.
