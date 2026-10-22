# Retention, erasure and audit admissibility

Status: engineering draft, verified against the code. Owner/counsel: verify legal retention periods. Property keys are under `naqqa.sso.*` (class `SsoProperties`); values are code defaults and can be overridden per deployment, and some per client.

## 1. Retention schedule

"TTL index" means MongoDB removes the document automatically after the stated delay (background job, runs about once a minute, so deletion is not instant). "Read-time" means the code refuses an expired record but the document stays in the database.

| Record (collection) | Setting key and default | Physical deletion |
|---------------------|-------------------------|-------------------|
| WebAuthn challenge (`sso_webauthn_challenges`) | `passkey.challenge-ttl` = 5 min | TTL index on `expiresAt`, immediately at expiry |
| TOTP step (`sso_totp_steps`) | fixed 1 day | TTL index |
| Pending TOTP enrollment | `totp.pending-ttl` = 15 min | At confirm or expiry in the flow |
| Email OTP (`sso_otp`) | `email-otp.ttl` = 10 min, `max-attempts` 5, `resend-interval` 60 s, `max-sends-per-hour` 5 | TTL index, 1 hour after `expiresAt` |
| SMS OTP (`sso_otp`) | `sms-otp.ttl` = 5 min, same caps | TTL index, 1 hour after expiry |
| OTP send log (`sso_otp_sends`) | fixed 2 days | TTL index |
| Magic link (`sso_magic_links`) | `magic-link.ttl` = 15 min, `bind-to-browser` true | TTL index, 1 hour after expiry |
| Push approval (`sso_push_approvals`) | `push.ttl` = 2 min, `max-mismatches` 2 | TTL index on `purgeAt` = expiry + 1 hour |
| MFA token | `mfa.mfa-token-ttl` = 5 min | Stateless / ticket expiry |
| Step-up freshness | `mfa.step-up-max-age` = 10 min | Not stored |
| Remember device cookie | `mfa.remember-device` false, `mfa.remember-device-ttl` = 30 d | Browser cookie expiry |
| Session (`sso_sessions`, `sso_session_meta`) | `session.idle-timeout` = 8 h, `session.absolute-timeout` = 30 d | TTL index on `purgeAt` = created + absolute timeout + 1 hour. Also expired at read time and deleted on logout / revoke. Stored: UA label, SHA-256 of UA, IP cut to /24 (IPv4) or /48 (IPv6) |
| Refresh token (`sso_refresh_tokens`) | `session.refresh-lifetime` = 30 d | TTL index on `familyExpiry` + 1 hour; revoked family markers (`sso_refresh_revoked_families`) TTL + 1 hour |
| Legacy refresh binding (`refresh_tokens`) | token expiry | Hourly job `TokenCleanupService` (cron `0 0 * * * *`) |
| Access token | `session.access-token-ttl` = 15 min | Stateless |
| OIDC access / ID token, authorization code | `oidc-provider.access-token-ttl` 15 min, `id-token-ttl` 15 min, `authorization-code-ttl` 1 min | In-memory / ticket store |
| Device code | `device-code.ttl` = 10 min | Ticket expiry |
| Client credentials token | `client-credentials.access-token-ttl` = 15 min | Stateless |
| SAML pending request | `saml.idp.pending-ttl` = 10 min | `sso_saml_pending` |
| SAML replay cache | `saml.assertion-ttl` = 5 min, `clock-skew` 60 s | `sso_saml_replay` |
| Rate limit and lockout (`sso_ratelimit`) | `rate-limits.*`, lockout base 30 s, max 1 h; row retention fixed 1 day | TTL index |
| Risk profile (`sso_risk_profiles`) | `risk.enabled` false, `risk.profile-retention` = 180 d | TTL index on `purgeAt`, renewed at each login. Networks are stored as /24 or /48 buckets, never full IPs |
| TOTP credential, recovery set, WebAuthn credentials, linked identities, SCIM resources | None | Kept until user or admin deletes, or account erasure |
| API token (`sso_api_tokens`) | `api-tokens.rotation-grace` = 24 h, `max-failed-attempts` 10, `lock-duration` 15 min | Revoked tokens stay as records (hash only). Delete at tenant offboarding |
| Account (`users`) | None | Until erasure |
| Audit log (`sso_audit`, `sso_audit_checkpoints`) | `audit.checkpoint-interval` = PT5M | `audit.retention` = 365 d, `audit.purge-interval` = PT1H | Scheduled purge of the expired prefix; a signed anchor (`sso_audit_anchors`) keeps the chain verifiable. See section 4 |

