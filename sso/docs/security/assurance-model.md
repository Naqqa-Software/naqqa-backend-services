# naqqa-auth authentication assurance model (P-ATTACK rounds 9 and 10)

Scope: `auth/src/main/java` on branch `feat/naqqa-sso`. Round 8 found a factor downgrade chain (mailbox, then password reset, then email OTP chosen as the second factor of a TOTP account). This round states authentication assurance as a formal property, enumerates every transition that creates or raises a session, and checks the property with a property-style test over capability sets and account factor sets. Tests are in `auth/src/test/java`; ids are `SSO-T-auth-<n>`.

## 1. Factor classes

Strength is the number the code records per session (`AssuranceEngine.KNOWLEDGE` to `PHISHING_RESISTANT`, stored in `sso_session_meta.authStrength`) and compares with the account class (`AssuranceEngine.accountClass`). Since round 10 every rule of this document is implemented once, in `AssuranceEngine` (section 10).

| Class | Strength | Factors | Notes |
|-------|----------|---------|-------|
| knowledge | 1 | password, LDAP bind, federated sign-in (`fed`) | A first factor only. |
| possession-weak | 2 | email OTP, magic link, SMS OTP | Anyone holding the mailbox or the SIM holds the factor. Push is a weak channel too: its result counts as 3 only because the approving session must already reach the account class (F-A8-AUTH-6), so a push never adds assurance the approver does not have. |
| possession-strong | 3 | TOTP, HOTP (counter based, same `Factor.TOTP` class; F-E13), recovery code, approved push | Recovery codes are issued only through a session of the account class (enrollment or a step-up) and are the declared recovery path (section 5). |
| phishing-resistant | 4 | passkey, security key, platform biometric with user verification | Signature over the origin and challenge; biometric data never leaves the device (D4). A WebAuthn assertion without user verification (security keys by default) is possession only: alone it is a first factor of strength 1, combined with any other factor it gives 4 (F-A10-AUTH-1). |

Account class C = the strongest enrolled factor: 4 with a WebAuthn credential, 3 with active TOTP or HOTP, otherwise 1. A session has strength S = the strongest factor of its latest authentication (sign-in or step-up), raised when the session itself enrolls a stronger factor.

Unverified email (Facebook, F-E19). An account created by Facebook has `email_verified=false`. The mailbox factors stay usable because they prove control of the mailbox: magic link and email OTP sign-in work (strength 2, first factor) and set the flag; a password reset mail works as before and sets a password without flipping the flag. Nothing treats the unverified address as proof for another provider: social auto-link and SAML adoption both require `email_verified=true`, so no factor or sign-in method is added by the address alone. A Facebook link never raises or lowers the class (still `fed`, strength 1).

Upstream assurance. Social and enterprise OIDC providers always yield the fixed amr `fed` (strength 1) and LDAP the amr `pwd`: the `amr`, `acr` and `auth_time` claims of an upstream ID token are never read, so an upstream IdP cannot raise a session or skip the account's second factor. Tested by SSO-T-auth-646. Enterprise SAML login (naqqa as SP, F-E11) uses the same rule through `AssuranceEngine.federatedAmr`: the SAML AuthnContext is ignored and the amr is `fed`, unless a platform operator set `trustedForAssurance` on that tenant's IdP and the AuthnContext is one of its `mfaAuthnContexts`; then the amr is `fed mfa`, which `amrStrength` counts as possession-strong (3, acr 2) only when no local factor amr (`pwd`, `otp`, `sms`, `hwk`, `swk`) is present, so a local second factor after a federated sign-in is never over-counted. On a class 4 account such a session stays below the class, like password plus TOTP. Tested by SSO-T-auth-673.

Session inputs. The engine reads the session strength recorded server side (`sso_session_meta.authStrength`); the amr fallback is used only for a token without a recorded session and under-estimates (TOTP, recovery and email codes all carry `otp`). The first-factor amr of an MFA sign-in comes from the signed `mfaToken`, the methods it offers are checked again against the enrolled factors at `/mfa/verify`, and WebAuthn user verification is the UV flag of the signed authenticator data. No input to the engine comes from the request body.

## 2. Attacker capabilities

| Capability | Grants on its own |
|------------|-------------------|
| mailbox | password reset (a new password, factors kept), magic link and email OTP sign-in (first factor, strength 2), email OTP codes, a provider account with the same verified address |
| phone or SIM | SMS OTP sign-in (first factor, strength 2), SMS codes |
| password | password sign-in (first factor, strength 1), password step-up only on class 1 accounts |
| stolen session | what its recorded strength allows while it lives; step-up needs a factor of class C and auth newer than `mfa.step-up-max-age` |
| stolen refresh token | rotation, reuse revokes the family; `auth_time` and strength are carried over, never refreshed; a legacy 2.0 refresh is never fresh |
| one recovery code | a second factor of class 3, single use, notified on every channel |

