# Privacy notice - sign-in and account security

Status: template for tenants. Owner/counsel: verify. Replace [placeholders].

This notice explains how [COMPANY NAME] ("we") uses your data when you sign in. Our identity service is run by naqqa as our processor.

## What we keep

- Account data: your email, name, and, if you give them, phone number and birth date.
- Sign-in data: a protected form of your password (we never store the password itself), and data for the extra security you turn on.
- Session data: when and from which device and IP address you signed in, a device identifier, and a fingerprint of your browser's user-agent text, so you can see and end your sessions.
- Security log: a record of important security events (sign-ins, failures, changes) with the IP address, kept to protect your account.

## Ways to sign in

- Password, or a one-time code or link sent to your email or phone. Codes and links work once and expire in minutes.
- Authenticator app (TOTP) and recovery codes. We keep the app secret encrypted and the recovery codes only as protected hashes.
- Passkeys, fingerprint and Face ID. When you use your fingerprint or face, your phone or computer checks it. Your fingerprint or face never leaves your device and we never receive or store it. We only keep a public key that cannot be used to rebuild your biometrics, plus the name you give the passkey and when you used it.
- Security keys, push approval in an app, and sign-in with Google, Apple, Microsoft, GitHub or Facebook. With social sign-in the provider tells us your identifier and verified email; the provider has its own privacy notice.

## Why, and the legal basis

To let you sign in and use the service (contract), to keep accounts safe, detect fraud and prevent abuse (legitimate interest), to remember your device if you ask (your consent), and to meet legal duties.

## Cookies

We use only cookies that are strictly necessary for sign-in. They do not track you and do not need a consent banner.

| Cookie | Purpose | Lifetime |
|--------|---------|----------|
| access_token | Proves you are signed in | Short (about 15 minutes) |
| refresh_token | Keeps you signed in without retyping | Up to 30 days |
| sso_session | Your single sign-on session | Up to 30 days, idle 8 hours |
| XSRF-TOKEN | Protects against forged requests | Same as session |
| sso_device | Recognizes your device for security | Up to 400 days |
| sso_ml_bind, sso_wa_bind | Binds a magic link or passkey step to your browser | Minutes |
| sso_mfa_rd | Remembers your device for two-step checks, only if you tick "remember this device" | 30 days |

## How long we keep it

Codes and links: minutes (records cleaned within about an hour). Sessions: until they expire or you sign out (up to 30 days). Account data: while your account exists. Security log: [RETENTION, e.g. 12 months]. After you delete your account we remove your sign-in data, subject to legal obligations and backup rotation.

## Who receives data

Our hosting provider, and the email, SMS or push provider we selected to deliver your codes: [PROVIDERS]. Data may be processed in [COUNTRIES]; transfers outside the EEA / Moldova use [SAFEGUARDS].

## Your rights

You can ask for access, correction, deletion, restriction, a copy of your data (portability), and object to processing. In your account security page you can list and end sessions, remove authenticator apps, remove passkeys and unlink social accounts. To use other rights write to [PRIVACY CONTACT]. You can complain to the supervisory authority: in Moldova the National Centre for Personal Data Protection (NCPDP), in the EU your national authority.

## Contact

[COMPANY NAME], [ADDRESS], [PRIVACY CONTACT EMAIL], [DPO IF ANY].