Audit entries are kept 12 months by default (`naqqa.sso.audit.retention`; raise it to 24 months or more if the tenant needs evidence). Owner/counsel: verify the period.

## 2. Erasure procedure (per data subject)

Trigger: verified request by the data subject or the controller (tenant), or account deletion by an administrator. Deadline: one month from the request (GDPR Art. 12(3)). Owner/counsel: verify the Moldova deadline.

1. Verify identity of the requester (signed-in session with step-up, or controller instruction).
2. Check for legal holds (open incident, dispute, legal retention duty). If any, restrict processing instead: disable the account (`PATCH /api/admin/users/{userId}/disable`) and revoke sessions.
3. Revoke access: call the credential revoker (`SsoCredentialRevoker.revoke`, triggered by any credential change) to end SSO sessions, OIDC sessions, refresh tokens, tickets, MFA tokens, magic links, pending push approvals and pending OTPs.
4. Delete factors and linked data for the user id:
   - `sso_totp_credentials` (`_id` = user id), `sso_recovery_sets` (`_id` = user id)
   - `sso_webauthn_credentials` (field `userId`), `sso_webauthn_meta`
   - `sso_linked_identities`, and the `googleId` / `facebookId` / `appleId` fields in `users`
   - `sso_sessions` and `sso_session_meta` (field `userId`), `sso_refresh_tokens` and legacy `refresh_tokens` (field `userId` / `user`)
   - `sso_risk_profiles`, device records (`UserDeviceEntity`), `sso_scim_users` / group memberships
   - `sso_otp` and `sso_magic_links` for the user (purposes and `userId`); these also expire by TTL.
5. Delete the account. `AccountErasureService` performs steps 3 to 5 in one call: self-service `DELETE /api/auth/account` (fresh step-up plus password or passkey, answers 202 and emails a notice with a reference) and the admin endpoint `DELETE /api/admin/users/{userId}` both use it. It revokes through the credential revoker, deletes the per-user documents above, then sweeps every `sso_*` collection (except audit, rate limit, secret counters, revoked-family markers and the hashed OTP send log) for documents by `userId`, `sub`, email or SCIM user name, removes Redis registration and reset records, and deletes the `users` row. API tokens are tenant scoped, not user owned, so they are not touched. SCIM `DELETE /scim/v2/Users/{id}` removes only the provisioned resource.
6. Audit log: entries are not deleted one by one, because that would break the hash chain. Since this version, user ids in `actor` and `target` are written as a keyed pseudonym (`u~` plus HMAC-SHA256 of the id, key derived from the pepper) and IPs are truncated, so no email or user id remains. Entries written by earlier versions keep the plain id until the retention purge removes them. The erasure is recorded as `account.erasure` with a reference and no personal content.
7. Notify processors / recipients (Art. 19): the tenant's email / SMS providers keep their own logs under their terms.
8. Confirm to the requester in writing. Keep the minimum evidence (request id, date, scope) without personal content.

Export (access and portability): `GET /api/auth/account/export` (fresh step-up required) returns JSON with profile, sessions, passkeys (without public keys), TOTP and recovery status, linked identities, risk profile, known devices, remembered devices, provisioned SCIM resources, other `sso_*` documents about the user and the user's audit events. Hashes, sealed secrets and public keys are withheld and listed under `withheld`.

## 3. Backups

- Backups contain deleted data until they rotate. Keep the rotation at 35 days or less and state it in the DPA.
- Backups are not used for ordinary retrieval. If a restore is required, re-apply the erasure log (list of user ids erased since the backup) before the system goes live again.
- Backup copies of keys (pepper, sealing, audit, signing) are stored separately from database backups. Losing the sealing key makes TOTP secrets unreadable (users re-enroll). Losing the pepper invalidates password and token hashes.

## 4. Audit log: integrity, admissibility, export

### What the log provides

Each entry holds seq, time, tenant, actor, action, target, outcome, IP, attributes, `prevHash`, `hash` (SHA-256 over `seq|prevHash|canonical event`), key id and an HMAC-SHA256 signature over `audit|keyId|seq|hash`. Entries are appended under a lock, so seq is gapless. Every `audit.checkpoint-interval` (default 5 minutes) a checkpoint (seq, head hash, time, key id, HMAC) is written to `sso_audit_checkpoints`. `GET /admin/audit/verify?full=true` (also `POST /api/sso/admin/audit/verify`) checks all checkpoint signatures, the anchored hashes (detects truncation and edits), and every link of the chain; without `full` it verifies from the last checkpoint. `POST /admin/audit/checkpoint` forces a checkpoint. The authority needed is `SSO_AUDIT`.

