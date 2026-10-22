# Registering an app as an OIDC client

Enable the provider with `naqqa.sso.oidc-provider.enabled=true` and set the issuer with `naqqa.sso.oidc-provider.issuer` (falls back to `naqqa.sso.issuer`; startup fails if neither is set). Clients read `<issuer>/.well-known/openid-configuration` for all endpoints. The provider serves them under `<issuer>/oauth2`:

| Endpoint | Path |
|----------|------|
| Discovery | `/.well-known/openid-configuration` |
| Authorization | `/oauth2/authorize` (GET or POST) |
| Pushed authorization request (PAR) | `/oauth2/par` |
| Token | `/oauth2/token` |
| UserInfo | `/oauth2/userinfo` |
| JWKS | `/oauth2/jwks` |
| Revocation | `/oauth2/revoke` |
| Introspection | `/oauth2/introspect` |
| Device authorization | `/oauth2/device_authorization` |
| User code entry | `/oauth2/device` (redirects to the device page of the frontend) |
| End session | `/oauth2/logout` (GET or POST) |
| Dynamic registration | `/oauth2/register`, only with `naqqa.sso.oidc-provider.dynamic-registration=true` and an initial access token in the env var named by `registration-token-env` (default `NAQQA_SSO_OIDC_REGISTRATION_TOKEN`); otherwise 404 and absent from discovery |

The login, consent, logout confirmation and device pages live in the frontend: `login-url`, `consent-url`, `logout-url` and `device-url` under `naqqa.sso.oidc-provider`, defaulting to `${frontend.url}/login`, `/consent`, `/logout` and `/device`. ID and access tokens are signed with a key ring separate from the naqqa-auth access tokens (`signing-algorithm`, default `RS256`; key in the env var named by `signing-key-env`, default `NAQQA_SSO_OIDC_SIGNING_KEY`).

## Client configuration

Each client is a block under `naqqa.sso.oidc-provider.clients.<client-id>`:

```properties
naqqa.sso.oidc-provider.clients.crm.name=CRM
naqqa.sso.oidc-provider.clients.crm.auth-method=client_secret_basic
naqqa.sso.oidc-provider.clients.crm.secret-env=CRM_OIDC_SECRET
naqqa.sso.oidc-provider.clients.crm.redirect-uris=https://crm.example.com/callback
naqqa.sso.oidc-provider.clients.crm.post-logout-redirect-uris=https://crm.example.com/
naqqa.sso.oidc-provider.clients.crm.scopes=openid,profile,email,offline_access
naqqa.sso.oidc-provider.clients.crm.backchannel-logout-uri=https://crm.example.com/backchannel-logout
```

Other keys per client: `tenant` (default `default`), `public-client`, `jwks` (for `private_key_jwt`), `grant-types`, `first-party` (skips the consent screen), `require-par`, `require-dpop`, `require-state`, `allowed-acr`, `min-acr`, `audience`, `access-token-ttl`, `id-token-ttl`. A client with no redirect URIs and no `grant-types` gets `client_credentials` only.

## Registration rules

- Redirect URIs are compared by exact string match, port included. No wildcards, no partial paths, no fragments, lowercase hosts. `http` is accepted only for `localhost`, `127.0.0.1` and `[::1]`, so a native app on a loopback redirect registers the exact port it listens on. Private-use schemes must contain a dot (reverse domain, for example `com.example.app:/cb`). Register every environment separately.
- PKCE with `S256` is always required for every client; `plain` and requests without a challenge are refused. There is no switch to turn it off.
- Public clients (`public-client=true`: SPAs, mobile) have no secret. Confidential clients use `auth-method` `client_secret_basic` (default) or `client_secret_post` with the secret in the env var named by `secret-env`, or `private_key_jwt` with the client's public keys in `jwks`. Prefer `private_key_jwt`. `client_secret_jwt` is not supported.
- Request only the scopes the app needs and list them in the client's `scopes`: `openid` always, then `profile`, `email`, `offline_access`, plus your own API scopes. Consent is stored per client and scope; `first-party` clients are not asked.

## Authorization code flow

1. Generate `code_verifier`, derive `code_challenge` (S256), random `state` and `nonce`.
2. Redirect to the authorization endpoint with `response_type=code`, `client_id`, `redirect_uri`, `scope`, `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`. Clients with `require-par=true` must first post these parameters to `/oauth2/par` and send only `client_id` and `request_uri`.
3. On callback verify `state` and `iss`, then exchange the code at the token endpoint with `code_verifier`. Codes live 1 minute (`naqqa.sso.oidc-provider.authorization-code-ttl`, capped at 10 minutes) and work once.
4. Validate the ID token signature against the JWKS, plus `iss`, `aud`, `exp` and `nonce`.

## Refresh tokens and sessions

Refresh tokens rotate on every use; presenting a rotated token revokes the whole family. Without `offline_access` a refresh token is bound to the SSO session (`sid`) and stops working when that session ends. With `offline_access` it survives a normal logout. Every refresh family of the user, `offline_access` included, is revoked when the user removes an MFA factor or passkey, resets the password, is deactivated or deleted through SCIM, or the account is erased. Your app then gets `invalid_grant` and must start a new authorization.

## Back-channel logout

Set `backchannel-logout-uri` on the client. The logout token always carries `sub` and `sid`. In production it must be `https` on a public address; plain `http` and loopback targets are refused unless `naqqa.sso.oidc-provider.backchannel.test-mode=true` (local fixtures only; refused at startup unless `naqqa.sso.dev-mode=true`). naqqa posts a signed `logout_token` to every client of an SSO session when that session ends: RP logout through `/oauth2/logout`, `POST /api/auth/logout`, `POST /api/auth/logout/device`, `DELETE /api/auth/sessions/{id}`, `POST /api/auth/sessions/revoke-others`, the end of an impersonation session, factor removal, password reset and account erasure. Your endpoint must verify the signature, issuer, audience, the `events` claim and that there is no `nonce`, then end the local session for that `sid`. Respond 200 quickly. Delivery is retried (`backchannel.max-attempts`, default 3, `backchannel.backoff`, default 2s) with a per-attempt timeout of `backchannel.timeout` (default 5s).

## Device flow

Set `naqqa.sso.device-code.enabled=true` for TVs and CLIs and give the client `grant-types=urn:ietf:params:oauth:grant-type:device_code,refresh_token`. The device calls `/oauth2/device_authorization`, shows the `user_code` and `verification_uri`, and polls the token endpoint no faster than `interval` (`naqqa.sso.device-code.poll-interval`, default 5s). Codes expire after `naqqa.sso.device-code.ttl` (default 10 minutes).

## Server to server

`naqqa.sso.client-credentials.enabled=true` issues access tokens for confidential clients without a user; the client needs `grant-types=client_credentials`. For long-lived integration keys prefer API tokens (see `api-tokens.md`).