## 3. Account MFA policy and the invariant

Policy of an account of class C:

- Sign-in (C >= 3): a first factor plus a second factor of class 3 or more (TOTP, recovery code, push approved from a class C session, passkey), or a passkey with user verification alone, or a remembered device minted at class C. Email and SMS OTP are never second factors of such an account.
- Sensitive operations: fresh authentication and S >= C. This covers enrolling or removing factors, regenerating recovery codes, linking or unlinking a social identity, export, erasure, impersonation, every admin mutation and API token issue (`@RequiresStepUp`).
- Delegation and persistence: S >= C, without the freshness rule, for approving a push, minting a remembered device, and OIDC codes, offline grants and device approvals (the OIDC client store raises `minAcr` to `urn:naqqa:acr:2` for class 3 and `urn:naqqa:acr:3` for class 4 while S < C and no remembered device of the account is presented).
- Changing the email is not offered to the user (SCIM and admin only); changing the password without the mailed reset token is not offered.

Policy MFA (F-CONF-16, fixed). Independently of the class, a second factor is required for every account (`mfa.required-for-all`), for listed roles (`mfa.required-roles`, client `mfa-required-roles`), decided by `mfaRequiredByPolicy`, and by default for every account holding an administrative role or authority (`mfa.required-for-admin=true`, `mfa.admin-roles` default `ADMIN`, `mfa.admin-authorities` default `SSO_ADMIN_*`, `SSO_IMPERSONATE`, `SSO_AUDIT`, `SSO_AUDIT_EXPORT`, `SSO_API_TOKENS` and the `users:`, `roles:`, `sub_roles:` write authorities `create`, `update`, `delete`, `disable`, `enable`; a trailing `*` is a prefix), decided by `administrative`. TOTP and recovery codes are enabled by default when `naqqa.sso.enabled=true` (an operator may disable them). An account covered by the policy is asked one of its enabled and enrolled second factors (the mailbox and the SIM count only for class 1 and 2 accounts). Administrative accounts (F-CONF-19, consistent with OMY F-A24-1): a possession-weak method (email OTP, SMS OTP, push, magic link, `weakChannel`) never satisfies their MFA requirement. With a strong factor (TOTP, HOTP, passkey or security key, with or without user verification) they are offered strong second factors only (TOTP, WebAuthn, recovery code; the password only after a security key without user verification), and email OTP, SMS OTP and push are refused at `/mfa/challenge` and `/mfa/verify` (`acceptsSecondFactor` with the administrative flag). Without a strong factor a weak method is only a second-channel proof before the restricted enrollment session: when email OTP or SMS OTP is enabled and the account has that address or number (or recovery codes remain), the first factor is followed by `mfa_required` with those methods, a remembered device does not skip it, and the verified code ends in the restricted session below, never in a full session. Push is never offered to an administrator (an approver would need an unrestricted session of the same account, which cannot exist before the first strong factor). When no second channel is enabled and available, the restricted session is issued directly after the first factor, as before; residual: then knowledge alone (the password) can enroll the first factor of such an administrator (section 9). An account covered by the policy whose class is below 3 (no TOTP, HOTP or WebAuthn credential, `hasEnrolledFactor`) signs in with a restricted session (`enrollmentRestricted`, when an enrollable factor is enabled for the client or in the global policy, `enrollable`; a caller-chosen client without one never lifts the restriction, F-CONF-20): the body is `{ status: "ok", user, mfaEnrollmentRequired: true }`, the session meta carries the restriction (`restrictSession`, `sessionRestricted`), every access token minted for that `sid` (sign-in, refresh, role switch) carries `mfa_enroll: true`, and `MfaEnrollmentGate` (called by `AccessTokenAuthenticationFilter` through `AccessSessionGuard` for cookie and Bearer tokens alike, deciding through `enrollmentPending`) answers 403 `{ code: "MFA_ENROLLMENT_REQUIRED" }` everywhere except `GET /methods`, `GET /mfa/factors`, `POST /mfa/totp/enroll|confirm`, `POST /mfa/hotp/enroll|confirm`, `POST /webauthn/register/options|verify`, `GET /webauthn/credentials`, `POST /mfa/recovery/regenerate` (only once a factor exists), `POST /step-up`, `POST /logout`, `POST /refresh`, `GET /sessions`, public metadata (`/.well-known/**`, `/oauth2/jwks`, SAML metadata) and `mfa.enrollment-allowed-routes` (default `GET /api/profile/me`). OIDC authorize, consent, resume and device approval refuse a restricted `sso_session` cookie with the same 403 even without an access token; the SAML IdP treats it as no session; admin APIs, impersonation and API token issuance are gated routes. The restriction is computed in `SsoLoginFlow.complete`, independently of the sign-in outcome, so password, LDAP, social, SAML SP, magic link, OTP, `/mfa/verify` and remembered devices all end restricted (a remembered device is never minted for a restricted session); federated success redirects carry `#mfaEnrollment=1`. A refresh re-evaluates the account: a session of an account that gained an administrative authority without a factor becomes restricted, a sessionless (legacy) refresh of such an account is refused (`TOKEN_INVALID`). Confirming the first factor (TOTP or HOTP confirm, WebAuthn registration) upgrades the session in place (`releaseSession`, amr gains the factor and `mfa`, a new access token without `mfa_enroll`), returns recovery codes (WebAuthn registration returns `recoveryCodes` too when it lifted the restriction), audits `mfa.enrollment.complete` and sends the factor-added notice. While the session is restricted the JSON answer of the app profile routes (`naqqa.sso.mfa.enrollment-profile-routes`, default `GET /api/profile/me`, served through the app chain with `AccessTokenAuthenticationFilter`) also carries `mfaEnrollmentRequired: true` (`MfaEnrollmentProfileAdvice`), so an app that loads the profile right after a federated return, which has no login body, detects the restriction. When no enrollable factor is enabled for the client nor globally the sign-in only carries the flag and a startup warning is logged (SSO-T-auth-746, 748 to 756, 759, 765, 769, 770; SSO-T-auth-635 now asserts that a restricted session mints no remembered device or OIDC grant).

