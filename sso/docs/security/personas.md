# naqqa-auth attacker personas (P-ATTACK round 8)

Threat-model review of `auth/src/main/java` on branch `feat/naqqa-sso`, done from the attacker's side: each persona below has a goal, the control that stops it, and the test that proves it. Tests are in `auth/src/test/java`; ids are `SSO-T-auth-<n>`. Rows marked with a finding id were open at the start of round 8 and are fixed (section 2).

## 1. Persona x goal

| Persona | Goal | Result | Control | Test |
|---------|------|--------|---------|------|
| 1 Anonymous | Enumerate accounts by OTP, magic link, forgot-password, passkey options | blocked | identical 202 bodies, async mail, fake passkey descriptors, dummy hash on unknown login | `SsoFlowsTest.emailOtpLogin` (414), `AttackRound1AuthTest.forgotPasswordDoesNotBlockOnDelivery` (569) |
| 1 Anonymous | Enumerate by `/register` | residual | 409 `EMAIL_IN_USE` kept for the legacy registration flow (same decision as OMY F-A8-R); per IP and per address windows | `AttackRound2AuthTest.accountMailIsRateLimited` (582) |
| 1 Anonymous | Brute force password | blocked | throttle per account and IP, known-device lease, risk engine | `SsoFlowsTest.riskStepUp` (424) |
| 1 Anonymous | Brute force TOTP, recovery, OTP from many IPs | blocked | per account failure cap whatever the IP, per account lease | `AttackRound6AuthTest.secondFactorGuessingIsCappedPerAccount` (610), `AttackRound7AuthTest.codesAreSingleUseAcrossNodes` (619) |
| 1 Anonymous | Brute force confirmation code, reset token, magic link | blocked | 5 attempts then the record dies, 122-bit token hashed at rest, single use | `AttackRound1AuthTest.confirmationCodeAttemptCap` (570), `AttackRound7AuthTest.registrationCodeIsKeyedHashAtRest` (618), `AttackRound6AuthTest.passwordResetTokenIsHashedAtRest` (615), `SsoFlowsTest.magicLink` (413) |
| 1 Anonymous | Mail or SMS cost abuse to one destination | blocked (F-A8-AUTH-7) | per IP window, resend cooldown, `max-sends-per-hour` per destination, daily cap | `AttackRound2AuthTest.accountMailIsRateLimited` (582), `AttackRound8AuthTest.otpSendsArePerHour` (631) |
| 1 Anonymous | Expensive unauthenticated work (SAML parsing, malformed input) | blocked | size and depth caps, strict parser, JSON 400 | `SamlIdpEndpointsTest.malformed` (326) |
| 2 Password only | Sign in past TOTP | blocked | MFA forced by an enrolled strong factor, mfaToken single use, TOTP replay refused | `SsoFlowsTest.mfaTokenSingleUseAndTotpReplay` (411) |
| 2 Password only | Push fatigue: flood the victim with approvals | blocked (F-A8-AUTH-5) | per account challenge window (`rate-limits.mfa-verify`), number matching | `AttackRound8AuthTest.mfaChallengesAreCapped` (629) |
| 2 Password and an old weak session | Approve own push to get a strong session | blocked (F-A8-AUTH-6) | approving needs the strength of the strongest enrolled factor | `AttackRound8AuthTest.pushApprovalNeedsStrongSession` (630) |
| 3 Mailbox only | Magic link past TOTP | blocked | email OTP never the second factor of a magic link | `AttackRound3AuthTest.magicLinkSecondFactorIsNotTheMailbox` (588) |
| 3 Mailbox only | Password reset, then password plus email OTP past TOTP | blocked (F-A8-AUTH-1) | email OTP is not a second factor for an account with TOTP or a passkey | `AttackRound8AuthTest.resetByMailDoesNotPassMfa` (625) |
| 3 Mailbox only | Step up with email OTP on a stolen weak session | blocked | step-up factor at least as strong as the strongest enrolled | `AttackRound4AuthTest.stepUpFactorStrength` (594) |
| 4 Phone or SIM only | SMS sign-in past TOTP | blocked | SMS and email OTP never the second factor of a TOTP user's SMS sign-in | `AttackRound8AuthTest.simAloneDoesNotPassMfa` (632) |
| 4 Password or mailbox plus SIM | Password, magic link or email OTP sign-in, then SMS OTP past TOTP or a passkey | blocked (F-A9-AUTH-1, round 9) | SMS OTP is not a second factor for an account with TOTP or a passkey | `AttackRound9AuthTest.simIsNotASecondFactorOfStrongAccounts` (634), `assuranceInvariant` (633) |
| 4 Phone or SIM only | Read SMS codes from logs | blocked | default transport never logs codes or numbers | `AttackRound7AuthTest.transportDoesNotLogCodes` (621) |
| 5 Stolen cookie or token | Change factors, enroll, link, export, erase | blocked after 10 minutes or with a weaker factor | `@RequiresStepUp`, factor strength | `AttackRound1AuthTest.credentialEnrollmentNeedsStepUp`, `SsoFlowsTest.stepUp` (415), `SsoFlowsTest.accountLinking` (419) |
| 5 Stolen cookie or token | Cross-site use of a cookie | blocked | double submit CSRF bound to the session | `SsoFlowsTest.csrfDoubleSubmit` (417) |
| 5 Stolen refresh token | Fresh authentication or reuse | blocked | rotation, reuse revokes the family, 2.0 refresh never fresh | `OidcProviderFlowsTest.refreshRotation` (505), `AttackRound4AuthTest.legacyRefreshIsNotFreshAuthentication` |
| 5 Stolen session | Persistence after the victim's session revoke, reset or factor removal | blocked (offline grants: see residuals) | session guard on every request, revoke all on credential change | `OidcProviderFlowsTest.deviceAndSessionRevokeEndOidcSessions`, `SsoFindingsTest.resetRevokesEverything`, `SsoFindingsTest.factorRemovalRevokesOthers`, `OidcProviderFlowsTest.credentialChangesRevokeOfflineGrants` |
| 6 Malicious OIDC RP | Redeem another client's code or refresh token, reuse PAR | blocked | client binding, single use | `OidcProviderFlowsTest.refreshRotation` (505), `OidcProviderFlowsTest.par` (508) |
| 6 Malicious OIDC RP | Introspect or revoke other clients' tokens | blocked | tenant and client checks | `OidcProviderFlowsTest.clientCredentials` (506) |
| 6 Malicious OIDC RP | Use an ID token as a naqqa-auth access token | blocked | issuer, `token_use`, separate key ring | `OidcProviderFlowsTest.idTokenNotAccepted` (512) |
| 6 Malicious OIDC RP | SSRF through back-channel logout | blocked | admin validation plus resolve-and-pin `SafeHttp` | `OidcProviderFlowsTest.logoutBackchannel` (509) |
| 6 Malicious OIDC RP | Log users out or redirect through logout | blocked | confirmation bound to the browser | `OidcProviderFlowsTest.logoutConfirm` (510) |
| 6 Malicious OIDC RP | Get a code from a single-factor session for an MFA client | blocked | client MFA policy raises `minAcr` | `AttackRound3OidcMfaTest.clientMfaPolicyAppliesToOidc` (589), `AttackRound3OidcMfaTest.clientMfaRolesApplyToOidc` (592) |
| 7 Malicious SAML SP | Unsigned request, tampered RelayState, foreign ACS | blocked | signature over the exact query, registered ACS only | `SamlIdpEndpointsTest.signedAccepted` (322), `SamlIdpEndpointsTest.unsignedSloRejected` (324) |
| 7 Malicious SAML SP | Sign out any user by email NameID | blocked (F-A8-AUTH-3) | SLO ends only sessions that signed in to that SP | `SamlIdpEndpointsTest.sloEndsOnlyParticipatingSessions` (627) |
| 7 Malicious SAML SP | Assertion from a revoked session | blocked | session guard | `SamlIdpEndpointsTest.revokedSessionNoAssertion` (325) |
| 8 Tenant A admin | Read or change tenant B resources | blocked | `SsoAdminScope`, 404 across tenants | `SsoAdminApiTest.tenantsAndIdor` |
| 8 Tenant A admin | Reach platform users, keys, audit | blocked | `default` access required | `AttackRound1AdminTest.keysNeedDefaultTenant`, `AttackRound3AuthTest.impersonationNeedsDefaultTenant` (590) |
| 8 Tenant A admin | Credentialed CORS on the admin API from a tenant origin | blocked | admin CORS uses configured origins only | `AttackRound1AdminTest.tenantOriginsNotTrustedOnAdminApi` |
| 8 Tenant A admin | Exceed plan quotas, use tokens of a suspended tenant | blocked (F-A8-AUTH-4 for the legacy token route) | quota under the tenant lease, writable tenant | `AttackRound4AuthTest.tenantQuotasEnforced`, `AttackRound1AdminTest.suspendedTenantTokensRejected`, `AttackRound8AuthTest.legacyApiTokenRouteAppliesQuota` (628) |
| 8 Tenant A admin | Point the tenant's SAML IdP at another company's (or a public) email domain to adopt or sign in to its accounts | blocked (F-SP-1) | allowed and authoritative domains, JIT, role mapping and assurance trust are platform-operator fields; one authoritative IdP per domain | `SamlIdpAdminTest.adminApi` (677) |
| 8 Tenant A admin | Swap the certificate or entity id of an IdP the platform granted domains or trust, to sign in as its users | blocked (F-A12-AUTH-2) | identity fields platform only once granted | `SamlIdpAdminTest.identityFieldsLockedAfterPlatformGrant` (682) |
| 8 Tenant A admin | Delete an IdP, or wait for the platform to revoke its domains, recreate it with an own entity id and certificate and inherit its links | blocked (F-A13-AUTH-1, F-A13-AUTH-6) | delete platform only once granted; removing the IdP or its last grant removes its links; linked sign-ins need an allowed domain | `AttackRound13AuthTest.idpReplacementDoesNotInheritLinks` (685), `AttackRound13AuthTest.regrantAfterAnchorSwapDoesNotReviveLinks` (691) |
| 8 Tenant A admin | Grant administrative roles through the IdP group mapping | blocked (F-SP-2) | `ROLE_PROTECTED` at configuration, filtered at sign-in | `SamlSpEndpointsTest.groupRoles` (675), `SamlIdpAdminTest.adminApi` (677) |
| 8 Tenant A admin | Escalate through legacy RBAC | blocked | subset rule, not self | `AttackRound4AuthTest.legacyRbacCannotEscalate` (593) |
| 12 Hostile or compromised tenant IdP | Sign in to an account with TOTP or a passkey by asserting its email | blocked | adoption only of verified class 1 or 2 accounts without admin authorities; linked accounts still need their local second factor (amr `fed`) unless the platform marked the IdP trusted for assurance | `SamlSpEndpointsTest.adoption` (672), `SamlSpEndpointsTest.mfaAccountsNeedTheLocalSecondFactor` (673) |
| 12 Hostile or compromised tenant IdP | Claim MFA in the AuthnContext to skip the local second factor | blocked | upstream AuthnContext ignored unless `trustedForAssurance` (platform only) | `SamlSpEndpointsTest.mfaAccountsNeedTheLocalSecondFactor` (673) |
| 13 Network or web attacker against SAML login | Replay a response, post another browser's response (login CSRF), forge InResponseTo, wrap a signed assertion, change audience or recipient, use a removed certificate | blocked | browser-bound single-use request id, Mongo replay cache, core `SamlSp` checks (one assertion per document, signature over the consumed element, configured certificates only) | `SamlSpEndpointsTest` (664 to 670), `SocialCallbackCorsTest.samlAcsCrossSite` (678) |
| 13 Network or web attacker against SAML login | Pre-register a victim's address unverified, wait for the IdP to adopt it | blocked (F-SP-3) | unverified local accounts are never adopted | `SamlSpEndpointsTest.adoption` (672) |
| 12 Hostile or compromised tenant IdP | JIT an unverified account in a non-authoritative domain and keep it after the owner claims the address | blocked (F-A12-AUTH-1) | such a link stops once the email is verified elsewhere | `SamlSpEndpointsTest.jitUnverifiedThenClaimed` (680) |
| 12 Hostile or compromised tenant IdP | Sign in to a linked account that became administrative | blocked (F-A12-AUTH-3) | administrative accounts never sign in through a tenant IdP | `SamlSpEndpointsTest.linkedAccountPromotedToAdmin` (681) |
| 13 Network or web attacker against SAML login | Force a link of the attacker's IdP identity to a victim session, or reuse a link intent | blocked (F-SP-5) | step-up, CSRF, intent single use and bound to session, flow bound to the browser, ACS needs the live session | `SamlSpEndpointsTest.linkWithStepUp` (679) |
| 9 Support operator | Impersonate an admin or chain impersonation | blocked | target protection, impersonated sessions cannot mutate | `AttackRound1AuthTest.impersonationOfPrivilegedAccountsDenied` (563), `SsoFlowsTest.impersonation` |
| 9 Support operator | Keep access after being disabled or reset | blocked | operator revocation ends impersonation sessions | `AttackRound6AuthTest.operatorRevocationEndsImpersonation` (612) |
| 9 Support operator | Act without step-up or audit | blocked | step-up on start, `impersonation.*` audit | `SsoAdminApiTest.stepUp` (549) |
| 10 SCIM client | Take over or provision into another tenant | blocked | account tenants, tenant isolation | `ScimAccountsTest.tenantIsolation` (533) |
| 10 SCIM client | Change, deactivate or erase an admin account | blocked | protected targets | `AttackRound4ScimTest.scimCannotTakeOverProtectedAccount` (600) |
| 10 SCIM client | Erase other users' data by email | blocked | erasure by subject | `AttackRound6AuthTest.erasureKeepsOtherSubjectsDocuments` (611) |
| 10 SCIM client | Weak passwords, verified email by assertion | blocked | password policy, accounts start unverified | `AttackRound2AuthTest.adminAndScimPasswordsFollowPolicy` (587), `AttackRound2AuthTest.scimAccountsUnverified` (579) |
| 11 Hostile LDAP directory | Adopt an admin account through a forged `mail` | blocked (F-A8-AUTH-2) | no adoption of accounts with admin authorities | `AttackRound8AuthTest.directoryDoesNotAdoptAdminAccounts` (626) |
| 11 Hostile LDAP directory | Grant an administrative role through a group mapping | blocked (F-A13-AUTH-4) | administrative roles filtered from the mapping, as for SAML | `AttackRound13AuthTest.directoryNeverGrantsAdministrativeRoles` (688) |
| 11 Hostile LDAP directory | Adopt an unverified account, two entries on one account | blocked | verified accounts only, one entry per account | `DirectoryLoginTest.jitProvisioning` (540), `AttackRound6AuthTest.parallelDirectoryAdoptionMapsOneEntry` (614) |
| 11 Hostile LDAP directory | Choose the directory policy from the request | blocked | directory-managed accounts sign in only through their directory | `AttackRound5AuthTest.directoryManagedAccountIgnoresCallerClient` (604) |

