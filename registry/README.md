# Naqqa Registry (`com.naqqa:naqqa-registry`)

Token-gated, read-only npm registry for the naqqa-ui packages (`@naqqa/*` components and micro-apps). Each customer gets a token that grants package sets and/or packages until an expiry date. The token can be revoked at any time. npm, pnpm and yarn install with the token through the standard registry protocol. Licensing is enforced at install time, with no runtime checks in the shipped code. Design: `naqqa-ui/docs/token-registry.md`.

Spring Boot 3.4.x library jar, Java 21. It depends on `naqqa-commons` for the error contract `{status, code, message}` and auto-configures on a servlet web app unless `naqqa.registry.enabled=false`. No third-party registry software is involved.

## Endpoints

Base path `naqqa.registry.path` (default `/api/registry`).

### Admin (permission-gated through `RegistryAdminAccess`)

| Method | Path | Permission | Body / query | Response |
| --- | --- | --- | --- | --- |
| POST | `/tokens` | manage | `{customerId, customerName?, label?, packageSets?: [], packages?: [], expiresAt? \| ttlDays?}` | 201 `{token, record}`; `token` is the only time the raw value is shown (`Cache-Control: no-store`) |
| GET | `/tokens` | read | `?customerId=&status=all\|active\|expired\|revoked&page=0&size=50` | `PageResponse<TokenView>` |
| GET | `/tokens/{id}` | read | - | `TokenView` |
| POST | `/tokens/{id}/revoke` | manage | `{reason?}` | `TokenView` (idempotent, so the first revocation is kept) |
| GET | `/catalog` | read | - | `{packageSets: {name: [patterns]}, packages: [{name, latest, versions}]}` for the admin UI |

`TokenView` = `{id, prefix: "nqr_<id>_****", customerId, customerName, label, packageSets, packages, status, createdAt, createdBy, expiresAt, revokedAt?, revokedBy?, revokeReason?, lastUsedAt?}`. The raw token and its hash are never returned after issue.

### Token holder (`Authorization: Bearer <token>`, or `Basic` with the token as password)

| Method | Path | Response |
| --- | --- | --- |
| GET | `/validate` | `{valid: true, tokenId, customerId, expiresAt, packages: [resolved package names]}` |
| GET | `/npm/-/ping` | `{}` (no token needed) |
| GET | `/npm/-/whoami` | `{username: customerId}` |
| GET | `/npm/@scope/name`, `/npm/@scope%2fname`, `/npm/name` | npm packument: `name`, `dist-tags`, `versions[v]` (stored manifest + `dist.{tarball, shasum, integrity}`), `time` |
| GET | `/npm/@scope/name/-/name-<version>.tgz`, `/npm/name/-/name-<version>.tgz` | tarball bytes |

The registry is read-only (a `PUT` publish gets 405). Packages are published by writing to the storage directory (see below).

Error codes: `token_missing` 401, `token_invalid` 401, `token_expired` 401, `token_revoked` 401 (all with `WWW-Authenticate: Bearer realm="naqqa-registry"`), `unauthorized` 401 (admin not signed in), `forbidden` 403, `not_found` 404 (also returned for packages the token does not grant, so callers cannot probe which packages exist), `validation_failed` 422.

## Token model

- Format `nqr_<16 id chars>_<40 secret chars>_<crc32>` in base32 lowercase. The secret is 200 random bits from `SecureRandom`, and the CRC lets malformed or mistyped tokens be rejected without a store lookup.
- Only `HMAC-SHA256(pepper, token)` is stored and compared in constant time. A leaked database alone does not yield usable tokens.
- Every token expires: the default TTL is `default-token-ttl` (365d) and the maximum is `max-token-ttl` (730d). Expiry applies at the exact instant.
- Grants: `packageSets` (names from `naqqa.registry.package-sets`, resolved at request time, so editing a set changes every holder immediately) plus `packages` (exact names or scope wildcards like `@naqqa/*`). With `include-dependencies=true` (default), packages in storage that a granted package depends on (`dependencies`, `peerDependencies`, `optionalDependencies`) are granted too, so installs never break on internal dependencies.
- `lastUsedAt` is recorded at most once per minute per token.

