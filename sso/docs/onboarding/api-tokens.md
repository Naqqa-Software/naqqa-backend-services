# API tokens

Enable with `naqqa.sso.api-tokens.enabled=true`. Manage tokens per tenant under `/api/sso/admin/tenants/{tenantId}/api-tokens` (authority `SSO_ADMIN_TOKENS`, fresh step-up for every change). The older route `/api/auth/admin/api-tokens` (authority `SSO_API_TOKENS`) still issues secret keys for existing integrations.

## Format

```
sso_<kind>_<env>_<id>_<secret>
```

| Part | Values |
|---|---|
| kind | `pk` publishable, `sk` secret |
| env | `live`, `test` |

The part `sso_<kind>_<env>_<id>` is the prefix. It is not secret and is what the admin list shows.

## sso_pk versus sso_sk

- `sso_sk_...` authorizes server calls (SCIM, provisioning). It must live only on your backend or CI secret store. Never put it in a browser bundle, mobile app, URL, log line or repository. If one leaks, revoke it at once. A secret key never reaches admin mutations: those need an interactive session with step-up.
- `sso_pk_...` identifies a tenant and one client (application) and is safe to ship in a browser bundle. It is not a credential: it never logs anyone in and never grants access to an API. Sent as `Authorization: Bearer` it is rejected (`INVALID_API_TOKEN`).

### Publishable keys in the browser

Issue one with `POST /api/sso/admin/tenants/{tenantId}/api-tokens` and body `{ "kind": "publishable", "clientId": "<client of that tenant>", "name": "web" }`. Then configure the client's allowed origins (`PATCH /api/sso/admin/tenants/{tenantId}/clients/{clientId}` with `{ "allowedOrigins": ["https://shop.example.com"] }`). An origin is `scheme://host[:port]` with no path; https, or http only on `localhost`, `127.0.0.1`, `[::1]`.

The browser sends `X-SSO-Key: sso_pk_...` on the public SSO endpoints under `/api/auth` (`GET /methods`, login and login option calls). The server:

1. Resolves the key to its tenant and client. The bound client replaces `X-Client-Id`; a different `X-Client-Id` is rejected with 400 `CLIENT_MISMATCH`.
2. Compares the request `Origin` header with the client's allowed origins, exact scheme, host and port. A missing or different origin is rejected with 403 `PUBLISHABLE_KEY_ORIGIN_DENIED`.
3. Rejects unknown, revoked, malformed or `sso_sk` values with 401 `PUBLISHABLE_KEY_INVALID`, and keys of a suspended tenant with 403 `TENANT_SUSPENDED`.

Every rejection is written to the audit log (`publishable_key.check`). The origin check stops other websites from using your key from a browser; it does not stop a non-browser client that forges the `Origin` header, which is why a publishable key only selects the client and its login options and never protects data.

### CORS

For `/api/auth/**` and `/api/sso/**`, naqqa-auth answers CORS from the same allowlist: `naqqa.sso.allowed-origins` plus every client's allowed origins. A listed origin gets `Access-Control-Allow-Origin: <that origin>` with `Access-Control-Allow-Credentials: true` and `Vary: Origin`; a preflight is answered 204 with methods `GET, POST, PUT, PATCH, DELETE` and headers `Content-Type, Authorization, X-Client-Id, X-SSO-Key, X-XSRF-TOKEN, X-Device-Id` plus the header names the preflight asks for. A wildcard is never sent. Origins outside the allowlist get no CORS headers from naqqa-auth (the host application's own CORS policy, if any, still applies to them).

## Secrets shown once

The secret part is shown once, in the issue or rotate response (`token`). Only a PBKDF2 hash over an HMAC with the pepper is stored. The list returns `id`, `prefix`, `name`, `kind`, `env`, `scopes`, `allowlist`, `clientId`, `createdAt`, `rotatedAt`, `graceUntil`, `revokedAt` and `lastUsedAt` (updated at most once a minute), never the token or its hash. Tokens can carry IP allow lists (CIDR) and scopes. Test tokens never touch live data, so use `test` in a test tenant first. The tenant plan caps the number of active tokens (`QUOTA_EXCEEDED`).

## Rotation and revocation

- Rotate: `POST /api/sso/admin/tenants/{tenantId}/api-tokens/{id}/rotate` with `{ "graceSeconds": 86400 }` returns a new secret. The old one keeps working until `graceUntil` (default `naqqa.sso.api-tokens.rotation-grace`, 24h, maximum 7 days) so you can deploy without downtime.
- Revoke: `DELETE /api/sso/admin/tenants/{tenantId}/api-tokens/{id}` takes effect immediately. Deleting a client revokes its publishable keys.
- After `max-failed-attempts` (10) bad secrets for one token from one address, further attempts are slowed down for up to `lock-duration` (15m).
- Rotate on a schedule (90 days suggested), on staff changes, and after any suspected exposure.
