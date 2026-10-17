# GDPR and Moldova Law 133/2011 - compliance notes for naqqa SSO

Status: engineering draft. Every point marked "Owner/counsel: verify" needs confirmation by qualified counsel. This document describes what the code stores (verified in `sso/src/main/java/com/naqqa/sso/**` and `auth/src/main/java/com/naqqa/auth/sso/**`) and is not legal advice.

## 1. Roles

| Party | Role for end-user data | Notes |
|-------|------------------------|-------|
| Tenant (customer using the SSO via API tokens or the embedded `naqqa-auth` module) | Controller | Decides purposes of sign-in and which methods are enabled (`naqqa.sso.*`, per-client policy). |
| naqqa (when it hosts the SSO) | Processor | Acts on tenant instructions under the DPA (`dpa-template.md`). |
| naqqa (own apps, e.g. naqqa-os) | Controller | Same data, own users. |
| Email, SMS, push transport provider | Sub-processor of the tenant or of naqqa | The transport is chosen by the tenant (`Transport` interface, PLAN D8). |
| Social login providers (Google, Apple, Microsoft, GitHub, Facebook) | Independent controllers | The user authenticates at the provider; naqqa receives the subject id and verified email. |

Owner/counsel: verify the role split when naqqa operates the service for a tenant versus when the tenant self-hosts the library.

## 2. Special categories (GDPR Art. 9) - biometrics

Fingerprint and Face ID sign-in uses WebAuthn platform authenticators with `userVerification=required` (PLAN D4, `passkey.require-user-verification=true`, `biometric` method). The biometric check happens only on the user's device inside the operating system or secure element. The server receives a public key, a credential id, a signature and flags (user verified, backup eligible and backup state). naqqa never receives, stores or processes a fingerprint, face image, template or any biometric measurement. Therefore naqqa does not process biometric data for the purpose of uniquely identifying a person and Art. 9 does not apply to naqqa's processing. The user's own device and operating system vendor handle the biometric matching under their own terms.

Stored for each passkey (collection `sso_webauthn_credentials`): credential id, user handle, COSE public key, algorithm, sign counter, uvInitialized, backupEligible, backupState, transports, AAGUID (authenticator model identifier, not user specific biometric data), attestation format/type/trusted flag, createdAt, lastUsedAt, cloneDetected. The label the user gives the passkey is held in `sso_webauthn_meta`.

Owner/counsel: verify that regulators do not treat the `uvInitialized` flag or AAGUID as biometric-related data. Our analysis: they are device capability metadata.

## 3. Data inventory

Locations: the primary database is MongoDB (collections named below). `users` is the account collection of `naqqa-auth`. Retention values are code defaults from `SsoProperties` (`naqqa.sso.*`); see `retention-and-erasure.md` for the full schedule.