## Storage layout

`naqqa.registry.storage-dir` (default `registry-storage`, relative to the working directory):

```
<storage>/@naqqa/badge/badge-0.0.1.tgz
<storage>/@naqqa/badge/0.0.1.json        package.json of that version + dist.{shasum, integrity, fileCount, unpackedSize}
<storage>/@naqqa/badge/dist-tags.json    {"latest": "0.0.1"}   optional, default latest = highest stable version
```

The layout is produced by `naqqa-ui/scripts/pack-for-registry.mjs`. Entries whose name or version does not match the directory, or that have no tarball, are skipped. Symlinks are not followed. The catalog is rescanned every `catalog-refresh` (5s).

## Configuration

| Property | Default | Notes |
| --- | --- | --- |
| `naqqa.registry.enabled` | `true` | |
| `naqqa.registry.path` | `/api/registry` | npm API lives under `<path>/npm` |
| `naqqa.registry.storage-dir` | `registry-storage` | |
| `naqqa.registry.public-url` | request origin | origin used in `dist.tarball` (set it behind a proxy, e.g. `https://api.naqqa.com`) |
| `naqqa.registry.token-pepper` | random (warns) | base64, at least 16 bytes. Required in production, otherwise tokens stop verifying after a restart |
| `naqqa.registry.default-token-ttl` / `max-token-ttl` | `365d` / `730d` | |
| `naqqa.registry.package-sets.<name>` | none | list of names or `@scope/*` patterns, validated at startup |
| `naqqa.registry.include-dependencies` | `true` | |
| `naqqa.registry.catalog-refresh` | `5s` | |
| `naqqa.registry.read-authority` / `manage-authority` | `registry.tokens:read` / `registry.tokens:manage` | used by the default `SecurityContextAdminAccess` |
| `naqqa.registry.allow-encoded-slash` | `true` | sets Tomcat `encodedSolidusHandling=passthrough` so npm's `@scope%2fname` reaches the app. A servlet filter (highest precedence, npm path only) rewrites it to `@scope/name` before Spring Security's firewall sees it |
| `naqqa.registry.collection-prefix` | `naqqa_registry_` | Mongo collection `<prefix>tokens` |

## Host integration (SPI)

| Port | Default | Host provides |
| --- | --- | --- |
| `RegistryTokenStore` | `MongoRegistryTokenStore` when a `MongoOperations` bean exists, else `InMemoryRegistryTokenStore` (warns) | optional custom store |
| `RegistryAdminAccess` | `SecurityContextAdminAccess` (authorities above) when Spring Security is present, else `DENY_ALL` | mapping to the host's permission model (naqqa-os-be: `AccessPolicy` + project membership) |
| `PackageSource` | `FileSystemPackageSource(storage-dir)` | e.g. object-storage backed source |

Host security: permit `<path>/npm/**` and `<path>/validate` without a session, and exclude them from the OAuth2 resource-server bearer resolver, so the registry token is not parsed as a JWT. Keep `<path>/tokens/**` and `<path>/catalog` authenticated.

## Tests

`mvn test`: tokens, names/semver, file storage, Mongo mapping, service rules, MockMvc for both controllers (auth, grants, dependency closure, revocation, expiry, metadata, tarball, traversal), auto-configuration, and a real embedded Tomcat test for `@scope%2fname`. The opt-in real npm round trip packs with the naqqa-ui script, runs `npm install` against the test server, and checks a 404 for ungranted packages and a 401 after revocation:

```
mvn test -Dtest=RegistryHttpEndToEndTest -Dnaqqa.registry.npm-e2e=true -Dnaqqa.registry.pack-script=<naqqa-ui>/scripts/pack-for-registry.mjs
```
