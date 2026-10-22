# SCIM 2.0 provisioning

Enable with `naqqa.sso.scim.enabled=true` and set `naqqa.sso.scim.base-url` to the public URL, for example `https://auth.example.com/scim/v2`.

## Endpoints

`/scim/v2/Users`, `/Groups` (GET list, POST create, GET/PUT/DELETE by id, `.search`), plus `ServiceProviderConfig`, `ResourceTypes` and `Schemas` for discovery.

## Authentication

SCIM calls use a bearer API token created for the tenant (see `api-tokens.md`). Use a secret token (`sso_sk_...`) in the IdP's provisioning settings. Store it in the IdP's secret field only.

## IdP setup (Okta, Entra ID, others)

1. Tenant URL: the `base-url` value. Auth: HTTP header bearer token.
2. Enable create, update and deactivate users; push groups if needed.
3. Match users on `userName` (email).
4. Test with a single user before assigning groups.

## Limits

`max-results=100` per page, `max-scan=2000` rows examined per filtered query, `max-start-index=10000`. Narrow filters instead of paging deep. Plan quotas may cap SCIM operations per day; exceeding a hard limit rejects provisioning calls but never blocks existing users from signing in.
