# SAML onboarding

naqqa speaks SAML 2.0 in both roles:

- Part A, enterprise login: naqqa is the service provider (SP) and a customer's identity provider (Okta, Entra ID, ADFS, Google Workspace, Keycloak) authenticates its employees.
- Part B: naqqa is the IdP for a third-party SAML application.

## Part A. Enterprise login (naqqa as SP)

Off by default. It needs `naqqa.sso.enabled=true`, `naqqa.sso.saml.sp.enabled=true`, an organization (tenant, `POST /api/sso/admin/tenants`) and an enabled IdP for that tenant. Each application can switch it off with `naqqa.sso.clients.<id>.saml-sp=false` or the admin client setting `methods.samlSp`.

### SP settings

```properties
naqqa.sso.saml.sp.enabled=true
naqqa.sso.saml.sp.private-key=${NAQQA_SAML_SP_KEY}
naqqa.sso.saml.sp.certificate=${NAQQA_SAML_SP_CERT}
naqqa.sso.saml.sp.request-ttl=10m
naqqa.sso.saml.sp.success-url=https://app.example.com
naqqa.sso.saml.sp.failure-url=https://app.example.com/login
```

- With a key and certificate the AuthnRequests are signed (RSA-SHA256 or ECDSA-SHA256) and encrypted assertions can be decrypted; the metadata publishes both. Without them requests are unsigned and encryption cannot be required. Key and certificate must be set together and must match (checked at startup).
- `success-url` and `failure-url` default to `frontend.url` and `frontend.url/login`.

### Give the customer's IdP these values

They are shown by `GET /api/sso/admin/tenants/{tenant}/saml-idp` under `sp`.

| Item | Value |
|---|---|
| SP entity id (audience) | `<issuer>/saml2/sp/{tenant}/metadata` |
| ACS URL (HTTP-POST) | `<issuer>/saml2/sp/{tenant}/acs` |
| Metadata | `GET /saml2/sp/{tenant}/metadata` (once the IdP entity id, SSO URL and a certificate are set) |
| NameID | persistent (recommended), emailAddress or unspecified; transient is refused |
| Attributes | an email attribute (default name `email`), optional display name (`name`) and groups (`groups`) |
| Signing | sign the assertion (default) or the response (`requireSignedResponse`) |

### Configure the IdP in naqqa

Admin API (`SSO_ADMIN_CLIENTS`, fresh authentication):

```http
PUT /api/sso/admin/tenants/acme/saml-idp
{
  "enabled": true,
  "name": "Acme Okta",
  "entityId": "http://www.okta.com/exk123",
  "ssoUrl": "https://acme.okta.com/app/naqqa/exk123/sso/saml",
  "signingCertificates": ["-----BEGIN CERTIFICATE-----..."],
  "nameIdFormat": "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",
  "attributes": { "email": "email", "name": "displayName", "groups": "groups" }
}
```

A platform operator (`SSO_ADMIN_TENANTS`) then sets the fields that decide who may sign in and with which rights:

```http
PUT /api/sso/admin/tenants/acme/saml-idp
{
  "allowedDomains": ["acme.com"],
  "authoritativeDomains": ["acme.com"],
  "jitProvisioning": true,
  "groupRoles": { "naqqa-sellers": "SELLER" },
  "defaultRole": "USER",
  "trustedForAssurance": false
}
```

The same settings can be fixed by the operator in properties (`naqqa.sso.saml.sp.tenants.acme.entity-id`, `sso-url`, `signing-certificates[0]`, `allowed-domains[0]`, `authoritative-domains[0]`, `jit-provisioning`, `group-roles.<group>`, `trusted-for-assurance`, ...); such an IdP is read-only in the admin API.

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | false | Sign-in through this IdP is offered. Needs entity id, SSO URL and a certificate. |
| `signingCertificates` | - | 1 to 5 PEM certificates. All verify. Rotation: add the new certificate, let the IdP switch, then remove the old one; a removed certificate stops at once. |
| `allowedDomains` | none | Email domains this IdP may sign in or create. Platform only. |
| `authoritativeDomains` | none | Subset of the allowed domains for which the IdP is the authority: its email counts as verified, and an existing local account with that address may be linked. One IdP per domain. Platform only. |
| `jitProvisioning` | false | Create an account on first sign-in. Platform only. |
| `groupRoles`, `defaultRole` | none | Groups attribute values to naqqa roles, applied on creation and re-synced on later sign-ins. Roles carrying an administrative authority are refused. Platform only. |
| `trustedForAssurance` | false | When false the IdP's AuthnContext is ignored: the session is a federated first factor (amr `fed`) and an account with TOTP or a passkey still completes its local second factor. When true and the AuthnContext is in `mfaAuthnContexts` (default `https://refeds.org/profile/mfa`) the IdP's MFA counts (amr `fed mfa`, strength 3, acr `urn:naqqa:acr:2`). Platform only. |
| `allowUnsolicited` | false | Accept IdP-initiated responses. They have no browser binding, so a user can be signed in to an account chosen by whoever holds a valid response for it (login CSRF); keep it off unless the customer requires it. |
| `requireSignedAssertion`, `requireSignedResponse` | true, false | At least one must be true. |
| `requireEncryptedAssertion` | false | Needs the SP key. |