Invariant. For an account whose strongest enrolled factor is class C, no sequence of actions available to an attacker who controls only capabilities weaker than C reaches an authenticated session satisfying that account's MFA policy, nor enrolls or removes factors, changes the email or the password without the reset, erases the account, or creates persistent access (API tokens, OIDC offline grants, remembered devices, passkeys).

Declared exception: one recovery code plus a first factor signs in (section 5). The resulting session has strength 3: on a class 3 account it is the account class; on a class 4 account it stays below the class, so none of the sensitive operations above are reachable from it.

Corollaries enforced in code:

1. A second factor is never weaker than possession-strong on an account of class 3 or 4 (`AssuranceEngine.acceptsSecondFactor`, checked when the methods are offered and again at `/mfa/verify` and `/mfa/challenge`). The password is a second factor only after a WebAuthn assertion without user verification.
2. Raising the account class (first TOTP on a class 1 account, first passkey on a class 1 or 3 account) revokes every artifact minted under the weaker class: other sessions, refresh families, OIDC sessions and grants including offline ones, remembered devices, pending links and MFA tokens. Only the enrolling session stays (`SsoCredentialRevoker.revoke`, reason `factor_enrolled`).
3. Persistence is minted only at class C: remembered devices (`MfaController.verify`), OIDC grants (`OidcProviderConfiguration.withPolicyAcr`).
4. The mailbox alone never adds a sign-in method to an account of class 3 or 4: social auto-link is refused (`LINK_REQUIRES_LOGIN`), linking needs a session of class C (step-up).

## 4. Transitions