## 2. Findings of round 8

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-A8-AUTH-1 | high | Mailbox alone passed the TOTP or passkey MFA: reset the password by mail, sign in with it, choose email OTP as the second factor | SSO-T-auth-625 (588 updated) |
| F-A8-AUTH-2 | medium | A directory entry whose `mail` named an account with admin authorities adopted it: local password dropped, roles replaced by the directory roles, session issued when no stronger factor | SSO-T-auth-626 |
| F-A8-AUTH-3 | low | A registered SAML SP could end every session of any user with a signed LogoutRequest naming the email | SSO-T-auth-627 (323 updated) |
| F-A8-AUTH-4 | low | Legacy `B/admin/api-tokens` issue skipped the tenant token quota; rotation worked in a suspended tenant | SSO-T-auth-628 |
| F-A8-AUTH-5 | low | `/mfa/challenge` throttle never recorded anything: one mfaToken created unlimited push approvals and passkey challenges | SSO-T-auth-629 |
| F-A8-AUTH-6 | low | Push approval from any own session: an old password-only session plus the password completed the MFA of a TOTP user | SSO-T-auth-630 |
| F-A8-AUTH-7 | low | `max-sends-per-hour` of email and SMS OTP was applied as a daily cap (x24) | SSO-T-auth-631 |

