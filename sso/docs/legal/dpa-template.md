# Data Processing Agreement (template)

Status: template. Owner/counsel: verify before use. Placeholders in [square brackets].

This Data Processing Agreement ("DPA") forms part of the agreement ("Agreement") between [TENANT LEGAL NAME], [address], [registration number] (the "Controller") and [NAQQA LEGAL ENTITY], [address], [registration number] ("naqqa" or the "Processor") for the naqqa SSO service.

It applies Regulation (EU) 2016/679 (GDPR) and Moldova Law 133/2011 on personal data protection, as applicable to the Controller.

## 1. Definitions

Terms such as personal data, processing, controller, processor, data subject, personal data breach and supervisory authority have the meaning given in the GDPR. "Services" means the naqqa SSO identity service (authentication, sessions, MFA, passkeys, OIDC, SAML, SCIM, API tokens, audit log). "Sub-processor" means a third party engaged by naqqa to process Controller personal data.

## 2. Roles and instructions

2.1 The Controller determines purposes and means. naqqa processes personal data only on the Controller's documented instructions: this DPA, the Agreement and the configuration the Controller sets (`naqqa.sso.*` properties, per-client policy, admin API calls).
2.2 naqqa informs the Controller if it considers an instruction to infringe data protection law.
2.3 The Controller guarantees it has a lawful basis for the processing and has informed data subjects (naqqa provides privacy notice templates).

## 3. Processing details

See Annex 1.

## 4. Confidentiality

Persons authorized by naqqa to process personal data are bound by confidentiality. Access to production data is limited to staff who need it, and support impersonation is time limited and audited.

## 5. Security

naqqa implements the technical and organizational measures in Annex 2 and keeps them at a level appropriate to the risk (GDPR Art. 32). naqqa may update them provided the overall level of protection is not reduced.

## 6. Sub-processors

6.1 The Controller gives general authorization for the sub-processors in Annex 3.
6.2 naqqa informs the Controller [30] days before adding or replacing a sub-processor; the Controller may object on reasonable data protection grounds within [14] days.
6.3 naqqa imposes on each sub-processor obligations no less protective than this DPA and remains liable for them.
6.4 Message transport (email, SMS, push) is chosen and contracted by the Controller. The Controller's chosen provider is a sub-processor of the Controller, not of naqqa, unless the parties agree otherwise in Annex 3.

## 7. International transfers

naqqa processes personal data in [REGION/COUNTRY]. Transfers outside the EEA / Republic of Moldova only occur with a lawful mechanism (adequacy decision, Standard Contractual Clauses, or equivalent). Owner/counsel: verify.

## 8. Assistance to the Controller

8.1 Data subject requests: naqqa forwards requests it receives within [5] working days and provides tools: factor and session listing and deletion endpoints, account disable and delete, SCIM, database export (Annex 2, part D).
8.2 naqqa assists with DPIAs, prior consultation and security obligations, taking into account the nature of the processing.

## 9. Personal data breach

naqqa notifies the Controller without undue delay and within [24] hours after becoming aware of a breach affecting Controller personal data, with the information available (nature, categories and number of data subjects, likely consequences, measures taken, contact point). naqqa cooperates in containment, key rotation, session revocation and evidence export.

## 10. Audit rights

10.1 naqqa makes available all information needed to demonstrate compliance, including the audit log verification output (`GET /admin/audit/verify`) and its signed checkpoints.
10.2 The Controller (or an independent auditor bound by confidentiality) may audit naqqa once per [12] months, or after a breach, on [30] days written notice, during business hours, without unreasonable disruption, at the Controller's cost. Third-party certifications and reports may satisfy the request where they cover the scope.

## 11. Return and deletion at end of services

11.1 On termination, naqqa at the Controller's choice returns (JSON export) and then deletes all Controller personal data: users, TOTP credentials, recovery sets, passkey records, linked identities, sessions, refresh tokens, OTP and magic link records, risk profiles, SCIM resources, API tokens and audit entries that identify data subjects, within [30] days, except where law requires storage.
11.2 Backups are overwritten within [35] days according to the backup rotation. Deleted data is not restored except for disaster recovery, and any restore is followed by re-applying deletions.
11.3 The audit log hash chain is append-only. Entries are not edited. Deletion at end of services is done by exporting a verified, signed archive for the Controller and then deleting the tenant's entries after a new checkpoint is recorded. Owner/counsel: verify.
11.4 naqqa confirms deletion in writing.