| Transition | Minimum capability | Result on a class 3 or 4 account | Control | Test |
|------------|--------------------|----------------------------------|---------|------|
| Password sign-in | password | MFA required, methods TOTP, passkey, recovery, push | `AssuranceEngine.signIn` | 411, 625, 634, 644 |
| Administrator sign-in (F-CONF-19) | first factor | with a strong factor: strong second factors only (no email OTP, SMS OTP or push); without one: an enabled email or SMS code (second channel) and then the restricted enrollment session, or the restricted session directly when no channel is available | `AssuranceEngine.signIn` and `acceptsSecondFactor` with the administrative flag | 644, 760 to 764 |
| LDAP sign-in | directory password | as password; directory-managed accounts never use the local hash | `DirectoryLogin` | 540, 604 |
| Magic link | mailbox | MFA required, email and SMS OTP excluded | browser binding, single use | 413, 588, 634 |
| Email OTP sign-in | mailbox | MFA required, email and SMS OTP excluded | per destination caps | 414, 633 |
| SMS OTP sign-in | SIM | MFA required, email and SMS OTP excluded | per destination caps | 632, 633 |
| Social sign-in | provider account | MFA required; no auto-link into a class 3 or 4 account | `LinkingPolicy` with `AssuranceEngine.mayAutoLink` | 525, 637, 644 |
| SAML SP sign-in | tenant IdP assertion | MFA required unless the IdP is trusted for assurance and asserts MFA; no adoption of a class 3 or 4, administrative or unverified account | `SamlSpAccounts` with `AssuranceEngine.mayAutoLink`, `federatedAmr` | 672, 673 |
| SAML SP link (F-SP-5) | own session | step-up at the account's class (`StepUpInterceptor` with `AssuranceEngine.satisfied`) before the intent; the link adds no strength, later sign-ins through it are `fed` | `SamlSpController.startLink`, `SamlSpAccounts.link` | 679 |
| Social linking | session of class C | link bound to the starting session | `@RequiresStepUp`, intent | 419, 522 |
| Facebook sign-in and link (F-E19) | provider account, or own session with step-up for the link | an unverified email never grants more: creation only for a new address, no adoption by email, the link only from a session of class C; the new account is class 1 and the sign-in is `fed` (strength 1) | `SocialLoginController` (`unverifiedEmailProvider`), `AccountLinkService.completeOwned` | 740 to 744 |
| Passkey sign-in | the authenticator | user verification: done; without it MFA required (password allowed as the second factor), refused when the account has no second factor | `AssuranceEngine.signIn` | 420, 642, 644, 645 |
| MFA method choice | as offered | only methods in the signed mfaToken | `MfaController.verify` | 411, 625 |
| Push approval | session of class C | approves one challenge with number matching | `PushController.respond` | 630 |
| Recovery code | one code + first factor | session of strength 3, notified | `RecoveryCodes`, `SsoSecurityNotices` | 633, 636, 638 |
| Step-up | factor of class C | `auth_time` refreshed, strength recorded | `StepUpController` | 594 |
| Remembered device | MFA at class C | skips the second factor with a first factor until TTL or revocation | `MfaController.verify`, revoke-others, class raise | 400, 635, 636, 640 |
| Factor enrollment | fresh session of class C | class raise revokes everything else | `MfaController.confirm`, `MfaController.hotpConfirm`, `WebAuthnController.registerVerify` | 635 |
| Factor removal | fresh session of class C | revoke all but the current session (residual F-B4), notified | `@RequiresStepUp` | 402, 513, 638 |
| Password reset | mailbox | new password, factors kept, revoke all, notified; a pending reset token ends with every revoke all (F-CONF-27) | reset token hashed, single use | 400, 625, 638, 772 |
| Email change | SCIM or admin | verification flag cleared; a SCIM change revokes all (F-CONF-24, `identity-lifecycle.md` section 6) | protected targets | 579, 600, 617, 772 |
| SCIM provisioned account | SCIM token | starts unverified; password set revokes all | `ScimAccountSync` | 530, 579, 600 |
| OIDC `prompt=none` | SSO session | code only when the session reaches `minAcr` (client policy, role policy, account class) | `withPolicyAcr`, `AuthorizeFlow` | 589, 592, 633, 636 |
| OIDC `max_age`, `acr_values` | SSO session | re-login or step-up interaction | `AuthorizeFlow` | 503, 504 |
| OIDC offline grant | session of class C | refresh family survives revoke-others, ends on revoke all | `withPolicyAcr`, `SsoCredentialRevoker` | 513, 633, 635 |
| Device flow approval | session reaching `minAcr` | grant to the polling device | `DeviceFlow.decide` checks `client.minAcr()` | 507, 635 |
| SAML AuthnContext | SSO session | AuthnContext from the session amr; a higher requested class sends the user to step-up | `SamlIdpController.decide` | 322, 325 |
| Impersonation | operator, step-up | access token only, `amr` `imp`, strength 0, cannot mutate | `ImpersonationController` | 418, 563, 612 |
| Refresh exchange | refresh token | same session, `auth_time` and strength kept | `RefreshTokenManager` | 505, 597 |
| Admin factor reset | `SSO_ADMIN_USERS`, step-up | all factors removed, revoke all, notified | `UserAdminController.resetFactors` | 557, 638 |

## 5. Recovery