Round 9 states the same review as a formal property over factor classes and attacker capabilities, see `assurance-model.md`.

## 3. Residuals and observations

- An account whose only factors are the password and its mailbox is fully reachable from the mailbox (password reset). MFA by email OTP adds nothing for it; enrolling TOTP or a passkey removes email OTP from its second factors.
- `offline_access` grants survive "revoke other sessions" (standard OIDC behaviour); password reset, factor removal, disable and erasure revoke them.
- Legacy RBAC and SSO admin routes protect targets holding `SSO_*` authorities; a `users:delete` holder can still disable or erase an account with other `users:*` or `roles:*` authorities (7.2 contract; impersonation is stricter).
- SAML assertions carry `email`, `fullName` and `roles` whatever NameID format the SP asks for.
- SAML SP: with `allowUnsolicited` on, an IdP-initiated response has no browser binding, so whoever holds a valid response can sign a browser in to that account (login CSRF). Off by default; documented in `onboarding/saml-sp.md`.
- SAML SP: an account with TOTP or a passkey is never linked by email; its owner links it from the security settings through the step-up link flow (F-SP-5, SSO-T-auth-679).

## 4. SAML SP findings (F-E11)

| Id | Severity | Title | Test |
|----|----------|-------|------|
| F-SP-1 | high | A tenant admin could declare its IdP authoritative for any email domain and adopt or sign in to accounts outside the tenant | SSO-T-auth-677, 672 |
| F-SP-2 | medium | IdP group to role mapping could grant administrative roles | SSO-T-auth-675, 677 |
| F-SP-3 | medium | Adoption of an unverified local account by email (pre-account takeover) | SSO-T-auth-672 |
| F-SP-4 | low | SAML request ids and replay keys shared one namespace across tenants | SSO-T-auth-668 |
| F-SP-5 | medium | Accounts with TOTP or a passkey had no way to link a tenant IdP; now a step-up link flow from the own session, single-use intent bound to session and browser, audited and notified | SSO-T-auth-679 |
| F-A12-AUTH-1 | medium | A JIT account in a non-authoritative domain stayed linked to the IdP after the mailbox owner claimed it (pre-account takeover) | SSO-T-auth-680 |
| F-A12-AUTH-2 | medium | A tenant admin could swap entity id, certificates, email attribute or NameID format of an IdP the platform had granted domains or assurance trust | SSO-T-auth-682 |
| F-A12-AUTH-3 | low | A linked account that later received an administrative authority still signed in through the tenant IdP | SSO-T-auth-681 |
| F-A12-AUTH-4 | low | Credential-change revocation of OIDC grants wrote no `grant:` marker, so a concurrent refresh could save the grant back as live and issue an access token | SSO-T-auth-683 |
| F-A12-AUTH-5 | low | Admin unlock did not clear the HOTP confirm and resync throttles | SSO-T-auth-684 |
| F-A13-AUTH-1..7 | medium, low | Federation round: see `federation.md` section 2 | SSO-T-auth-685 to 692 |
- Accounts still on a legacy BCrypt hash answer at a different speed from unknown addresses until their first sign-in rehashes them.