### How a sign-in is mapped

1. A NameID already linked to an account (`sso_linked_identities`, provider `saml:<tenant>`) signs in that account.
2. Otherwise the email domain must be allowed (`DOMAIN_NOT_ALLOWED`) and an email must be present (`EMAIL_MISSING`).
3. An existing account with that email is linked only when the domain is authoritative, the local email is verified, the account has no TOTP or passkey, holds no administrative authority, is not managed by LDAP and has no other identity of this IdP. Every other case answers `LINK_REQUIRES_LOGIN`: the user signs in with the existing method instead.
4. Without an account, one is created when JIT is on (`SIGNUP_DISABLED` otherwise), email verified only for an authoritative domain.
5. The account's own MFA policy then applies (`#mfaToken=...` on the failure URL, as for social login).

### Frontend

- Button: navigate the browser (no XHR) to `/saml2/sp/{tenant}/login?relay=/after/login&client_id=<app>`; show it when `GET /api/auth/methods` returns `samlSp: true` and the app knows the organization (fixed per app, a subdomain or an "organization id" field).
- The relay must be a relative path starting with one `/`.
- On return handle `?error=<CODE>` and `#mfaToken=..&methods=..&redirect=..` exactly as for social login.

### Security notes

- Requests: HTTP-Redirect, signed when the SP key is set; the request id is stored once per tenant and bound to the `sso_saml_sp` cookie (HttpOnly, Secure, SameSite=None, path of the tenant ACS). The response must answer that id; it is consumed once.
- Replay: assertion ids are kept in `sso_saml_replay` until the assertion expires.
- The ACS POST is the only cross-site naqqa-auth route and is excluded from CORS and CSRF; nothing else under `/saml2/sp/` is.
- Audit: `saml.sp.start`, `saml.sp.acs` (failure reasons), `saml.sp.login`, `saml.sp.signup`, `saml.sp.adopt`, `saml.sp.roles`, `admin.saml_idp.*`.

## Part B. Registering a SAML service provider (naqqa as IdP)

naqqa acts as the SAML IdP when `naqqa.sso.saml.enabled=true` and `naqqa.sso.saml.idp.*` is set.

### Give the SP these values

| Item | Value |
|---|---|
| Metadata | `GET /saml2/idp/metadata` |
| SSO URL | `/saml2/idp/sso` (HTTP-Redirect and HTTP-POST) |
| SLO URL | `/saml2/idp/slo` |
| Entity id | `naqqa.sso.saml.idp.entity-id` |

### Register the SP

```properties
naqqa.sso.saml.idp.entity-id=https://auth.example.com/saml2/idp
naqqa.sso.saml.idp.certificate=${NAQQA_SAML_IDP_CERT}
naqqa.sso.saml.idp.private-key=${NAQQA_SAML_IDP_KEY}
naqqa.sso.saml.idp.service-providers[0].entity-id=https://sp.example.org/metadata
naqqa.sso.saml.idp.service-providers[0].acs-urls[0]=https://sp.example.org/acs
naqqa.sso.saml.idp.service-providers[0].slo-url=https://sp.example.org/slo
naqqa.sso.saml.idp.service-providers[0].signing-certificate=<PEM>
naqqa.sso.saml.idp.service-providers[0].sign-response=true
naqqa.sso.saml.idp.service-providers[0].default-name-id-format=urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress
```

- The ACS URL in the request must match a registered `acs-urls` entry exactly.
- `acs-urls` and `slo-url` must be absolute `https` URLs (plain `http` only on loopback), without user info or fragment. Startup fails otherwise.
- Set `require-signed-requests=true` and provide `signing-certificate` when the SP signs AuthnRequests.
- Set `encrypt-assertion=true` with `encryption-certificate` for SPs that want encrypted assertions.
- Assertions live `assertion-ttl` (5m) with `clock-skew` (60s) tolerance. Keep server clocks on NTP.
- Keep the private keys in a secret store, not in the repository.