- Lost TOTP (class 3): a recovery code signs in and steps up (strength 3 = C); the user removes the old TOTP, enrolls a new one and regenerates codes. Every step is audited and notified.
- Lost passkey (class 4): a recovery code signs in at strength 3. That session reads the account but cannot manage factors, mint remembered devices or get OIDC codes (step-up to acr 3 is required). Restoring the account is admin assisted: `POST /api/sso/admin/users/{id}/factors/reset` needs `SSO_ADMIN_USERS`, access to the default tenant, fresh authentication, refuses protected targets without platform scope, writes `admin.user.factors_reset` and `credentials.revoke_all`, and sends a security notice to the email and the phone of the account. The user then signs in with the password and enrolls again.
- Password reset by mail never removes factors, so it never lowers the class.

Security notices (`SsoSecurityNotices`, template `security_notice`, event in `vars.event`) go to the account email and, when stored, its phone, through the configured `Transport`: `recovery_code_used`, `factor_added`, `factor_removed` (also when a social or SAML identity is unlinked), `recovery_regenerated`, `factors_reset`, `password_reset`, `account_linked` (a social, SAML or directory identity added to an existing account, F-A13-AUTH-5). They carry no code, link or address of the actor.

## 6. Notification abuse

| Sender | Per IP | Per destination or account | Cost bound |
|--------|--------|----------------------------|------------|
| `/otp/request` email and SMS | `rate-limits.otp` | resend cooldown, `max-sends-per-hour` per hashed destination, daily cap | codes go only to addresses and numbers stored on an account |
| `/mfa/challenge` email, SMS | per mfaToken caller | `mfa-challenge:<user>` window (`rate-limits.mfa-verify`) plus the OTP caps above | as above |
| `/mfa/challenge` push and passkey | | `mfa-challenge:<user>` window, number matching | no external cost |
| `/magic-link/request` | `rate-limits.magic-link` | `rate-limits.account-mail` window per pseudonymous address, silent (F-A9-AUTH-6) | known enabled accounts only |
| `/forgot-password`, `/register` | `rate-limits.account-mail` | same window per address | |
| Security notices | | one per security event of the account | events need a valid factor or an admin |

Tenant-wide request quotas (`authRequestsPerMinute`) apply to every sign-in call through `SsoLoginFlow.context`. SMS spend is bounded per number (hourly and daily caps); there is no global SMS budget (residual).

## 7. Property test

`AttackRound9AuthTest.assuranceInvariant` (SSO-T-auth-633) loops over the account factor sets {TOTP, passkey, TOTP and passkey} (each with a verified email, a phone and recovery codes) and every subset of {mailbox, SIM, password, one recovery code} (48 cases). For each case a fresh account is attacked through every first factor the capabilities allow (password, magic link, email OTP sign-in, SMS sign-in, password reset then password) and every offered second factor the capabilities can satisfy (email OTP, SMS OTP, recovery code). It asserts:

- no route reaches a session without a recovery code;
- with a recovery code and any first factor the route succeeds (recovery stays possible) and notifies email and phone;
- on class 4 accounts the recovery session passes no step-up (password, email, SMS, recovery), gets no OIDC `offline_access` code with `prompt=none` and mints no remembered device;
- on class 3 accounts the recovery session restores factor management.

Targeted regressions: SSO-T-auth-634 to 640 (one per finding), SSO-T-auth-641 (notification caps across IPs and sign-in attempts).

## 8. Findings of round 9

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A9-AUTH-1 | high | Password or mailbox plus the SIM passed the MFA of a TOTP or passkey account: SMS OTP stayed a second factor on password, magic link, email OTP and social sign-in | SSO-T-auth-633, 634 |
| F-A9-AUTH-2 | medium | Enrolling TOTP or a passkey left access minted under the weaker class valid: older sessions, their OIDC offline grants and remembered devices | SSO-T-auth-635 (594, 630 updated) |
| F-A9-AUTH-3 | low | A recovery code session of a passkey account minted a remembered device and obtained OIDC codes and offline grants above its class | SSO-T-auth-633, 636 |
| F-A9-AUTH-4 | low | Social sign-in auto-linked a provider identity to an account with TOTP or a passkey before its MFA | SSO-T-auth-637 (525 updated) |
| F-A9-AUTH-5 | medium | Recovery and factor changes notified nobody | SSO-T-auth-633, 638 |
| F-A9-AUTH-6 | low | Magic-link mails had no per-address cap | SSO-T-auth-639 |
| F-A9-AUTH-7 | low | Signing out the other sessions kept the other remembered devices | SSO-T-auth-640 |

## 9. Residuals