| Field / record | Purpose | Retention | Location |
|----------------|---------|-----------|----------|
| Email | Identifier, contact, account recovery, magic link / OTP delivery | Life of account | `users` |
| Full name, birth date (optional), phone number (optional, SMS OTP) | Profile, SMS OTP | Life of account | `users` |
| Password hash: PBKDF2-HMAC-SHA512, 210000 iterations, 16-byte salt, HMAC-SHA256 pepper pre-hash (legacy BCrypt rehashed at login) | Password sign-in | Life of account | `users.password` |
| Social subject ids (`googleId`, `facebookId`, `appleId`, and linked identities) | Social login, account linking (verified email only) | Until unlinked or account erased | `users`, `sso_linked_identities` |
| Roles, sub roles, enabled / blocked flags | Authorization | Life of account | `users` |
| TOTP secret, sealed AES-256-GCM with key id, nonce, AAD bound to tenant, user and purpose; algorithm, digits, step, window, confirmed flags | Second factor | Until factor removed or account erased | `sso_totp_credentials` |
| Last accepted TOTP step | Replay prevention | 1 day (TTL) | `sso_totp_steps` |
| Recovery code records: per-code salt, PBKDF2-HMAC-SHA256 hash, HMAC lookup id, used flag, failure counter | Account recovery | Until regenerated, factor removed or account erased | `sso_recovery_sets` |
| WebAuthn credential (see section 2) | Passkey, biometric (on-device), security key | Until deleted by user or account erased | `sso_webauthn_credentials`, `sso_webauthn_meta` |
| WebAuthn challenge (with userName, display name, binding) | Ceremony integrity | 5 minutes (`passkey.challenge-ttl`, TTL index) | `sso_webauthn_challenges` |
| OTP record: HMAC of destination (not the plain address), HMAC of code, purpose, attempts, consumed | Email and SMS one-time codes | 10 min email, 5 min SMS (TTL index adds 1 hour after expiry) | `sso_otp` |
| OTP send log: HMAC of destination, time | Resend and hourly cap | 2 days (TTL) | `sso_otp_sends` |
| Magic link record: SHA-256 of token, user id, relative redirect, browser binding hash, used | Passwordless sign-in | 15 min plus 1 hour TTL slack | `sso_magic_links` |
| Push approval: user id, number, status | Push approve second factor | 2 min plus 1 hour purge | `sso_push_approvals` |
| Session: SHA-256 of session id, user id, tenant, client ids, device id, IP address cut to /24 (IPv4) or /48 (IPv6), user-agent hash, amr, acr, auth time, created, last seen | Session management, session list, revocation | Idle 8 h, absolute 30 d enforced at read; TTL index deletes the row 1 hour after the absolute timeout | `sso_sessions` |
| Session meta: coarse device label (browser and OS family), impersonation data (impersonator id and email, reason, expiry) | Session list display, support impersonation audit | Same as session | `sso_session_meta` |
| Refresh token: SHA-256 of token, family id, user id, client id, created, family expiry, rotated flag | Session continuity | `session.refresh-lifetime` 30 d, TTL +1 hour | `sso_refresh_tokens`, `sso_refresh_revoked_families` |
| Legacy refresh binding (device id, client type, session id, amr, acr, auth time, rememberMe, expiry) | Legacy-compatible refresh | Until expiry; hourly cleanup (`TokenCleanupService`) | `refresh_tokens` (JPA/Mongo entity of naqqa-auth) |
| Device record (device id, client type, last role) | Device sessions, "logout this device" | Life of account | entity `UserDeviceEntity` |
| Risk profile: known device hashes, known network buckets (/24, /48 hashed prefix), last login time | Adaptive authentication (only when `risk.enabled=true`) | TTL 180 days after last login (`risk.profile-retention`) | `sso_risk_profiles` |
| Rate limit and lockout counters (keys are hashes of account / IP) | Abuse protection | 1 day (TTL) | `sso_ratelimit` |
| Audit event: time, tenant, actor and target (user ids as keyed pseudonyms), action, outcome, IP address cut to /24 or /48, attributes (values cut at 256 chars), prevHash, hash, key id, HMAC signature | Security audit, incident response, evidence | 365 days by default (`audit.retention`), then purged behind a signed anchor; see `retention-and-erasure.md` | `sso_audit`, `sso_audit_checkpoints`, `sso_audit_anchors` |
| API token record: kind, env, name, scopes, IP allowlist, PBKDF2-SHA256 hash over HMAC(pepper, secret), previous hash, failure counters | Tenant API authentication | Until revoked / tenant ends | `sso_api_tokens` |
| SCIM user and group resources (attributes pushed by the tenant: userName, name, emails, active, groups, etc.) | Provisioning from the tenant IdP | Until deleted via SCIM or tenant offboarding | `sso_scim_users`, `sso_scim_groups` |
| SAML pending requests, replay cache | SAML integrity | Short TTL (`saml.pending-ttl` 10 min) | `sso_saml_pending`, `sso_saml_replay` |
| Cookies in the browser | See `privacy-notice.en.md` | See section 8 below | Browser |

Not stored: plain passwords, plain TOTP secrets, plain recovery codes, plain OTP codes or destinations, plain magic link tokens, plain refresh tokens, plain API token secrets, biometric data of any kind.

## 4. Lawful bases (GDPR Art. 6)

| Processing activity | Lawful basis | Note |
|---------------------|--------------|------|
| Account creation and password / passkey / OTP / magic link sign-in | Art. 6(1)(b) contract (providing the service the user asked for) | The tenant is the controller. |
| Session and refresh token handling, cookies `access_token`, `refresh_token`, `sso_session`, `XSRF-TOKEN` | Art. 6(1)(b) and 6(1)(f); strictly necessary, no consent required under ePrivacy | Owner/counsel: verify. |
| Second factor (TOTP, recovery codes, push, security keys) | Art. 6(1)(b) and 6(1)(f) security of processing (Art. 32) | Tenant policy can make it mandatory (`mfa.required-for-all`). |
| Remember this device (`sso_mfa_rd`, 30 d default) | Art. 6(1)(a) consent, given by ticking the box | Off by default (`mfa.remember-device=false`). |
| Adaptive risk scoring (device, network, geo) | Art. 6(1)(f) legitimate interest in fraud and account takeover prevention | Off by default. Owner/counsel: verify balancing test and whether Art. 22 applies (a deny decision is automated; step-up is the normal outcome; offer human review through the tenant). |
| Audit log | Art. 6(1)(f) legitimate interest and 6(1)(c) legal obligation where the tenant is subject to security-logging duties | |
| Rate limiting, lockout | Art. 6(1)(f) | |
| Emails and SMS carrying codes and links | Art. 6(1)(b) | Transactional only. No marketing. |
| Social login | Art. 6(1)(b) at the moment the user chooses the provider | |
| SCIM provisioning | Tenant's lawful basis as controller (employment or contract); naqqa as processor | |
| Support impersonation | Art. 6(1)(f) with tenant authorization, fully audited, time limited | |
| Breach handling and legal claims | Art. 6(1)(c) and 6(1)(f) | |

