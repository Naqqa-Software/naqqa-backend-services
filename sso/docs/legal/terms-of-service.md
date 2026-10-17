# Terms of Service - naqqa SSO

Status: draft. Owner/counsel: verify. Placeholders in [square brackets].

These Terms govern the use of the naqqa SSO service ("Service") provided by [NAQQA LEGAL ENTITY] ("naqqa") to the customer ("you", "Customer"). By creating a tenant or an API token you accept them.

## 1. The Service

naqqa SSO provides authentication and identity features: password, magic link, email and SMS codes, TOTP, recovery codes, passkeys (including fingerprint and face unlock through the device), security keys, push approval, social login, OIDC, SAML, SCIM, sessions, audit log, delivered through an HTTP API and libraries. Features available depend on your plan and on the methods you enable.

## 2. Accounts and API tokens

2.1 Tokens come as public keys `sso_pk_...` and secret keys `sso_sk_...`. A secret key is a credential for your server.
2.2 You must keep secret keys secret. Never put a secret key in a browser, mobile app, public repository, log, or client-side bundle. Only public keys may be used in browser code.
2.3 Store secrets in a secret manager or environment variables. Restrict tokens by scope and IP allowlist where possible. Rotate tokens regularly (the rotation grace period is [24 hours] by default) and immediately after suspected exposure.
2.4 You are responsible for all activity under your tokens until you revoke them. Tell us at [SECURITY CONTACT] if a token leaks. We may lock or revoke a token we believe is compromised (tokens also lock automatically after repeated failures).
2.5 You are responsible for your administrators, their authorities and any support impersonation you permit.

## 3. Your data and roles

You are the controller of your end users' personal data and naqqa is your processor, under the Data Processing Agreement. You must have a lawful basis, display a privacy notice to your users (templates provided) and handle their requests. You choose the email, SMS and push providers; their terms and fees apply to you.

## 4. Acceptable use

You must not: attack, probe or overload the Service or other tenants; bypass rate limits, quotas or lockouts; use the Service to send spam, phishing or unsolicited messages; use it to process data of people you have no right to process; reverse engineer security controls in breach of law; resell the Service without agreement; use it for illegal purposes; store in the Service data that it is not designed for. Security testing needs prior written permission. We may suspend the Service for violations, with notice when possible.

## 5. Availability and support

5.1 Target availability: [99.9]% monthly, excluding maintenance announced [48] hours ahead and events outside our control. Service credits: [PLACEHOLDER].
5.2 Support channels and response times: [PLACEHOLDER].
5.3 Rate limits and quotas apply per plan and may be changed with [30] days notice, except for security reasons.
5.4 Methods that depend on third parties (email, SMS, social providers, passkey device support) are outside our availability commitment.

## 6. Security and compliance

naqqa applies the measures listed in the DPA Annex 2. You must apply reasonable security in your own systems, including enabling MFA for administrators and keeping dependencies up to date.

## 7. Fees

[PRICING / PAYMENT / TAXES PLACEHOLDER]

## 8. Intellectual property

naqqa retains all rights in the Service. You receive a non-exclusive, non-transferable right to use it during the term. You keep your data and grant naqqa the right to process it to provide the Service.

## 9. Confidentiality

Each party keeps the other's non-public information confidential for [3] years after the term.

## 10. Warranty disclaimer

The Service is provided "as is" to the extent permitted by law. No authentication system can guarantee that accounts are never compromised. [DISCLAIMER PLACEHOLDER]

## 11. Liability

To the extent permitted by law, each party's total liability under these Terms is limited to [AMOUNT / MULTIPLE OF FEES]. Neither party is liable for indirect or consequential loss, lost profits or lost data, except where law does not allow exclusion (death, personal injury, fraud, wilful misconduct). [LIABILITY PLACEHOLDER]

## 12. Term and termination

Either party may terminate with [30] days notice. We may suspend or terminate immediately for material breach or security risk. On termination your data is returned and deleted as set out in the DPA.

## 13. Changes

We may update these Terms with [30] days notice for material changes.

## 14. Governing law and disputes

Governing law: [LAW, e.g. Republic of Moldova]. Courts: [COURTS]. Contact: [LEGAL CONTACT].