- A passkey account that also has TOTP signs in with password and TOTP (strength 3, acr 2); everything listed as sensitive in section 3 still needs the passkey.
- A remembered device minted at class C skips the second factor (never the first) until its TTL, a revoke all, a revoke-others from another browser, or a class raise.
- Accounts of class 1 are reachable from the mailbox (round 8 residual); email or SMS OTP as their second factor adds no assurance against the mailbox.
- A stolen session that already reaches class C can approve pushes and step up until it ends; that capability equals the account class.
- SAML has no account or client MFA policy of its own: the IdP reports the AuthnContext of the session and the SP decides; a requested higher class sends the user to step-up.
- No global SMS budget; spend is bounded per stored number.
- MFA tokens issued in the same second as a revocation are refused (second granularity of the epoch).
- An administrator without a strong factor on a deployment where neither email OTP nor SMS OTP is enabled for the client (or the account has no address or number for it, and no recovery code) gets the restricted enrollment session from the first factor alone: password-only knowledge can enroll the first strong factor of such an account (F-CONF-19). Keep a second channel enabled, or provision the first factor out of band, where this matters.

## 10. Round 10: one assurance engine

`AssuranceEngine` (`auth/src/main/java/com/naqqa/auth/sso/flow`) is the single implementation of sections 1 to 5. Given the factors enrolled on the account (`Factor`: `PASSWORD`, `EMAIL`, `PHONE`, `TOTP` (TOTP or HOTP), `PASSKEY`, `SECURITY_KEY`, `RECOVERY_CODES`) and what a session or sign-in presented (amr, recorded strength, remembered device), it answers:

| Question | Engine entry | Callers |
|----------|--------------|---------|
| Is a method enabled for the client (first factor, second factor, step-up, WebAuthn kind) | `enabled`, `credentialKindAccepted` | password, OTP, magic link, WebAuthn, MFA enroll and regenerate, step-up |
| After a first factor: complete, ask for a second factor (which ones) or refuse (`Outcome`: `COMPLETE`, possibly flagged `enrollmentRequired`, `SECOND_FACTOR`, `REFUSE`); the first factor alone completes only at level 2 (`firstFactorCompletes`) | `signIn`, `secondFactors`, `acceptsSecondFactor`, `firstFactorCompletes` | `SsoLoginFlow.afterFirstFactor` (password, LDAP, magic link, email and SMS OTP, social, passkey, promotion-server gate), `MfaController.verify` and `challenge` |
| Strength of a session; a WebAuthn assertion without user verification is possession only (`possessionOnly`) | `amrStrength`, `combinedStrength`, `stepUpStrength`, `methodStrength`, `possessionOnly` | `SsoLoginFlow.complete`, `MfaController.verify`, `StepUpController` |
| Policy MFA: the resolved client or global policy requires a second factor for every account or for one of the account roles (`mfa.required-for-all`, `mfa.required-roles`, client `mfa-required-roles`), or the account holds an administrative role or authority (`mfa.required-for-admin`); for an administrator possession-weak methods never satisfy the requirement and serve only as a second channel before the enrollment session (F-CONF-19); see section 3, policy MFA | `mfaRequiredByPolicy`, `administrative`, `weakChannel`, `signIn`, `secondFactors` and `acceptsSecondFactor` with the administrative flag | `SsoLoginFlow.afterFirstFactor` (`administrative`), `complete`, `enrollmentRequired` and `requiredAcr`, `MfaController.verify` and `challenge` |
| Enrollment restriction: the account needs a factor it lacks, the session is restricted until the first enrollment, the gate and refresh decide on it | `enrollmentRestricted`, `hasEnrolledFactor`, `enrollable`, `enrollmentPending`, `sessionRestricted`, `restrictSession`, `releaseSession` | `SsoLoginFlow.complete` and `enrollmentCompleted`, `MfaEnrollmentGate` (`allows`, `onRefresh`), `OidcController`, `SamlIdpController` |
| Enrolled factors, account class and the session record | `enrolled`, `accountClass`, `sessionStrength`, `recordAuthentication` | `SsoLoginFlow`, `MfaController`, `WebAuthnController`, `StepUpController`, `SamlSpAccounts`, `SocialLoginController`, `ImpersonationController` |
| Step-up method acceptable | `stepUpAccepted` | `StepUpController` |
| Sensitive and persistent operations (`Operation`: `MANAGE_FACTORS`, `CHANGE_EMAIL`, `CHANGE_PASSWORD`, `ERASE`, `EXPORT`, `LINK_IDENTITY`, `IMPERSONATE`, `ADMIN`, `API_TOKEN`, `REGISTER_PASSKEY` need a fresh authentication; `APPROVE_PUSH`, `REMEMBER_DEVICE`, `OIDC_GRANT`, `OIDC_OFFLINE_GRANT`, `DEVICE_APPROVAL` do not) | `permits`, `satisfied` (the `StepUpFactorPolicy` of `@RequiresStepUp`) | `StepUpInterceptor`, `PushController`, `MfaController.verify` |
| Account class raised by an enrollment | `factorEnrolled` | `MfaController.confirm`, `MfaController.hotpConfirm`, `WebAuthnController.registerVerify` |
| OIDC `minAcr` for a session, client acr selection, `@RequiresStepUp(acr)`, SAML AuthnContext; levels from amr and acr | `requiredAcr`, `clientAcr`, `clientAcrAtMaximum`, `acrSatisfied`, `reachesLevel`, `acr`, `level`, `levelOfAcr` | `OidcProviderConfiguration.withPolicyAcr` (authorize, consent, device approval), `StepUpInterceptor`, `SamlIdpController.decide`, `SsoSessionManager` |
| Federated amr of a SAML SP sign-in | `federatedAmr` | `SamlSpAccounts.signIn` |
| Social auto-link, erasure confirmation, recovery codes at enrollment, impersonation strength | `mayAutoLink`, `acceptsErasureConfirmation`, `erasureNeedsConfirmation`, `issuesRecoveryCodesOnEnrollment`, `NONE` | social, `AccountController.delete`, `MfaController.confirm`, `ImpersonationController` |