## 5. Data subject rights mapped to endpoints

Base path `${auth.api.base-path:/api/auth}` (default `/api/auth`). The tenant (controller) receives the request; naqqa assists under the DPA.

| Right | Available now | Gap |
|-------|---------------|-----|
| Access (Art. 15) | `GET /api/auth/account/export` (fresh step-up) returns all personal data as JSON; also `GET /sessions`, `GET /mfa/factors`, `GET /webauthn/credentials`, `GET /account/links`. | Secrets (hashes, sealed values, public keys) are withheld and listed. |
| Rectification (Art. 16) | Profile update through the host application (e.g. `/api/admin/users`); password via `/change-password` and `/reset-password`; SCIM `PUT` / `PATCH /scim/v2/Users/{id}` for tenant-provisioned data. | Self-service profile edit is the host app's. |
| Erasure (Art. 17) | `DELETE /api/auth/account` (fresh step-up plus password or passkey; accounts without either need only the step-up), admin `DELETE /api/admin/users/{userId}`, both through `AccountErasureService`; per-factor deletes remain. | Removes all SSO data for the user, revokes sessions and tokens, pseudonymizes audit references, emails a confirmation. Backups rotate per `retention-and-erasure.md`. |
| Restriction (Art. 18) | `PATCH /api/admin/users/{userId}/disable` (account blocked, no sign-in), plus session revoke. | |
| Portability (Art. 20) | Same JSON export as access. SCIM `GET /scim/v2/Users/{id}` gives a standard representation for provisioned users. | Self-service download available. |
| Objection (Art. 21) | Handled by the tenant; for risk scoring the tenant can disable `risk.enabled`. | |
| Automated decisions (Art. 22) | Risk engine returns allow, step-up or deny. | Owner/counsel: verify. |

Response deadline: one month (Art. 12(3)); Law 133/2011 sets its own deadline for answering data subject requests. Owner/counsel: verify the Moldovan deadline.

## 6. Moldova Law 133/2011 on personal data protection

Summary of the obligations to implement. Owner/counsel: verify each against the current text of the law (Law 133 of 8 July 2011, as amended, including the amendments aligning with GDPR if enacted) and the NCPDP (Centrul National pentru Protectia Datelor cu Caracter Personal) guidance.

- Controller and processor: the controller determines purposes and means and carries the responsibility; the processor acts under a written contract with security obligations. `dpa-template.md` covers this.
- Principles: lawfulness, purpose limitation, data minimization, accuracy, storage limitation, security. These map to sections 3, 4 and `retention-and-erasure.md`.
- Consent and other legal grounds: the law lists consent and contract / legal obligation / legitimate interest style grounds. Our grounds in section 4 follow the same logic. Owner/counsel: verify wording.
- Data subject rights: information, access, intervention (rectification, blocking, erasure), objection, and not to be subject to individual automated decisions. Section 5 covers them.
- Registration / notification with the NCPDP: historically controllers had to notify processing and register the evidence of personal data. Owner/counsel: verify whether notification or registration is currently required for the tenants and for naqqa, and who files (controller). Do not assume an exemption.
- Security measures: the law requires organizational and technical measures; the NCPDP requirements on security of personal data apply (government decision on minimum security requirements). Mapping in section 7.
- Cross-border transfer: transfer to states that ensure adequate protection is allowed; other transfers need a legal basis, notification to the NCPDP, or safeguards. Owner/counsel: verify the current adequacy list and the status of EU / EEA states (treated as adequate by Moldovan law, verify).
- Breach notification: Section 8.

