# naqqa-auth HTTP route inventory (P-ATTACK round 5, re-verified in rounds 8 and 9)

Source: every `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping` and `@DeleteMapping` in `auth/src/main/java`, the two security filter chains in `AuthSecurityConfig`, the servlet filters in `SsoFilterConfiguration` and `StepUpInterceptor`. Base path `B` = `${auth.api.base-path:/api/auth}`, admin base `A` = `/api/sso/admin`. Reviewed state: branch `feat/naqqa-sso`, after the fixes of round 5 (every row re-verified against the code: step-up annotation, authority, audit call and scope check per handler). Since round 5 the rows below are also enforced by architecture tests (see Structural checks).

## Cross-cutting layers

| Layer | Scope | Behaviour |
|-------|-------|-----------|
| Chain 1 (`@Order(1)`) | `/api/auth/**`, `/api/custom-auth/**`, `/api/sso/**`, `B/**`, legacy `/api/admin/users*`, `/api/roles*`, `/api/sub-roles*` | stateless, `permitAll` at URL level (authorization by `@PreAuthorize` and controller checks), JSON 401/403, CSP `default-src 'none'`, `frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, Spring Security default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`. Filters: `ApiTokenAuthenticationFilter` (Bearer `sso_sk_*`, tenant `authRequestsPerMinute` hard limit, 429 `QUOTA_EXCEEDED`, F-A3-8), `AccessTokenAuthenticationFilter` (Bearer or `access_token` cookie, `naqqa-auth` issuer, `token_use=access`, session guard), `SsoCsrfFilter` |
| Chain 0 (`@Order(0)`) | POST `/login/oauth2/code/*` with `Origin: https://appleid.apple.com`; POST `/saml2/sp/{tenant}/acs` (exact path, any origin) | same headers and token filters, CORS disabled, no `SsoCsrfFilter`: the IdP posts cross-site by design; protected by the single-use flow bound to the `SameSite=None` binding cookie (F-E11) |
| Chain 2 (`@Order(2)`) | `/oauth2/**`, `/login/oauth2/code/*`, `/.well-known/**`, `/saml2/**`, `/scim/v2/**` | same headers and token filters, no `SsoCsrfFilter` (protocol endpoints: browser binding cookies, single-use handles, signed SAML, Bearer only for SCIM) |
| `SsoCsrfFilter` | chain 1, cookie-authenticated, non-safe methods | double submit `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header bound to the session; exact exemptions `B/login`, `B/refresh`, `B/logout`, `B/register`, `B/confirm-email`, `B/forgot-password`, `B/reset-password`, `B/change-password` |
| `SsoCorsFilter` | `B/**` and `A/**` with `Origin` | `B/**`: configured origins plus client `allowedOrigins`; `A/**`: configured origins only (F-A1-13); credentialed, never `*` |
| `PublishableKeyFilter` | `B/**` with `X-SSO-Key` | key must be `sso_pk_*`, bound to a client of its tenant, tenant active, `Origin` in the client's `allowedOrigins`, no conflicting `X-Client-Id` |
| `StepUpInterceptor` | handlers with `@RequiresStepUp` (inert when `naqqa.sso.enabled=false`) | 401 `UNAUTHORIZED` anonymous, 403 `INTERACTIVE_LOGIN_REQUIRED` for API tokens, 403 `IMPERSONATION_FORBIDDEN`, 401 `STEP_UP_REQUIRED` when `auth_time` is older than `naqqa.sso.mfa.step-up-max-age` or when the factor of the latest authentication of the session is weaker than the strongest factor class the user has enrolled (passkey > TOTP, recovery, push > email or SMS OTP > password; F-A3-6). `anyFactor = true` would waive the strength check per endpoint; no endpoint uses it |
| Exception handlers | `GlobalExceptionHandler`, `AuthExceptionHandler`, `SsoExceptionHandler` (highest precedence), `AuthoritiesExceptionHandler` (fallback) | stable codes only, generic 500 body, stack trace only in the server log, security exceptions rethrown to the JSON entry point |

Legend: S = `@RequiresStepUp`; CSRF "dbl" = double submit for cookie sessions; "n/a" = not cookie authenticated or safe method; throttle = `LoginThrottle` (per key + IP, delay then challenge); window = `SlidingWindowLimiter` on `MongoRateLimitStore`.

## naqqa-auth core routes (`DefaultAuthController`, delegating to `AuthController`)

| Method | Path | Auth | Authority | S | CSRF | Rate limit | Tenant | Audit | Verdict |
|--------|------|------|-----------|---|------|------------|--------|-------|---------|
| POST | `B/register` | none | - | - | exempt | window per IP and email (F-A2-3) | default | `register.request` (F-A5-AUTH-2) | ok |
| POST | `B/confirm-email` | none | - | - | exempt | 5 attempts per pending record (F-A1-9) | default | `account.register` (F-A4-AUTH-4) | ok |
| POST | `B/login` | none | - | - | exempt | throttle per email + IP, risk engine | client policy via `X-Client-Id`/`X-SSO-Key`, MFA = union with global (F-A2-1); directory-managed accounts never use a local password (F-A5-AUTH-3) | `login.password`, `login.mfa_required`, `login.success` | ok |
| POST | `B/refresh` | refresh cookie | - | - | exempt | rotation, reuse revokes family | - | `token.refresh` (F-CONF-14) | ok; a 2.0 legacy row is exchanged with `auth_time` = its issue time, never fresh (F-A4-AUTH-1) |
| POST | `B/logout` | refresh cookie | - | - | exempt | - | - | `logout` (F-CONF-14) | ok |
| POST | `B/logout/device` | access token, not impersonated (F-A4-AUTH-4) | authenticated | - | dbl | - | own user | `logout.device` | ok |
| POST | `B/forgot-password` | none | - | - | exempt | window per IP and email | - | `password_reset.request`, known and unknown addresses alike (F-A5-AUTH-2) | ok (enumeration safe, async mail) |
| POST | `B/reset-password` | reset UUID | - | - | exempt | 122-bit UUID, TTL | own user | `credentials.revoke_all` (F-CONF-14) | added under `B` (contract row, F-A3-4) |
| POST | `B/change-password` | reset UUID | - | - | exempt | as above | own user | `credentials.revoke_all` (F-CONF-14) | ok (same handler as reset) |
| POST | `/register`, `/confirm-email`, `/login`, `/refresh`, `/logout`, `/logout/device`, `/forgot-password`, `/reset-password` (root) | - | - | - | - | - | - | - | F-A3-4: were also served at the root outside both chains; removed |

## SSO user routes (`naqqa.sso.enabled=true`)

| Method | Path | Auth | Authority | S | CSRF | Rate limit | Tenant | Audit | Verdict |
|--------|------|------|-----------|---|------|------------|--------|-------|---------|
| GET | `B/methods` | none | - | - | n/a | - | client policy | - | ok |
| POST | `B/mfa/verify` | mfaToken (JWS, single use jti) | - | - | dbl if cookie | throttle `mfa:<user>` | policy of the token's client; remembered device only when the factor reaches the strongest enrolled class (F-A9-AUTH-3); recovery code use notified (F-A9-AUTH-5) | `mfa.verify` | ok |
| POST | `B/mfa/challenge` | mfaToken | - | - | dbl if cookie | window `mfa-challenge:<user>` (`rate-limits.mfa-verify`, F-A8-AUTH-5), OTP cooldown, hourly and daily cap | token methods only; email and SMS OTP never offered to an account with TOTP or a passkey (F-A8-AUTH-1, F-A9-AUTH-1) | `mfa.challenge` | ok |
| GET | `B/mfa/factors` | session | authenticated | - | n/a | - | own | - | ok |
| POST | `B/mfa/totp/enroll` | own session | - | S | dbl | - | own | `mfa.totp.enroll` | ok (F-A1-5) |
| POST | `B/mfa/totp/confirm` | own session | - | - | dbl | throttle `totp-confirm:<user>` | enrollment bound to user; raising the account class revokes all but the current session (F-A9-AUTH-2); security notice | `mfa.totp.enroll`, `credentials.revoke_all` | ok |
| POST | `B/mfa/hotp/enroll` | own session | - | S | dbl | - | own; `naqqa.sso.hotp.enabled` and the client TOTP switch; refused when TOTP or HOTP is active | `mfa.hotp.enroll` | ok (F-CONF-2) |
| POST | `B/mfa/hotp/confirm` | own session | - | - | dbl | throttle `hotp-confirm:<user>` | enrollment ticket bound to the user; counts as TOTP for the account class, so raising it revokes all but the current session; security notice `factor_added` | `mfa.hotp.enroll`, `credentials.revoke_all` | ok (F-CONF-2) |
| POST | `B/mfa/hotp/resync` | own session | - | - | dbl | throttle `hotp-resync:<user>` | own credential, two consecutive codes inside the resync window, counter only moves forward | `mfa.hotp.resync` | ok (F-CONF-2) |
| DELETE | `B/mfa/factors/{id}` | own session | - | S | dbl | - | own; security notice (F-A9-AUTH-5) | `mfa.factor.remove` | ok |
| POST | `B/mfa/recovery/regenerate` | own session | - | S | dbl | - | own; security notice (F-A9-AUTH-5) | `mfa.recovery.regenerate` | ok |
| POST | `B/webauthn/register/options` | own session | - | S | dbl | - | own, binding cookie + session | - | ok (F-A1-5) |
| POST | `B/webauthn/register/verify` | own session | - | - | dbl | challenge consumed atomically | own; raising the account class revokes all but the current session (F-A9-AUTH-2); security notice | `webauthn.register`, `credentials.revoke_all` | ok |
| POST | `B/webauthn/login/options` | none | - | - | dbl if cookie | - | policy | - | ok |
| POST | `B/webauthn/login/verify` | assertion | - | - | dbl if cookie | challenge single use (`findAndRemove`) | policy kind check | `login.webauthn` | ok |
| GET | `B/webauthn/credentials` | session | - | - | n/a | - | own | - | ok |
| PATCH | `B/webauthn/credentials/{id}` | own session | - | - | dbl | - | own | `webauthn.rename` (F-A4-AUTH-4) | ok (label only) |
| DELETE | `B/webauthn/credentials/{id}` | own session | - | S | dbl | - | own; security notice (F-A9-AUTH-5) | `webauthn.remove` | ok |
| POST | `B/otp/request` | none | - | - | dbl if cookie | window per IP, cooldown, `max-sends-per-hour` (F-A8-AUTH-7) and daily cap per destination | policy | `otp.request` | ok |
| POST | `B/otp/verify` | OTP cookie + code | - | - | dbl if cookie | throttle `otp:<destination>` | policy, same channel excluded as 2nd factor | `login.otp` | ok |
| POST | `B/magic-link/request` | none | - | - | dbl if cookie | window per IP, silent `rate-limits.account-mail` window per address (F-A9-AUTH-6) | policy, relative redirect only | `magic_link.request` | ok |
| POST | `B/magic-link/consume` | link token (atomic `findAndModify used=false`) + browser binding | - | - | dbl if cookie | throttle `magic:<ip>` | policy | `login.magic_link` | F-A3-1: email OTP no longer offered as 2nd factor |
| GET | `B/push/{id}` | none (random id) | - | - | n/a | - | state only | - | ok |
| POST | `B/push/{id}/respond` | own session | - | - | dbl | number matching | owner only; approving needs a session as strong as the strongest enrolled factor (F-A8-AUTH-6) | `push.respond` | ok |
| POST | `B/step-up` | own session | - | - | dbl | throttle `stepup:<user>` | policy of the request | `step_up` (method, step-up amr and acr, strength) | F-A3-6: a method weaker than the strongest enrolled factor class is refused (400 `STEP_UP_FACTOR_TOO_WEAK`) before verification, so `auth_time` is never refreshed by a weak factor; password step-up is refused for directory-managed accounts (F-A5-AUTH-3) |
| GET | `B/sessions` | session | - | - | n/a | - | own | - | ok |
| DELETE | `B/sessions/{id}` | own session | - | - | dbl | - | own | `session.revoke` | ok |
| POST | `B/sessions/revoke-others` | own session | - | - | dbl | - | own; also forgets every remembered device except this browser (F-A9-AUTH-7) | `session.revoke_others` | ok |
| GET | `B/account/export` | own session | - | S | n/a | - | own | `account.export` | ok, `no-store` |
| DELETE | `B/account` | own session | - | S | dbl | reauth throttle `erase:<user>` | own | `account.erasure*` | ok |
| GET | `B/account/links` | session | - | - | n/a | - | own | - | ok |
| POST | `B/account/links/{provider}/start` | own session | - | S | dbl | - | own session id | `account.link.start` | ok |
| DELETE | `B/account/links/{provider}` | own session | - | S | dbl | - | own | `account.unlink` | ok |
| GET | `/oauth2/authorization/{provider}` | none (link: own session) | - | - | n/a | state TTL | policy social list | `social.start` | ok |
| GET, POST | `/login/oauth2/code/{provider}` | binding cookie + state | - | - | n/a | flow ticket `findAndRemove` | flow client | `social.*` | ok |

## Admin and operator routes

| Method | Path | Auth | Authority | S | CSRF | Tenant scope | Audit | Verdict |
|--------|------|------|-----------|---|------|--------------|-------|---------|
| POST | `A/impersonate`, `B/admin/impersonate` | own session | `SSO_IMPERSONATE` | S | dbl | F-A3-3: needs access to `default` (user scope); target protection (F-A1-2) | `impersonation.start` | fixed |
| DELETE | `A/impersonate/{sessionId}`, `B/admin/impersonate/{sessionId}` | own session | `SSO_IMPERSONATE` | - | dbl | own impersonation sessions only | `impersonation.end` | ok |
| GET, POST | `A/audit/verify`, `B/admin/audit/verify` | session | `SSO_AUDIT` | - | dbl (POST) | `default` access (F-A4-AUTH-3) | - | fixed (read only) |
| GET | `A/audit/export`, `B/admin/audit/export` | session | `SSO_AUDIT_EXPORT` (the method annotation replaces the class one, as in the 7.2 contract) | - | n/a | `default` access (F-A4-AUTH-3) | `audit.export` | fixed |
| POST | `A/audit/checkpoint`, `B/admin/audit/checkpoint` | session | `SSO_AUDIT` | - | dbl | `default` access (F-A4-AUTH-3) | checkpoint itself | fixed (idempotent signed checkpoint) |
| GET | `B/admin/api-tokens` | session | `SSO_API_TOKENS` | - | n/a | `SsoAdminScope` (F-A1-4) | - | ok |
| POST | `B/admin/api-tokens`, `.../{id}/rotate`; DELETE `.../{id}` | own session | `SSO_API_TOKENS` | S | dbl | `SsoAdminScope`; issue under the tenant lease with the `apiTokens` quota, issue and rotate need a writable tenant (F-A8-AUTH-4) | `api_token.*` | ok |
| GET | `A/tenants` | session | `TENANTS` or `CLIENTS` or `TOKENS` | - | n/a | visible tenants | - | ok |
| POST | `A/tenants` | own session | `SSO_ADMIN_TENANTS` | S | dbl | platform | `admin.tenant.create` | ok |
| PATCH | `A/tenants/{tenantId}` | own session | `SSO_ADMIN_TENANTS` | S | dbl | platform, `default` cannot be suspended | `admin.tenant.update` | ok |
| GET | `A/tenants/{tenantId}/usage` | session | as GET tenants | - | n/a | 404 outside scope | - | ok |
| GET | `A/tenants/{t}/clients`, `.../{clientId}` | session | `SSO_ADMIN_CLIENTS` | - | n/a | owner tenant, 404 otherwise | - | ok |
| POST | `A/tenants/{t}/clients` | own session | `SSO_ADMIN_CLIENTS` | S | dbl | writable tenant, quota | `admin.client.create` | ok, secret once |
| PATCH | `A/tenants/{t}/clients/{clientId}` | own session | `SSO_ADMIN_CLIENTS` | S | dbl | writable tenant | `admin.client.update` | ok |
| DELETE | `A/tenants/{t}/clients/{clientId}` | own session | `SSO_ADMIN_CLIENTS` | S | dbl | owner tenant | `admin.client.delete` | ok |
| POST | `A/tenants/{t}/clients/{clientId}/secret` | own session | `SSO_ADMIN_CLIENTS` | S | dbl | writable tenant | `admin.client.secret_rotate` | ok, secret once |
| GET | `A/tenants/{t}/saml-idp` | session | `SSO_ADMIN_CLIENTS` | - | n/a | owner tenant, 404 otherwise | - | ok, certificates as PEM, subject, expiry, fingerprint |
| PUT | `A/tenants/{t}/saml-idp` | own session | `SSO_ADMIN_CLIENTS` | S | dbl | writable tenant; domains, JIT, roles, default role, MFA contexts and assurance trust need `SSO_ADMIN_TENANTS` (403 `PLATFORM_ONLY`, F-SP-1), and so do entity id, certificates, email attribute and NameID format once domains or trust are granted (F-A12-AUTH-2, 682); authoritative domain unique (409 `DOMAIN_CLAIMED`); administrative roles refused (F-SP-2); revoking the last allowed domain and the trust removes every `saml:<tenant>` link (F-A13-AUTH-6, 691); serialized by an admin lease | `admin.saml_idp.update` (success and denied) | ok |
| DELETE | `A/tenants/{t}/saml-idp` | own session | `SSO_ADMIN_CLIENTS` | S | dbl | owner tenant; platform only when the IdP has allowed domains or trust (403 `PLATFORM_ONLY`); removes every `saml:<tenant>` link (F-A13-AUTH-1, 685) | `admin.saml_idp.delete` (attribute `links`) | ok |
| GET | `A/keys` | session | `SSO_ADMIN_KEYS` | - | n/a | `default` access (F-A1-14) | - | ok, public data |
| POST | `A/keys/{ring}/rotate` | own session | `SSO_ADMIN_KEYS` | S | dbl | `default` access | `admin.keys.rotate` | ok |
| GET | `A/tenants/{t}/api-tokens` | session | `SSO_ADMIN_TOKENS` | - | n/a | owner tenant | - | ok |
| POST | `A/tenants/{t}/api-tokens`, `.../{id}/rotate` | own session | `SSO_ADMIN_TOKENS` | S | dbl | writable tenant, publishable key client of the tenant | `admin.api_token.*` | ok, token once, scopes become `SCOPE_*` only |
| DELETE | `A/tenants/{t}/api-tokens/{id}` | own session | `SSO_ADMIN_TOKENS` | S | dbl | owner tenant | `admin.api_token.revoke` | ok |
| GET | `A/users?q=&limit=`, `A/users/{userId}` | session | `SSO_ADMIN_USERS` | - | n/a | `default` access | - | ok |
| GET | `A/users/email-conflicts` | session | `SSO_ADMIN_USERS` | - | n/a | `default` access | - | ok, round 7: index state, group and account counts, first 50 groups (oldest account and newer duplicates) |
| POST | `A/users/email-conflicts/scan` | own session | `SSO_ADMIN_USERS` | S | dbl | `default` access, serialized by an admin lease | `admin.user.email_conflict_scan` | ok, re-runs the startup migration (F-A6-AUTH-10) |
| POST | `A/users/{userId}/email` | own session | `SSO_ADMIN_USERS` | S | dbl | `default`, `TARGET_PROTECTED`, not self, only an account that shares its address in another case, new address free in any case, serialized by an admin lease | `admin.user.email_conflict_resolve` | ok, clears `emailVerified`, then re-runs the migration (F-A6-AUTH-10) |
| POST | `A/users/{userId}/factors/reset` | own session | `SSO_ADMIN_USERS` | S | dbl | `default`, `TARGET_PROTECTED`; security notice to email and phone (F-A9-AUTH-5) | `admin.user.factors_reset` | ok |
| DELETE | `A/users/{userId}/sessions`, `.../sessions/{sessionId}` | own session | `SSO_ADMIN_USERS` | S | dbl | `default`, `TARGET_PROTECTED` | `admin.user.session*_revoke` | ok |
| POST | `A/users/{userId}/unlock` | own session | `SSO_ADMIN_USERS` | S | dbl | `default`, `TARGET_PROTECTED` | `admin.user.unlock` | ok |
| DELETE | `A/users/{userId}` | own session | `SSO_ADMIN_USERS` | S | dbl | `default`, `TARGET_PROTECTED` | `admin.user.delete` | ok |

## Legacy RBAC routes (present when the host keeps the bundled controllers)

Rules (F-A3-5, `RbacGuard`): a caller assigns, removes or replaces only roles and sub-roles whose whole authority set it holds itself; role and sub-role create, update and delete only add or remove authorities the caller holds; nobody changes their own roles; platform operators (`SSO_ADMIN_TENANTS`) are exempt from the subset rule, never from the self rule. Every mutation is audited (`rbac.*`, actor, target, roles and authorities before and after; logged at INFO when SSO is off).

| Method | Path | Authority | S | CSRF | Guard | Audit | Verdict |
|--------|------|-----------|---|------|-------|-------|---------|
| POST | `/api/admin/users` | `users:create` | S | dbl | password policy (F-A2-5), protected roles need platform (F-A1-3), role subset of caller | `rbac.user.create` | fixed (F-A3-5) |
| GET | `/api/admin/users`, `/api/admin/users/{userId}` | `users:read` | - | n/a | - | - | ok |
| POST, DELETE | `/api/admin/users/{userId}/roles/{roleName}` | `users:update` | S | dbl | F-A1-3, role subset of caller, not self | `rbac.user.roles` | fixed (F-A3-5) |
| PUT | `/api/admin/users/{userId}/roles` | `users:update` | S | dbl | F-A1-3, every added or removed role subset of caller, not self | `rbac.user.roles` | fixed (F-A3-5) |
| PATCH | `/api/admin/users/{userId}/disable`, `/enable` | `users:disable`, `users:enable` | S | dbl | F-A1-3, disable revokes sessions (F-A1-6) | `rbac.user.disable`, `rbac.user.enable` | fixed (audit) |
| DELETE | `/api/admin/users/{userId}` | `users:delete` | S | dbl | F-A1-3, erasure service | `account.erasure`, `rbac.user.delete` | ok |
| GET | `/api/roles`, `/api/roles/authorities`, `/api/roles/{id}` | `roles:read` | - | n/a | - | - | ok |
| POST, PUT, DELETE | `/api/roles`, `/api/roles/{id}` | `roles:create`, `roles:update`, `roles:delete` | S | dbl | F-A1-3, changed authorities held by caller | `rbac.role.*` | fixed (F-A3-5) |
| POST | `/api/roles/switch` | authenticated | - | dbl | caller resolved by token user id, target role must be assigned, new token keeps `sid`, `amr`, `acr`, `auth_time`, `act` and never outlives the caller's `exp` | `rbac.role.switch` | fixed (F-A3-7); naqqa-os-be keeps its own `/api/role-switch`, which calls the same service and gets the same context-preserving token |
| GET | `/api/sub-roles`, `/api/sub-roles/authorities`, `/api/sub-roles/{id}` | `sub_roles:read` | - | n/a | - | - | ok |
| POST, PUT, DELETE | `/api/sub-roles`, `/api/sub-roles/{id}` | `sub_roles:*` | S | dbl | F-A1-3, changed authorities held by caller | `rbac.sub_role.*` | fixed (F-A3-5) |

## Protocol routes (chain 2)

| Method | Path | Auth | CSRF equivalent | Rate limit | Tenant | Audit | Verdict |
|--------|------|------|-----------------|------------|--------|-------|---------|
| GET | `/.well-known/openid-configuration`, `/oauth2/jwks` | none | n/a | - | default | - | ok |
| GET, POST | `/oauth2/authorize` | SSO session cookie | PKCE, state, single-use code | core | client tenant must be `default` | core `oidc.*` | F-A3-2: client MFA policy now raises `minAcr` |
| GET | `/oauth2/authorize/resume` | session + handle bound to `sso_oidc_binding` | browser binding | - | - | core | ok |
| POST | `/oauth2/authorize/consent` | session + bound handle | browser binding, SameSite Lax | - | - | core | ok |
| GET | `/oauth2/interaction` | bound handle | browser binding | - | - | - | ok, `no-store` |
| POST | `/oauth2/par`, `/oauth2/token`, `/oauth2/introspect`, `/oauth2/revoke` | client auth | n/a | core | client tenant | core | ok, `no-store` on token responses; disabled clients hidden (F-A2-2) |
| GET, POST | `/oauth2/userinfo` | OIDC access token | n/a | - | - | - | ok |
| POST | `/oauth2/device_authorization` | client | n/a | core | - | core | ok |
| GET | `/oauth2/device`, `/oauth2/device/lookup` | none / session | n/a | per IP and per session lookups | - | core | ok |
| POST | `/oauth2/device/decision` | session | JSON only (`consumes`), no CORS on `/oauth2/**`, SameSite Lax | - | - | core | ok, client `minAcr` applies (F-A3-2) |
| GET, POST | `/oauth2/logout`; POST `/oauth2/logout/confirm` | session, `id_token_hint` or confirm handle | bound handle | - | - | core | ok |
| POST | `/oauth2/register` | registration token (off by default) | n/a | - | - | core | ok |
| GET | `/saml2/idp/metadata` | none | n/a | - | - | - | ok |
| GET, POST | `/saml2/idp/sso` | session | signed or registered SP, replay cache | - | - | `saml.sso` | ok, AuthnContext from session level |
| GET | `/saml2/idp/continue` | session + pending ticket bound to browser and user | browser binding | - | - | `saml.sso` | ok |
| GET, POST | `/saml2/idp/slo` | signed LogoutRequest only | signature | replay cache | - | `saml.slo` | ok: ends only the user's sessions that signed in to the requesting SP, matching `SessionIndex` when given (F-A8-AUTH-3, SSO-T-auth-323, 627) |
| GET | `/saml2/sp/{tenant}/login` | none (`naqqa.sso.saml.sp.enabled`, IdP enabled, client `samlSp`) | request id stored once per tenant (`sso_saml_requests`), flow bound to `sso_saml_sp` (HttpOnly, Secure, SameSite=None, ACS path) | window `rate-limits.saml-sp` per IP, tenant `authRequestsPerMinute` | path tenant through `SamlSpIdpStore.forLogin` (exists, not suspended, IdP enabled) | `saml.sp.start` | ok, relative relay only, signed AuthnRequest when the SP key is set (F-E11); with `link` the intent is taken by its own session and the flow carries user and session (F-SP-5) |
| POST | `/saml2/sp/{tenant}/acs` | signed SAML response | chain 0 (no CORS, no CSRF filter); flow ticket taken once by the binding cookie, InResponseTo = its request id, request id consumed once, assertion id in `sso_saml_replay`; unsolicited refused unless `allowUnsolicited` | window per IP | flow tenant must equal the path tenant; identity rules of PLAN 7 (links, allowed and authoritative domains, no adoption of admin, unverified, TOTP or passkey accounts) | `saml.sp.acs`, `saml.sp.login`, `saml.sp.signup`, `saml.sp.adopt`, `saml.sp.roles` | ok (F-E11, F-SP-1 to 4, SSO-T-auth-664 to 678); link flows need the live session, allowed domain, non-administrative account, `saml.sp.link` (F-SP-5, 679); unverified JIT links and administrative accounts refused (F-A12-AUTH-1, 3; 680, 681) |
| POST | `B/account/links/saml/{tenant}/start` | own session | step-up at the account's class, dbl CSRF; intent single use, bound to the session | window `rate-limits.saml-sp` per IP | `SamlSpIdpStore.forLogin`; administrative, directory-managed and already linked accounts refused | `account.link.start`, `saml.sp.link` (denied) | ok (F-SP-5, SSO-T-auth-679) |
| GET | `/saml2/sp/{tenant}/metadata` | none | n/a | - | `SamlSpIdpStore.forMetadata` | - | ok, public data |
| GET, POST, PUT, PATCH, DELETE | `/scim/v2/Users*`, `/scim/v2/Groups*` | API token with `scim` scope | Bearer only | API token throttle, tenant `scimOpsPerDay` (429 SCIM error, `Retry-After` to the next UTC day, F-A3-8) | token tenant, account provisioning only for `scim.account-tenants` (F-A1-11); PUT, PATCH, DELETE of a user mapped to an account with protected or legacy admin authorities answer 403 (F-A4-AUTH-2) | `scim.*` | fixed |
| GET | `/scim/v2/ServiceProviderConfig`, `/ResourceTypes*`, `/Schemas*` | API token | Bearer only | `scimOpsPerDay` | - | - | ok |
| GET | `/oauth2/authorization/{provider}`, `/login/oauth2/code/{provider}` (`SocialPlaceholderController`) | none | n/a | - | - | - | 501 when social login is off |

Count: 172 method and path routes across 30 controllers: 164 still mapped by 29 controllers (F-E11 added `SamlSpController` and `SamlIdpAdminController`, 6 routes; F-SP-5 added the SAML link start; F-E13 added the three HOTP routes; SSO-T-auth-694 fails when a mapped route or this count is missing here, F-CONF-2) (each method and each path counted, so the two impersonation and audit base paths count twice) plus the 8 root duplicates of `AuthController` removed by F-A3-4.

## Cross-cutting checks

| Check | Result |
|-------|--------|
| Policy selectors from request input (`X-Client-Id`, `client_id`, `X-SSO-Key`) | One resolver, `SsoClientPolicy` (client policy, publishable-key tenant, LDAP directory). Strictest wins inside the resolver since round 5: methods = override AND base, MFA for all = client OR global, MFA roles = union, step-up max age = minimum, social and SAML lists narrowed. Login MFA is the union of client and global policy (F-A2-1). A directory-managed account authenticates by password only through its directory whatever client is named (F-A5-AUTH-3). OIDC authorize and device decision now apply the client MFA policy to existing sessions (F-A3-2). Unknown ids fall back to the global policy. Tenant OIDC clients outside `default` are refused by the core tenant check. |
| Identity by email | Every create path stores the trimmed lower-case address and refuses an address taken in any case; `users.email_ci_unique` enforces it in the database (F-A6-AUTH-1), created at startup in both modes only when no case duplicates exist (F-A6-AUTH-10). Password, OTP, magic link, passkey options, LDAP, social and SAML SLO look up the address with the index collation (en, strength 2) and resolve to the oldest account (lowest id) when legacy case duplicates exist, so the older owner is never locked out and newer duplicates cannot sign in by email until an operator moves them; OTP purpose is the normalised address. Social auto-link needs a verified local email (F-A1-15). LDAP adopts only verified local accounts not linked to another DN, one DN per account (F-A6-AUTH-6). The SAML SP adopts only verified local accounts of class 1 or 2 without administrative authorities, in a domain for which the tenant IdP is authoritative (set by a platform operator, one IdP per domain; F-SP-1, F-SP-3). SAML SLO maps email NameID only for signed requests. Lifecycle per transition: `identity-lifecycle.md`. |
| Single-use artifacts | mfaToken jti (`insert` on unique id), magic link (`findAndModify used=false`), WebAuthn challenge (`findAndRemove`), social flow ticket (`findAndRemove`), OIDC code and PAR (core `consume*`), refresh tokens (rotation + reuse detection), recovery codes (compare-and-set on the read state, F-A6-AUTH-9), OTP challenges (`consumed=false` conditional update, F-A6-AUTH-9), TOTP steps (core stores), push approvals (atomic transitions), password reset (deleted on use, 122-bit UUID, SHA-256 at rest), email confirmation (5 attempts, HMAC at rest, F-A6-AUTH-8). |
| Responses carrying secrets | All inside chain 1 or 2, so Spring Security sets `no-store` unless the handler sets its own (`no-store` on OIDC, SAML, social, export). The root duplicates without it are removed (F-A3-4). Secrets are returned once (client secret, API token, TOTP secret, recovery codes) and never logged; log lines carry ids, counts or codes only. Round 7 log review: no SMS code, push challenge or phone number reaches the log (F-A7-AUTH-1), database exception messages are not logged verbatim (F-A7-AUTH-4), the email index migration logs counts only. |
| Outbound calls | Back-channel logout, social OAuth and JWKS through core `SafeHttp` (blocked ranges, timeouts, size cap); back-channel URIs also validated at admin time. Test modes are opt-in properties, refused at startup unless `naqqa.sso.dev-mode=true` (F-A7-AUTH-2, default deny since F-CONF-17). SendGrid via its client. |
| Startup validation | Pepper required in SSO mode, secrets length, OIDC issuer, client secrets present, OIDC key distinct from the access token key, SAML certificate pairing, social and LDAP secrets, RSA key pair and SendGrid key required. Dev-only switches (`naqqa.sso.social.test-mode`, `naqqa.sso.oidc-provider.backchannel.test-mode`, `naqqa.sso.risk.signal-override`, `naqqa.sso.ldap.directories.*.allow-plain-for-tests`, `naqqa.sso.session.cookie-secure=false`, `app.cookie.secure=false`) refuse to start unless `naqqa.sso.dev-mode=true`, which logs a WARN at startup listing the switches in use; the profile no longer matters (F-A7-AUTH-2, F-CONF-17, SSO-T-auth-747). |
| Mail templates | Links are built from `app.url` only: confirmation (`uuid`, code), reset (URL-encoded `uuid` and address, HTML-escaped attribute, F-A7-AUTH-3), magic link (URL-encoded token, escaped); OTP and erasure bodies escape their values; subjects are fixed text plus `app.platform-name`; recipients are the stored address (reset, confirmation, magic link, erasure) or the normalised address being proven (OTP); SendGrid JSON API, no raw headers. |
| Dependency surface (round 7) | Runtime tree: Spring Boot, Spring Security, Spring Data Mongo and Redis, `naqqa-sso`, Jackson, Mongo driver, Lettuce and Jedis (with its `redis-authx-core` Redis client token helper, not used), SendGrid client (Apache HttpClient, commons-codec), Tomcat, Logback. No JWT, JOSE, OAuth, SAML, crypto provider or password hashing library outside Spring Security's own `spring-security-crypto` (BCrypt verification of legacy hashes). |
| Exception handlers | Stable codes, generic 500, no stack trace or internal ids in bodies; cross-tenant resources answer 404 `NOT_FOUND`. |
| MFA policy consistency | Login: union of client and global policy, strong factor forces MFA, same channel never counts twice (OTP login and magic link, F-A3-1). Step-up: fresh `auth_time` and a factor at least as strong as the strongest enrolled class (F-A3-6); the session records the strength, amr and acr of its latest authentication; enrolling TOTP or a passkey raises the strength of the enrolling session. OIDC: client `minAcr`, `acr_values`, client MFA policy (F-A3-2). SAML: AuthnContext from session level, `NoAuthnContext` for unknown classes, step-up redirect. |

| Tokens minted | One issuer: `TokenService.generateAccessToken(user, role, AccessContext)`; the context-less overload is gone. `AccessContext` requires `amr`, `acr` and `auth_time`, an `act` only with a session; in SSO mode a session-less context is refused unless its `auth_time` is older than the step-up max age. Login and MFA (`sid`, `amr`, `acr`, `auth_time`), refresh (binding context), step-up (session union), impersonation (`act`, own session with its `amr` `imp` and `acr`, TTL at most 30 minutes), role switch (caller context, F-A3-7). Session-less tokens exist only in legacy mode (`naqqa.sso.enabled=false`) and for 2.0 refresh migration, whose `auth_time` is the legacy issue time (F-A4-AUTH-1). |
| Less-trusted policy over more-trusted principals | Request selected client policy only narrows methods; tenant admin client settings never enable a globally disabled method; legacy RBAC callers cannot grant or revoke more than they hold (F-A3-5); SCIM tokens cannot change admin accounts (F-A4-AUTH-2); audit, users, keys and impersonation need `default` access (F-A3-3, F-A4-AUTH-3). |
| Tenant quotas | `authRequestsPerMinute` and `scimOpsPerDay` counted per tenant on the Mongo rate limit store (`quota:auth:<tenant>:<minute>`, `quota:scim:<tenant>:<day>`), decisions by `QuotaPolicy` (core `PlanQuotaPolicy` semantics on the tenant plan). Plan: the stored `sso_tenants` record wins; without one, `naqqa.sso.tenants.plans.<id>` (default `default=ENTERPRISE`), then tenants referenced in properties (OIDC clients, `scim.account-tenants`, social registrations) get `naqqa.sso.tenants.configured-plan` (PRO), and any other id from request input `naqqa.sso.tenants.unknown-plan` (FREE) (F-A5-AUTH-1). Interactive sign-in is never blocked (soft: allow, `quota.limit` audit and a warning at the soft and hard thresholds); API-token and SCIM traffic gets 429 at the hard limit (F-A3-8). |

## Structural checks (round 5, `auth/src/test/java/com/naqqa/auth/architecture`)

Plain JUnit over Spring's classpath scanning and ASM bytecode (call graph of `com.naqqa.auth`, depth 8), no new dependencies.

| Test | Rule |
|------|------|
| SSO-T-auth-602 `EndpointSecurityArchitectureTest` | Every `@RequestMapping` handler, of any visibility (Spring MVC maps package-private handlers too, F-CONF-9), is classified explicitly (an unclassified or stale entry fails). Per handler: access (`@PreAuthorize` with the listed authority, `SsoWeb.requireOwnSession` for own-data mutations, authenticated principal, credential, API token, protocol chain), CSRF (path inside the API chain and not exempt, or in the `SsoCsrfFilter` exemptions, or in the protocol chain; exempt and machine-only handlers must not act on the cookie principal; a double-submit mutation must not be reachable by a safe method or an unrestricted mapping, which `SsoCsrfFilter` skips, F-CONF-10), audit (the action string reaches an `SsoAudit`/`AuthAudit` call, core audit for OIDC, or an explicit reason), `@RequiresStepUp` equal to the S column. Admin mutations without step-up must still refuse API tokens and impersonation. |
| SSO-T-auth-605 `PolicyResolverArchitectureTest` | `X-Client-Id`, `client_id`, `X-SSO-Key` and the publishable-key request attributes appear only in `SsoWeb`, `SsoClientPolicy` and `PublishableKeyFilter`; `SsoWeb.clientId` is called only by `SsoClientPolicy`; per-client configuration is read only by the resolver and the two scoped admin controllers; handler parameters named after a client or tenant must pass through `SsoAdminScope`, or for the public SAML SP routes through `SamlSpIdpStore.forLogin` or `forMetadata` (tenant exists, not suspended, IdP configured; F-E11). The SAML SP rows are classified as protocol and machine-only in 602, and `SamlSpAccounts.signIn` and `resolve` are decision points of SSO-T-auth-643. |
| SSO-T-auth-606 | Strictest wins, checked on every component of `SsoClientPolicy.Policy` (a new component without a classification fails), with and without client settings stored by a tenant admin (F-CONF-12). |
| SSO-T-auth-607, 608 `TokenIssuerArchitectureTest` | Only `TokenService.generateAccessToken` (access tokens) and `MfaTokenService.issue` (`mfa+jwt`) sign JWTs; every minting method takes an `AccessContext`; partial contexts and fresh session-less contexts in SSO mode are refused. |
| SSO-T-auth-694 `SecurityDocsArchitectureTest` | Every mapped handler (any visibility, F-CONF-9), with each of its methods and paths, appears in a table of this inventory, and the count above matches the mapped routes and controllers (F-CONF-2). SSO-T-auth-745 does the same for the entries of `AssuranceEngine` in `assurance-model.md` (F-CONF-15). |

## Findings of the confirmation round

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-CONF-2 | low | The three HOTP routes (`B/mfa/hotp/enroll`, `/confirm`, `/resync`) were missing, the count was stale (155) and the CSRF column said n/a for WebAuthn login, OTP and magic-link routes that `SsoCsrfFilter` checks for cookie sessions; no test tied this inventory to the mapped handlers | SSO-T-auth-694 |
| F-CONF-5 | low | SSO-T-auth-643 saw a method-id comparison only through `String.equals` or `hashCode` (`Objects.equals`, `equalsIgnoreCase` or a literal `Set.of(...).contains` passed) and did not guard `HotpEnrollment.isActive`; the test now covers every `String` comparison, `Objects.equals` and lookups in literal collections (factor export and the enrollment guards excepted) | SSO-T-auth-643 |
| F-CONF-6 | low | SSO-T-auth-602 accepted any `@PreAuthorize` containing the listed authority, so a widened expression (`... or isAuthenticated()`, an extra authority in `hasAnyAuthority`) passed; it now requires exactly `hasAuthority('<listed>')`, or the declared `hasAnyAuthority` set of `A/tenants` reads | SSO-T-auth-602 |
| F-CONF-7 | low | SSO-T-auth-605 matched selector header names case-sensitively (`x-client-id`, `x-sso-key` passed); now case-insensitive | SSO-T-auth-605 |
| F-CONF-8 | low | SSO-T-auth-607 saw only signing through `com.naqqa.sso.jose`; `SigningKey.sign` anywhere and `java.security.Signature.sign` outside the SAML SP request signer and the self-signed certificate now fail it | SSO-T-auth-607 |

## Findings of the second confirmation round

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-CONF-9 | low | The architecture tests enumerated only public handler methods; Spring MVC also serves a package-private `@GetMapping`/`@PostMapping`, so an unclassified, unauthorized and undocumented handler passed 602, 605 and 694 | SSO-T-auth-602, 605, 694 |
| F-CONF-10 | low | SSO-T-auth-602 accepted a mutation classified double-submit CSRF while it was mapped to GET or to every method; `SsoCsrfFilter` skips safe methods | SSO-T-auth-602 |
| F-CONF-11 | low | SSO-T-auth-643 matched method ids case-sensitively and ignored keyed lookups (`"TOTP".equalsIgnoreCase(m)`, a strength table `Map.of("totp", 3, ...).get(m)` passed) | SSO-T-auth-643 |
| F-CONF-12 | low | SSO-T-auth-606 resolved policies without the admin settings store, so tenant-admin client settings were never checked against strictest wins | SSO-T-auth-606 |
| F-CONF-13 | low | SSO-T-auth-644 took the account class of its oracle from `AssuranceEngine.accountClass`, so a misclassified factor passed 644 and 645 | SSO-T-auth-644 |
| F-CONF-14 | low | The audit cells of `B/refresh`, `B/logout`, `B/reset-password` and `B/change-password` named "hooks" instead of the events the handlers write | SSO-T-auth-602 |
| F-CONF-15 | low | `assurance-model.md` did not name every engine entry (`mfaRequiredByPolicy` among them) | SSO-T-auth-745 |
| F-CONF-17 | high | Dev-only switches were refused only under a `prod` or `production` Spring profile, which no consumer (naqqa-os-be, OMY) activates, so test modes, the risk signal override, plain LDAP and insecure cookies were accepted in production; now default deny behind `naqqa.sso.dev-mode=true` | SSO-T-auth-747 |
| F-CONF-16 | medium | Default policy: an administrative account signed in with the password alone on a fresh install and no second factor was enabled; `mfa.required-roles` did not bring an un-enrolled account to MFA. Fixed: TOTP and recovery codes on by default, `mfa.required-for-admin=true`, restricted enrollment-only session (`MfaEnrollmentGate`, 403 `MFA_ENROLLMENT_REQUIRED`), see `assurance-model.md` section 3 | SSO-T-auth-746, 748 to 756, 759 |
| F-CONF-18 | medium | Lost update on the user document: the password rehash at sign-in and other auth flows (reset, social link, SCIM, admin role changes) saved the whole user read earlier, so a sign-in with the old password racing a password change could write the old password back. Fixed: the rehash is a conditional field update (`replacePassword`, only where the stored hash is still the verified one, otherwise the sign-in is refused) and every other user write in auth flows is an atomic field update (`updateFields`); full saves remain only for account creation | SSO-T-auth-757, 758 |
| F-CONF-20 | medium | The enrollment restriction checked `enrollable` on the caller-chosen client policy only: an administrator without a factor naming a client without TOTP and WebAuthn got a full session from the password alone. Fixed: client or global policy decides (`SsoLoginFlow.enrollmentRequired`, sign-in and refresh) | SSO-T-auth-765 |
| F-CONF-21 | medium | Verifying the email (magic link, email OTP) of an account opened through an unverified Facebook or SAML JIT link kept the sessions opened before it. Fixed: `SsoAccounts.markEmailVerified` revokes every session, refresh family, OIDC session and grant on the first verification | SSO-T-auth-766, 743 |
| F-CONF-22 | low | Admin role and sub-role add and remove still wrote the whole array read earlier, so a grant racing another administrator's removal wrote the removed role back. Fixed: `$addToSet` and `$pull` (`UserFieldOperations.addReference`, `removeReference`); the only full saves of `UserEntity` left are account creation and the startup seed, each inserting a new entity | SSO-T-auth-767, 768 |
| F-CONF-23 | low | Mutation check of the F-CONF-16 and F-CONF-19 tests: the profile flag on every allowlisted route, a remembered device skipping an administrator's second channel and `enrollable` ignoring WebAuthn all passed. Fixed: 759 checks other allowlisted routes carry no flag, 769 (remembered device), 770 (passkey-only deployment). Two mutants are equivalent and kept as defense in depth: the SAML IdP restriction check (the gate refuses `/saml2/idp/sso` first) and the administrative check at `/mfa/verify` (the signed MFA token already lists only accepted methods) | SSO-T-auth-759, 769, 770 |
| F-CONF-24 | low | A SCIM email change (`PUT`/`PATCH /scim/v2/Users/{id}`) moved the mailbox factor to another address but kept every session, refresh family, OIDC grant, remembered device and the magic links sent to the previous address. Fixed: `ScimAccountSync` revokes all (`email_changed`) | SSO-T-auth-772 |
| F-CONF-25 | medium | `DELETE B/account/links/{provider}` and the platform removal of a tenant IdP's links (`DELETE A/tenants/{t}/saml-idp`, last grant revoked) ended no session opened through the removed identity. Fixed: unlink revokes all except the current session (`identity_unlinked`), `AccountLinkService.removeProvider` revokes all for every account it unlinks (`identity_removed`) | SSO-T-auth-772 |
| F-CONF-26 | low | An LDAP bind through `B/login` that adopts a local account dropped its password but kept the sessions opened with it. Fixed: `DirectoryLogin` revokes all on adoption (`directory_adopted`) | SSO-T-auth-772 |
| F-CONF-27 | low | A pending password reset token (`POST B/forgot-password`) survived every revoke all, so `B/change-password` still set the password after a factor change, unlink, admin factor reset or session revoke, disable or SCIM deactivation. Fixed: `SsoCredentialRevoker` deletes the reset record of the account's address | SSO-T-auth-772 |
| F-CONF-20 class | - | No other per-client setting weakens a global or account rule: SSO-T-auth-771 classifies every client setting and compares sign-in, enrollment restriction, OIDC acr and lifetimes through every client variant with the global policy | SSO-T-auth-771 |
| F-CONF-28 | low | Mutation check of SSO-T-auth-643: a second-factor method-id table held in a field (`static Map.of("totp", 3, ...)` or an instance `Set` of `"email_otp"`, `"sms_otp"`) looked up in `B/step-up` passed. Fixed: 643 also follows field reads to their initializer and fails a lookup of such a table outside `AssuranceEngine` (the client switch names of `SsoClientSettingsStore.METHODS` excepted) | SSO-T-auth-643 |
| F-CONF-29 | low | A SCIM email change revoked with the new address only: the reset token and the email sign-in code of the previous address survived, `B/change-password` answered 500 for that token, and an account taking the previous address was reset or signed in by them. Fixed: `SsoCredentialRevoker.revokeAfterAddressChange` ends both for the previous address; a reset record whose account is gone answers 400 `PASSWORD_RESET_EXPIRED_LINK` and is deleted | SSO-T-auth-775 |
| F-CONF-30 | low | `B/login` in legacy mode logged the client-supplied device id and client type at INFO on every sign-in (log forging, persistent device identifier). Fixed: line removed (the sign-in is audited); SSO-T-auth-774 fails any log statement that passes a token, code, secret, password, email, phone, IP, cookie, session, device, request or header value or a raw exception, unless masked, a stable error code or allowlisted with a reason | SSO-T-auth-774 |
| F-CONF-31 | low | A private key set inline without its PEM header (`naqqa.sso.saml.idp.private-key`, `naqqa.sso.saml.sp.private-key`, a certificate setting, `rsa.private-key`) was read as a resource location, so the startup failure chained a `FileNotFoundException` that carried the key material. Fixed: the failure names the setting only and carries no cause; startup validation messages name settings and environment variables, never their values | SSO-T-auth-776 |
| F-CONF-32 | low | Mutation check of SSO-T-auth-768: a full `UserEntity` save through the Mongo template (`template.save(user)`) passed, so the F-CONF-18 lost update could return outside `UserRepository`. Fixed: 768 also fails a whole-document template write (`save`, `insert`, `findAndReplace`, `replace`) in code that handles `UserEntity` or the `users` collection | SSO-T-auth-768 |
| F-CONF-33 | low | Spring Boot's `FormContentFilter` answered 500 (default error body) to a PUT, PATCH or DELETE form body with invalid percent-encoding, before any naqqa-auth code ran. Fixed: `MalformedRequestFilter`, registered just before `FormContentFilter` for the requests of the naqqa-auth chains (`appleFormPostCallbackFilterChain`, `authSecurityFilterChain`, `ssoProtocolSecurityFilterChain`), answers 400 `{ code: "INVALID_REQUEST" }` when a filter in front of the dispatcher fails on client input (`IllegalArgumentException`, `HttpMessageNotReadableException`, `HttpMediaTypeException`, `MultipartException`) and the response is not committed; any other failure propagates | SSO-T-auth-773 |
| F-CONF-34 | low | Inside the dispatcher a broken multipart body (any POST, PUT, PATCH, DELETE route), a header value or form parameter name rejected by the Spring Security firewall (`RequestRejectedException`, NUL in `X-Device-Id`, `=&%00=` form body) and a JSON body repeating a creator property (`InvalidDefinitionException`) reached the catch-all: 500 `INTERNAL_ERROR` and the raw exception logged at ERROR, the rejected header value included. Fixed: `GlobalExceptionHandler` answers 400 `Auth.Errors.InvalidRequest` for `MultipartException`, `RequestRejectedException` and `HttpMessageConversionException` (a `HttpMessageNotWritableException` stays 500), nothing logged | SSO-T-auth-773 |
| F-CONF-35 | low | The SAML IdP `ServiceProvider` (core public API) accepted any ACS and SLO URL, and `naqqa.sso.saml.idp.service-providers[n]` passed them unchecked: a plain `http://` ACS outside loopback, `javascript:` or a relative URL became the action of the auto-submitted form that carries the signed assertion (OIDC redirect URIs were already validated in core). Fixed: `ServiceProvider` refuses any ACS or SLO URL that fails `RedirectUris.validateHttps` (`SAML_SP_CONFIG`); naqqa-auth fails startup with a message naming the setting. Review of round 8 also checked `MalformedRequestFilter` transparency: valid form posts on every form route, PUT/PATCH/DELETE form bodies and the 401, invalid client, CSRF and 403 decisions answer the same with and without the filter | SSO-T-saml-142, SSO-T-auth-778, SSO-T-auth-779 |
| Error surface | - | SSO-T-auth-773 calls every mapped handler with junk path variables, queries and bodies as anonymous, user, platform administrator and API token, then with malformed input (badly percent-encoded queries and form bodies on every method, unknown charsets, invalid UTF-8, nested and oversized JSON, duplicate keys, broken multipart, junk Content-Type, Accept, Authorization, Cookie, Origin, CSRF and forwarding headers, megabyte inputs) through MockMvc and as raw requests through the embedded server (oversized header, bad path escapes, empty charset, body above the form limit): no 5xx, no exception, class, package or stack frame in a body. A host that never created the default role (`USER`) still answers 500 at `POST /api/admin/users` without `roleName`, a server configuration state, not client input | SSO-T-auth-773 |

## Findings of round 9

Authentication assurance review, see `assurance-model.md` (factor classes, attacker capabilities, invariant, transition table, findings F-A9-AUTH-1 to F-A9-AUTH-7, tests SSO-T-auth-633 to 641).

## Findings of round 8

Persona driven review, see `personas.md` (persona x goal table with the backing tests, findings F-A8-AUTH-1 to F-A8-AUTH-7, tests SSO-T-auth-625 to 632).

## Findings of round 7

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A6-AUTH-10 | high | The case-insensitive unique email index could not be created on a database with case-duplicate emails; startup now detects them, creates the index only when none exist, reports counts and the admin API resolves them | SSO-T-auth-617, 620 |
| F-A6-AUTH-8 | medium | Registration code stored in clear in Redis | SSO-T-auth-618 |
| F-A6-AUTH-9 | medium | OTP and recovery codes could be accepted twice by two nodes at the same instant | SSO-T-auth-619 |
| F-A7-AUTH-1 | medium | SMS OTP codes and push challenges written to the log by the default transport | SSO-T-auth-621 |
| F-A7-AUTH-2 | low | Dev-only switches accepted under a prod profile | SSO-T-auth-622 |
| F-A7-AUTH-3 | low | Reset mail link not encoded, mail sent to the typed address | SSO-T-auth-623 |
| F-A7-AUTH-4 | low | Database exception messages (with emails) logged verbatim | SSO-T-auth-624 |

Details and the email migration behaviour: `identity-lifecycle.md` sections 2 to 5.

## Findings of round 6

Data and identity lifecycle review, see `identity-lifecycle.md` (transition table, collections, findings F-A6-AUTH-1 to F-A6-AUTH-7, tests SSO-T-auth-609 to 616).

## Findings of round 5

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A5-AUTH-1 | medium | The platform tenant `default` and tenants configured in properties fell back to FREE quotas without a stored record (SCIM 429, 60 API requests per minute); plan not configurable | SSO-T-auth-601 |
| F-A5-AUTH-2 | low | Registration and password reset requests wrote no audit event | SSO-T-auth-602, SSO-T-auth-603 |
| F-A5-AUTH-3 | medium | An LDAP-adopted account kept its local password: password login without the directory client, and password step-up, accepted it after the directory password changed or the account was removed | SSO-T-auth-604 |

## Findings of round 4

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A3-5 | high | Legacy RBAC escalation (O1): users:update and roles:update could grant roles or authorities the caller lacked, own roles included; no audit | SSO-T-auth-593 |
| F-A3-6 | medium | Step-up checked freshness only (O2): password or email OTP stepped up a TOTP or passkey user | SSO-T-auth-594 |
| F-A3-7 | low | `/api/roles/switch` answered 404 (O3); repaired with a context-preserving token | SSO-T-auth-595 |
| F-A3-8 | medium | Tenant quotas `authRequestsPerMinute` and `scimOpsPerDay` reported but not enforced (O4) | SSO-T-auth-596 |
| F-A4-AUTH-1 | medium | 2.0 refresh exchange minted a session-less access token with a fresh `auth_time`, passing `@RequiresStepUp` | SSO-T-auth-597 |
| F-A4-AUTH-2 | medium | A SCIM tenant token could set the password, email or status of, or erase, a provisioned account later granted admin authorities | SSO-T-auth-600 |
| F-A4-AUTH-3 | low | Audit verify, export and checkpoint did not require access to the default tenant | SSO-T-auth-598 |
| F-A4-AUTH-4 | low | Account creation by email confirmation and passkey rename were not audited; `/logout/device` accepted impersonation tokens | SSO-T-auth-599 |

## Findings of round 3

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A3-1 | high | Magic link sign-in offered email OTP as the second factor: mailbox access alone passed the MFA of a TOTP or passkey user | SSO-T-auth-588 |
| F-A3-2 | medium | Client policy MFA requirement not enforced on OIDC authorize: a single-factor SSO session got a code for that client | SSO-T-auth-589, SSO-T-auth-592 |
| F-A3-3 | medium | Impersonation did not require access to the default tenant although users are platform scope | SSO-T-auth-590 |
| F-A3-4 | low | Legacy `AuthController` routes were also served at the root path outside the security chain (login responses without `no-store` and the API headers) | SSO-T-auth-591 |

## Observations for the owner

- O1 to O4 of round 3 are resolved by F-A3-5, F-A3-6, F-A3-7 and F-A3-8.
- O5. Client `allowedOrigins` of every tenant get credentialed CORS on `B/**` (needed by publishable-key sign-in from tenant apps). Auth cookies are `SameSite=Lax`, so a cross-site tenant origin never receives the user's cookies; only same-site origins should be registered by tenants.
- O6. Unknown tenants (API tokens issued programmatically for a tenant id that is neither in `sso_tenants` nor referenced in properties) get `naqqa.sso.tenants.unknown-plan` (FREE), so SCIM is refused for them (`scimOpsPerDay` 0). `default` and configured tenants keep a working plan (F-A5-AUTH-1).
- O7 (resolved in confirmation round 5). The per-client `idle-timeout`, `absolute-timeout`, `refresh-lifetime` and `step-up-max-age` were bound but never read (`@RequiresStepUp`, sessions and refresh families use the global values). A session is shared by every client of the SSO, so a per-client session lifetime has no single owner; the four keys were removed from `SsoProperties.Client` and the onboarding guide. Session, refresh and step-up lifetimes are global only (`naqqa.sso.session.*`, `naqqa.sso.mfa.step-up-max-age`); SSO-T-auth-771 fails if a client lifetime is bound again.