## 12. Liability and term

Liability is governed by the Agreement. [LIABILITY CAP PLACEHOLDER]. This DPA lasts as long as naqqa processes Controller personal data. Governing law: [LAW]. Courts: [COURTS].

Signatures: [Controller name, title, date] / [naqqa name, title, date]

---

## Annex 1 - Processing details

| Item | Description |
|------|-------------|
| Subject matter | Identity and access management for the Controller's applications |
| Duration | Term of the Agreement plus deletion period |
| Nature | Storage, verification, transmission of codes and links through the Controller's transport, logging |
| Purpose | Authenticate users, protect accounts, manage sessions, provision accounts, keep security evidence |
| Data subjects | The Controller's end users, employees, customers, administrators |
| Categories of personal data | Email, name, phone (optional), birth date (optional), password hash, social subject ids, TOTP secret (sealed), recovery code hashes, passkey public keys and credential ids and sign counters and AAGUID, sessions with IP address and user agent, device ids, refresh token hashes, OTP and magic link hashes, risk profile (device hashes, networks, approximate location when enabled), audit events with IP address, SCIM attributes, API token hashes |
| Special categories | None. Biometrics are verified only on the user's device by the platform authenticator; naqqa receives a signature and a public key and stores no biometric template. |
| Frequency | Continuous |

## Annex 2 - Technical and organizational measures

A. Cryptography and secrets
- Passwords: PBKDF2-HMAC-SHA512, 210000 iterations, 16-byte random salt, HMAC-SHA256 pepper held outside the database (`NAQQA_SSO_PEPPER`).
- TOTP secrets: AES-256-GCM, key id, 96-bit random nonce, AAD bound to tenant, user and purpose, per-key message cap, sealing key outside the database.
- Refresh tokens, session ids, magic links: SHA-256 at rest. OTP codes and destinations: HMAC-SHA256. API tokens: PBKDF2-SHA256 over HMAC(pepper, secret), dummy hash for unknown ids. Recovery codes: salted PBKDF2-SHA256 and HMAC lookup id.
- Access tokens: JWS with key rotation (key ring), `alg` fixed by key.

B. Authentication and sessions
- Optional and policy-driven MFA (TOTP, passkeys with required user verification, security keys, push with number matching), step-up, adaptive risk.
- Opaque sessions with idle and absolute timeouts, device binding, rotating refresh tokens with reuse detection and family revocation, CSRF protection, Secure HttpOnly SameSite cookies.
- Rate limiting and progressive lockout; enumeration-safe responses.
- Credential change revokes sessions, refresh tokens, pending OTPs, magic links and push approvals.

C. Logging and integrity
- Hash-chained, HMAC-signed audit log with periodic signed checkpoints and a verify endpoint.

D. Data subject support and deletion
- Per-user factor, passkey, session, and link listing and deletion endpoints; account disable; account deletion; SCIM delete; operator export and erasure procedure (`retention-and-erasure.md`).

E. Organizational
- Least privilege admin authorities, audited impersonation, secret management through environment or KMS, key rotation procedure, incident response and breach register, staff confidentiality and training, backups with rotation. [Add organization-specific items.]

## Annex 3 - Sub-processors

| Sub-processor | Service | Location | Notes |
|---------------|---------|----------|-------|
| [Cloud / hosting provider] | Infrastructure and database hosting | [region] | naqqa |
| [Database service, e.g. MongoDB hosting] | Storage | [region] | naqqa |
| [Email provider chosen by Controller, e.g. SendGrid, SMTP relay] | Delivery of codes and links | [region] | Chosen and contracted by Controller |
| [SMS provider chosen by Controller] | SMS OTP | [region] | Chosen and contracted by Controller. SMS is the weakest second factor. |
| [Push provider chosen by Controller] | Push approval notification | [region] | Chosen by Controller |
| Social login providers selected by the user (Google, Apple, Microsoft, GitHub, Facebook) | Authentication at the provider | Provider | Independent controllers, not sub-processors |