### Limits to state honestly

- Signatures are HMAC with a symmetric key (`NAQQA_SSO_AUDIT_KEY`). Whoever holds that key could recompute a consistent chain. Evidence is therefore strong against third parties and accidental change, but weaker against a malicious operator unless head hashes are anchored outside the system.
- Time comes from the server system clock (injected `Clock`), not from a trusted timestamp authority. Keep the servers on NTP and record the sync status.
- The checkpoints live in the same database as the log. To strengthen: at least daily, copy the latest checkpoint (seq, head, time, signature) to an independent store (WORM bucket, email to the tenant, or an RFC 3161 timestamp / qualified time stamp). Owner/counsel: verify what Moldovan courts and the eIDAS-style rules accept as electronic evidence; a qualified timestamp is the strongest option.
- Key rotation: set the new key in `NAQQA_SSO_AUDIT_KEY` and the old keys, comma separated, in `NAQQA_SSO_AUDIT_PREVIOUS_KEYS`. At startup a rotation event signed by the old key is appended, and checkpoints and anchors signed by retired keys still verify. Verify reasons `retired_key` and `rotation_mismatch` mean a retired key signed after its rotation. Keep old keys to verify archived exports offline.
- Entries contain pseudonymous data (truncated IP, pseudonymized user id). Keep access to the log restricted and use the retention in section 1.

### Export and verify procedure (incident response, court)

1. Force a fresh checkpoint: `POST /admin/audit/checkpoint`. Note the returned seq, head, time.
2. Run `GET /admin/audit/verify?full=true`. It must return `ok: true`. Save the response and the time of the call.
3. Export the range with `GET /api/sso/admin/audit/export?from=&limit=` (authority `SSO_AUDIT_EXPORT`): JSON lines of entries including prevHash, hash, keyId and signature, followed by a trailer line with the count, the purge anchor and the verify result. A direct dump of `sso_audit`, `sso_audit_checkpoints` and `sso_audit_anchors` also works.
4. Hash the export file (SHA-256), record the hash in the case record, store the file read-only, and preferably timestamp the hash with a trusted timestamp authority.
5. Offline verification: recompute `hash = SHA-256(seq|prevHash|canonical)` for each entry, check `prevHash` equals the previous `hash`, starting from the genesis value (64 zeros) or a trusted earlier anchor, then recompute each HMAC with the audit key id from the key custodian, and compare the last hash with a checkpoint.
6. Chain of custody: who exported, when, with which key custodian, where it is stored, who accessed it.
7. For court: provide the export, the verification output, a technical statement describing the mechanism (this document), clock source evidence, and an expert witness if required. Owner/counsel: verify admissibility rules and requirements for electronic evidence in the relevant jurisdiction.

### Reconciling erasure with the chain

Implemented as follows. Actor and target user ids are pseudonymized at write time, so erasing a user needs no change to stored entries. After `audit.retention` the expired prefix is deleted and replaced by a signed anchor (seq, hash of the last purged entry, time, key id, HMAC) in `sso_audit_anchors`; verification then starts at the anchor and checks that the first remaining entry links to it. Checkpoints inside the purged prefix are deleted. Without the anchor, or with a forged one, verification fails (`anchor_signature_invalid`, `seq_gap`). Export the prefix to the archive before it is purged if it must be kept as evidence. Owner/counsel: verify.

## 5. Findings from the code review (data handling discrepancies)

| Id | Finding |
|----|---------|
| F-G1 | Fixed. Session meta stores a coarse UA label (browser and OS family) instead of the raw User-Agent, the session keeps the SHA-256 of the UA, IPs are truncated, and both collections have TTL indexes on `purgeAt`. Rows created earlier are migrated at startup. |
| F-G2 | Fixed. `AccountErasureService`, `GET /api/auth/account/export`, `DELETE /api/auth/account`; admin delete uses the same service. |
| F-G3 | Fixed. Retention with anchored purge, truncated IPs, pseudonymized ids, HTTP export and verify, injected clock, key ring for rotation. |
| F-G4 | Fixed. TTL index on `purgeAt` (180 days since last login, `risk.profile-retention`). |
| F-G5 | Fixed in the library (key ring and rotation events); auth reads previous keys from `NAQQA_SSO_AUDIT_PREVIOUS_KEYS`. |
| Note | Recovery codes use PBKDF2-HMAC-SHA256 and API tokens PBKDF2-HMAC-SHA256 (not SHA512; SHA512 applies to passwords only). Documents state this. |