SCIM and admin email or password changes act with a SCIM token or an admin session that passed `@RequiresStepUp`; they do not create sessions.

Tests:

- SSO-T-auth-643 (`AssuranceArchitectureTest`): outside the engine no code derives levels or acr from amr (`StepUp`), reads the client factor switches of `SsoClientPolicy.Policy`, reads enrolled factor state or session strength for a decision (factor listing and removal endpoints excepted), compares second-factor method ids, inline or through a method-id table held in a field (F-CONF-28; factor verification mechanics in `SecondFactors` excepted) or declares a strength rule; and every decision point listed above reaches the engine. Method ids count in any case inside a method that folds case, and keyed lookups (`get`, `getOrDefault`) in literal collections count as comparisons (F-CONF-11).
- SSO-T-auth-644: every subset of the 7 factors (128 accounts) times every subset of the attacker capabilities {password, mailbox, SIM, TOTP device, verified passkey, security key without its PIN, one recovery code} (128) times push on or off, policy MFA off, on, or required by administrative authorities, and social auto-link on or off (196608 worlds). Each world explores to a fixpoint every transition: password, magic link and email OTP, SMS OTP, social, passkey, security key, password reset by mail, every offered second factor, push approved by a session the attacker already reached, every step-up, remembered devices minted and reused. It asserts: weaker capabilities (below min(C, 3)) reach no session; capabilities below C reach no session permitting any `Operation`, no OIDC acr and no remembered device; on class 3 and 4 every session needs a verified passkey or two distinct factors one of them possession-strong or better; for administrators (F-CONF-19), push is never used, no email OTP, SMS OTP or magic link satisfies the MFA of an administrator with a strong factor, the password alone reaches no session of an administrator without a strong factor whose account has a second channel (email, phone or recovery codes) while password plus that channel does, and an administrator without any channel is not locked out; and liveness (a verified passkey reaches 4, password or mailbox plus TOTP reach 3, password plus security key reach 4, a recovery code plus a first factor recovers, a first factor signs in to class 1). The account class of the oracle comes from section 1, not from the engine, and every disagreement fails (F-CONF-13).
- SSO-T-auth-645: a deterministic sample of 40 account and capability pairs through the real endpoints (sign-in, `/mfa/challenge`, `/mfa/verify`, password reset, `/step-up`, `/test/fresh`), asserting the HTTP outcome (session reached, factor management reached) equals the model of 644.
- SSO-T-auth-771 (`ClientPolicyFloorTest`, F-CONF-20 class): every per-client setting a caller can select (`naqqa.sso.clients.<id>.*` and the stored client settings) must be classified as a method switch, an MFA requirement or neutral, and no client lifetime may be bound (O7: session, refresh and step-up lifetimes are global only); for every method switch on and off (properties and stored), every method group off and the client MFA requirements, the sign-in outcome (full session, restricted session, second factor or refusal), the enrollment restriction and the OIDC acr of five account kinds (plain, MFA role, administrator without and with a factor, TOTP) through the client are never weaker than without it. The global and account rules are a floor; a client only tightens.
- Confirmation round 6 mutation check: 602 killed a step-up handler resolving the caller without its own session, an unlink without `@RequiresStepUp` and a CSRF exemption of `revoke-others`; 643 killed method-id tables in fields after F-CONF-28; 644 killed push and a weaker strength accepted for a strong-factor administrator; 771 killed a client lifting the restriction through its own policy; 772 killed the reset token and the MFA token kept by revoke all; 606 killed a client enabling a method the operator disabled; 768 killed a template save of the user after F-CONF-32; 774 killed a logged destination, code, raw exception and an inline logger with an email and IP. Equivalent mutants kept as defense in depth: weak channels for a strong-factor administrator (already below the strength floor), a client replacing `mfa.required-roles` (the global policy is also read), the `B/step-up` strength check (SSO-T-auth-594 refuses it, and the session strength still gates factor management), approved push records kept by revoke all (their MFA token is revoked).