Cross-border notes for naqqa SSO: data goes abroad when (a) the tenant chooses an email or SMS provider outside Moldova / EEA, (b) the user chooses a social login provider, (c) hosting is outside Moldova. Choose hosting region in the order form. Transfers to the US need a mechanism (EU-US Data Privacy Framework certification of the provider, or SCCs). Owner/counsel: verify.

## 7. Security measures (Art. 32 GDPR, Law 133 security obligations)

| Measure | Control in code |
|---------|-----------------|
| Password protection | PBKDF2-HMAC-SHA512, 210000 iterations, per-user salt, pepper from environment `NAQQA_SSO_PEPPER`, breached-password check, policy min 8 / max 128. |
| Secrets at rest | TOTP secrets AES-256-GCM, key id, 96-bit random nonce, AAD binding, per-key message counter cap (`sso_secretbox_counters`), key from `NAQQA_SSO_SEALING_KEY`. |
| Tokens at rest | Refresh tokens, magic links, session ids: SHA-256; OTP and destinations: HMAC-SHA256 with pepper; API tokens: PBKDF2-SHA256 over HMAC(pepper, secret); recovery codes: PBKDF2-SHA256 with salt and HMAC lookup id. |
| Authentication strength | TOTP, passkeys with required user verification, security keys, step-up (`mfa.step-up-max-age` 10 min), adaptive risk. |
| Session security | Opaque sessions, idle 8 h and absolute 30 d, device binding, refresh rotation with family revocation on reuse, CSRF token, cookies HttpOnly (except XSRF-TOKEN), Secure, SameSite=Lax. |
| Abuse protection | Rate limits per login / OTP / magic link / MFA / token, progressive lockout (30 s base, 1 h max), OTP max attempts 5 and hourly send cap, enumeration-safe 202 answers. |
| Integrity and accountability | Hash-chained, HMAC-signed audit log with periodic signed checkpoints (default every 5 minutes), verify endpoint. |
| Protocol hardening | Strict JSON, JOSE rejects `none` and header-supplied keys, PKCE S256 only, exact redirect URI, SAML parser without DTD / entities, SSRF guard for JWKS fetch. |
| Access control | Admin endpoints require authorities (e.g. `SSO_AUDIT`), API tokens scoped with IP allowlists and rotation grace. |
| Availability | Rate limiter stores are shared in the database (multi-instance), TTL cleanup. Backups are the operator's duty. |

## 8. Personal data breach process

1. Detect: audit verify failure, anomaly alerts, reports by tenants or users. Record the time of awareness.
2. Contain: rotate affected keys (`NAQQA_SSO_PEPPER`, sealing key, signing key), revoke sessions and refresh families (`SsoCredentialRevoker`), revoke API tokens, force password reset.
3. Assess: scope using the audit log, categories and number of data subjects, risk to rights.
4. Notify:
   - Processor to controller (tenant): without undue delay, target within 24 hours of awareness (DPA clause).
   - Controller to supervisory authority: within 72 hours of awareness (GDPR Art. 33), unless unlikely to result in risk. For Moldova, notify the NCPDP in the form and time required by Law 133/2011 and its regulations. Owner/counsel: verify the Moldovan deadline and form.
   - To data subjects without undue delay when high risk (GDPR Art. 34).
5. Document every breach in a register (facts, effects, remedial action), Art. 33(5).
6. Post-incident: key rotation, lessons learned, evidence export from the audit log (see `retention-and-erasure.md`).

## 9. DPIA trigger analysis (GDPR Art. 35)

| Criterion (WP248) | Present? |
|-------------------|----------|
| Evaluation or scoring | Partial: risk scoring when `risk.enabled=true`. |
| Automated decision with legal or similar effect | Possible (deny). Owner/counsel: verify. |
| Systematic monitoring | Limited: session and audit logs. |
| Sensitive data | No. Biometrics stay on device (section 2). |
| Large scale | Depends on the tenant. |
| Matching or combining datasets | Account linking by verified email only. |
| Vulnerable subjects | Depends on the tenant. |
| Innovative technology | Passkeys are standard WebAuthn. |
| Prevents exercising a right or using a service | Lockout slows, never hard-blocks a correct second factor from a known device. |

Conclusion: a DPIA is not mandatory for the SSO core with default settings. A DPIA is recommended for a tenant that enables risk scoring at large scale, processes children or employees at scale, or combines SSO data with other profiling. Owner/counsel: verify.

## 10. Open items for the owner

- Counsel review of this document, the DPA, the ToS and the privacy notices.
- NCPDP notification or registration status.
- Appoint a contact point (DPO if required) and fill the placeholders.
