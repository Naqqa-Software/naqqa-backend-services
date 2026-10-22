# Go-live checklist

Start in a test tenant with `sso_*_test_*` tokens and a staging issuer, run the full flow, then repeat in live.

## Keys and secrets

- [ ] `NAQQA_SSO_PEPPER` set to at least 32 random bytes, stored in a secret manager, backed up. Losing it invalidates password hashes and tokens.
- [ ] `NAQQA_SSO_SEALING_KEY` (32 bytes) and `NAQQA_SSO_SEALING_KEY_ID` set. Keep old keys available until re-sealed after rotation.
- [ ] `NAQQA_SSO_AUDIT_KEY` and `NAQQA_SSO_SIGNING_KEY` set. Publish JWKS, plan a rotation schedule and rehearse it once.
- [ ] No secret appears in properties files, images or logs.
- [ ] `naqqa.sso.dev-mode` is unset or `false` (the startup log has no `DEVELOPMENT MODE` warning).

## Identity settings

- [ ] `issuer`, `rp-id` and `allowed-origins` are final production values (rp-id is permanent for passkeys).
- [ ] OIDC redirect URIs are exact, HTTPS, per environment. PKCE on.
- [ ] MFA for administrators: `mfa.required-for-admin` stays `true` (default) and `mfa.admin-roles` / `mfa.admin-authorities` list every administrative role and authority of the app; an enrollable factor stays enabled (`totp.enabled` and `recovery.enabled` are `true` by default, or passkeys), otherwise the restriction cannot apply and a startup warning is logged. An administrator without a factor signs in to a restricted session (403 `MFA_ENROLLMENT_REQUIRED` outside the enrollment routes) until a first factor is enrolled; the app routes `mfaEnrollmentRequired`, `#mfaEnrollment=1` and that 403 to the security settings, and lists its profile route in `mfa.enrollment-allowed-routes` and `mfa.enrollment-profile-routes` (both default `GET /api/profile/me`, the latter adds `mfaEnrollmentRequired: true` to the profile answer) (F-CONF-16).
- [ ] Administrator second channel (F-CONF-19): email OTP, SMS OTP, push and magic link never satisfy an administrator's MFA. An administrator with TOTP, HOTP or WebAuthn is offered strong factors only; one without a strong factor must first verify an email or SMS code when that channel is enabled for the client and the account has the address or number, then lands in the restricted enrollment session; push is never offered to administrators. If neither email OTP nor SMS OTP is enabled, the restricted session follows the password alone, so the password alone can enroll the first factor: either keep email OTP (or SMS OTP) enabled for the admin client, or provision administrators' first factor out of band and accept that residual explicitly. Apps handle `mfa_required` with `email_otp` or `sms_otp` followed by `mfaEnrollmentRequired: true` on `/mfa/verify`.
- [ ] Passwords: `min-length` 10 or more recommended; breached-password policy decided.

## Web hardening

- [ ] Cookies: `session.cookie-secure=true`, `cookie-same-site=Lax` (or `Strict` for same-site apps), HttpOnly, behind HTTPS with HSTS.
- [ ] CSP: no inline script, `connect-src` limited to the API and issuer, `frame-ancestors 'none'` on login pages, `form-action` limited to known hosts.
- [ ] CORS limited to `allowed-origins`.

## Operations

- [ ] Rate limits reviewed (`rate-limits.*`); Redis is highly available and monitored.
- [ ] Alerts on login failure spikes, lockouts, token refresh reuse, 5xx on `/api/auth`, audit chain verification failures (`/api/auth/admin/audit/verify`).
- [ ] Audit export and checkpoint scheduled; retention aligned with `docs/legal/retention-and-erasure.md`.
- [ ] Email and SMS providers verified with real delivery, sender domains authenticated (SPF, DKIM).
- [ ] Plan quotas and metering sink configured; soft limit alerts go to a human.
- [ ] Backups of Mongo tested by restore.
- [ ] Privacy notice, terms and DPA published (`docs/legal`).

## Release integrity (owner item)

- [ ] Run `node scripts/sbom.mjs` and archive `dist-sbom/*.cdx.json` with each release.
- [ ] Signed releases are an owner item: the owner must create and guard the signing keys (GPG for Maven artifacts, a signing identity for tags) and wire them into CI. Not implemented yet.

## Cutover

1. Test tenant passes login, MFA, passkey, magic link, logout, SCIM and OIDC client flows.
2. Create live tenant, rotate every test credential out, issue `live` tokens.
3. Enable for staff first, then a small percentage, then everyone, watching the alerts above.