## 11. Findings of round 10

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A10-AUTH-1 | medium | A security key without user verification signed in alone, at strength 4, to an account whose only strong factor is WebAuthn (`mfaEnrollmentRequired` path; default configuration, push disabled) | SSO-T-auth-642, 644, 645 |

## 12. Findings of the confirmation round

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-CONF-3 | low | This document defined class 3 as active TOTP and named only the TOTP and passkey enrollments as class raises, while `AssuranceEngine.enrolled` counts an active HOTP credential as `Factor.TOTP` and `MfaController.hotpConfirm` raises the class (revoke all but the current session); documentation corrected | SSO-T-auth-695 |
| F-CONF-11 | low | SSO-T-auth-643 matched method ids case-sensitively and ignored keyed lookups in literal collections | SSO-T-auth-643 |
| F-CONF-13 | low | SSO-T-auth-644 took the account class of its oracle from the engine, so a misclassified factor passed 644 and 645 | SSO-T-auth-644 |
| F-CONF-15 | low | Section 10 did not name every engine entry (`mfaRequiredByPolicy`, `possessionOnly`, `firstFactorCompletes`, `level`, `levelOfAcr`, `clientAcrAtMaximum`, `enrolled`, `sessionStrength`, `recordAuthentication`) nor the `Factor`, `Operation` and `Outcome` constants; completed | SSO-T-auth-745 |
| F-CONF-16 | medium | Default policy: an administrative account signed in with the password alone and no second factor was enabled on a fresh install; `mfa.required-roles` did not bring an un-enrolled account to MFA. Fixed (owner decision): TOTP and recovery codes on by default, MFA required for administrative accounts, a restricted enrollment-only session until the first factor (section 3, policy MFA) | SSO-T-auth-746, 748 to 756 |
| F-CONF-19 | medium | With email OTP, SMS OTP or push enabled, an administrator without a strong factor satisfied `mfa_required` with a mailbox code (and push stayed offered to administrators with TOTP), so the weak method decided the outcome instead of forcing a strong factor. Fixed (decision consistent with OMY F-A24-1): weak methods never satisfy an administrator's MFA; without a strong factor an enabled email or SMS code is only a second channel before the restricted enrollment session, push is never offered to administrators, with a strong factor only strong factors are offered and accepted (section 3; residual in section 9) | SSO-T-auth-644, 760 to 764 |
| F-CONF-20 | medium | `enrollable` was read from the client policy only, so an administrator without a factor naming a client that enables no enrollable factor (`X-Client-Id`) signed in with the password alone to a full session. Fixed: the restriction applies when the client or the global policy enables an enrollable factor (`SsoLoginFlow.enrollmentRequired`, sign-in and refresh) | SSO-T-auth-765 |
| F-CONF-21 | medium | Verifying the email of an account opened through an unverified link (Facebook, SAML JIT) left the sessions opened before alive, so whoever controlled that link kept access and could link the provider again. Fixed: the first verification (`SsoAccounts.markEmailVerified`) revokes every session, refresh family, OIDC session and grant of the account | SSO-T-auth-766, 743 |
| F-CONF-24 to 26 | low, medium | Control changes that kept credentials: SCIM email change, unlink and IdP link removal, directory adoption; see `identity-lifecycle.md` section 6 | SSO-T-auth-772 |
| F-CONF-27 | low | A pending password reset token survived every revoke all; see `identity-lifecycle.md` section 6 | SSO-T-auth-772 |
| F-CONF-28 | low | SSO-T-auth-643 did not follow a method-id table held in a field (`static Map.of("totp", 3, ...)`, an instance `Set` of weak method ids) to its lookup outside the engine | SSO-T-auth-643 |
| F-CONF-29 | low | A SCIM email change left the reset token and email sign-in code of the previous address; see `identity-lifecycle.md` section 6 | SSO-T-auth-775 |
