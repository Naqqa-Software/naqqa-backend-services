# Store contracts

Every persisted entity in `naqqa-sso` sits behind an interface. Core flows are written against these interfaces and assume the guarantees below. A store that does not give them reopens the races the flows were written to close (double redemption, revocation undone, counters that never reach their cap).

Rules for every implementation:

- A method that implies single use, a state transition or a counter is atomic on the backing store itself, not inside one JVM. Two replicas calling it at the same instant behave as if the calls ran one after the other.
- A method that changes one field never rewrites other fields from a record read earlier.
- Absent records are never created by conditional or field-level methods (no upsert), unless the method says so.
- Times are compared with the store's own clock only where the contract says so; otherwise expiry is checked by the core flow.

Contract tests live in `sso/src/test/java/com/naqqa/sso/contract` as abstract JUnit classes and are shipped in the `naqqa-sso` test jar (`<type>test-jar</type>`). `InMemoryStoreContractsTest` runs them against the in-memory stores; `naqqa-auth` runs them against its Mongo stores in `MongoStoreContractsTest`. A new implementation subclasses the matching contract and must pass it.

Implementations: InMemory (core, single JVM, tests and embedded use) and Mongo (naqqa-auth, production). Plain reads (`find*`, `list*`) return the latest committed state and never create records; they carry no atomicity requirement beyond that and are not repeated in every table.

## tokens.ApiTokenStore

| Method | Requirement |
|--------|-------------|
| `findById`, `findByTenant` | Plain reads. |
| `save(record)` | Creates or replaces a token. Never clears `revokedAt` of an existing record (revocation is sticky against stale writers). Used to issue. |
| `recordFailure(id)` | Atomic increment of `failedAttempts`, returns the new value (0 when absent). Touches no other field. |
| `resetFailures(id)` | Sets `failedAttempts` to 0 and `lockedUntil` to null. Touches no other field. |
| `revoke(id, at)` | Sets `revokedAt` only when unset; true for exactly one caller. |
| `rotate(id, expectedHash, ...)` | Compare-and-swap on the current hash; refused when revoked. Concurrent rotations: one wins, the others get `token.conflict`. |
| `updateScopes`, `updateAllowlist` | Field-level set; false when absent. |
| `delete` | True for exactly one caller. |

Why: `ApiTokenService.authenticate` reads the record, spends a PBKDF2 verification, then updates counters. Before F-CORE-17 it saved the whole record it had read, undoing a revoke, rotation or scope change that landed in between. Tests: SSO-T-tokens-027..029, SSO-T-contract-001..004.

## session.SessionStore

| Method | Requirement |
|--------|-------------|
| `save` | Creates or replaces (session creation only). |
| `find`, `listByUser` | Plain reads by id hash or user. |
| `replace(session)` | Replaces only an existing session, atomically; never upserts. A session deleted (logout, revoke) is never brought back by a concurrent touch. No default body (F-CORE-22). |
| `delete`, `deleteByUser` | Remove; `deleteByUser` returns the count. |

Tests: SSO-T-session-040..042, SSO-T-contract-010, 011, SSO-T-auth-store-026.

## session.RefreshStore

| Method | Requirement |
|--------|-------------|
| `save`, `find` | Insert of a new refresh record; plain read by token hash. |
| `markRotated(hash)` | Flips `rotated` false to true once; the single-use point of refresh rotation. |
| `revokeFamily`, `isFamilyRevoked` | A revocation marker visible to every replica at once, kept at least until the family expires. |
| `revokeAllForUser` | Marks every family of the user known at call time. A family created concurrently is covered by the caller revoking sessions first. |

Tests: SSO-T-contract-020, 021.

## otp.OtpStore

| Method | Requirement |
|--------|-------------|
| `recordAttempt(id, max)` | Atomic: increments `attempts` only when the challenge is not consumed and `attempts < max`; returns the updated record, or empty. Called before the code is compared (F-CORE-13). |
| `consume(id)` | Flips `consumed` false to true once. |
| `save` | Creates a challenge. Must not revive a consumed one (Mongo refuses). |
| `invalidateDestination` | Consumes every challenge of the destination. |
| `find` | Plain read. |
| `recordSend`, `lastSend`, `sendsSince` | Append-only log; counts include parallel sends. The check-then-send in `OtpService.request` is not atomic across replicas; naqqa-auth puts an atomic `SlidingWindowLimiter` (RateLimitStore) in front of every send. |

Tests: SSO-T-otp-042, 043, SSO-T-contract-030..034, SSO-T-auth-store-017.

## otp.MagicLinkStore

`save` inserts, `find` reads; `markUsed(hash)` flips `used` once (Mongo also requires the link to be unexpired; core checks expiry first). Tests: SSO-T-contract-040.

## otp.PushApprovalStore

| Method | Requirement |
|--------|-------------|
| `create` | Insert; duplicate id refused. |
| `transition(id, from, to)` | Compare-and-swap on status; exactly one racing transition from a given status wins. |
| `find` | Plain read. |
| `addMismatch(id)` | Atomic increment on a PENDING approval only; returns the new count, empty otherwise. |

Tests: SSO-T-contract-050, 051.

## webauthn.ChallengeStore and CredentialStore

| Method | Requirement |
|--------|-------------|
| `ChallengeStore.save` | Inserts a challenge with its expiry. |
| `ChallengeStore.consume(value)` | Removes and returns the challenge once; never returns an expired one. |
| `CredentialStore.create` | Unique by credential id (`webauthn.credential_exists`). |
| `CredentialStore.findById`, `findByUserHandle`, `findByUserId` | Plain reads. |
| `CredentialStore.delete` | True for exactly one caller. |
| `CredentialStore.update(previous, next)` | Compare-and-swap on sign count, clone flag, backup state and last use of `previous`; one racing assertion wins, so the sign counter is never rolled back. |

Tests: SSO-T-contract-060, 070, 071.

## mfa.TotpStepStore, TotpCredentialStore, RecoveryStore

| Method | Requirement |
|--------|-------------|
| `TotpStepStore.advance(user, step)` | Atomic: succeeds only when `step` is above the last accepted step; one racing call wins (TOTP replay). |
| `TotpStepStore.lastStep`, `clear` | Plain read; removal (factor reset). |
| `TotpCredentialStore.save` | Saving an unconfirmed credential over a confirmed one throws `totp_already_enrolled` (F-CORE-21). Confirmed saves replace (import and tests only; enrollment uses `confirm`). |
| `TotpCredentialStore.confirm(user, sealedSecret, at)` | Compare-and-swap: confirms only an unconfirmed credential holding exactly that sealed secret, true once; false when it was removed or replaced by a new enrollment; never creates (F-CORE-27). |
| `TotpCredentialStore.find`, `delete` | Plain read; removal. |
| `RecoveryStore.find`, `delete` | Plain read; removal of the whole set. |
| `RecoveryStore.markUsed(user, hash)` | Flips one unused entry with that hash to used, once, and resets the failure counter (F-CORE-20). |
| `RecoveryStore.recordFailure(user, n, at)` | Writes only the counter fields of an existing set; never rewrites codes, never creates a set. |
| `RecoveryStore.save` | Whole-set replace, used by (re)generation; codes of a replaced set stop redeeming. |

Residual F-CORE-14: the recovery failure counter is last-writer-wins across replicas; naqqa-auth throttles every recovery attempt with the atomic per-account lockout. Tests: SSO-T-mfa-072..075, SSO-T-contract-080..082, 090..092.

## oidc.OidcStore

| Method | Requirement |
|--------|-------------|
| `consumeCode(hash)` | Atomically marks the code used and returns the record as it was before; exactly one caller sees `used=false`, every later caller sees `used=true` (reuse detection revokes the grant). |
| `saveCode`, `savePushed`, `savePending` | Inserts of new single-use records. |
| `consumePushed(hash)` | Removes and returns once. |
| `findPending`, `deletePending` | Plain read; removal. The pending interaction is not single use; the code it produces is. |
| `markOnce(key, exp)` | Insert-if-absent; one caller wins (nonce, DPoP and client assertion `jti`). |
| `saveDeviceIfUserCodeFree` | Claims a user code for one live device authorization; concurrent claims of one code yield one success (F-CORE-23). |
| `findDeviceByCode`, `findDeviceByUserCode` | Plain reads. |
| `replaceDevice(expected, next)` | Compare-and-swap on the whole expected state. |
| `saveGrant` | Whole-record write. Grant revocation does not rely on it: `revokeGrant` writes a `grant:` marker first and every issuance (code, device, refresh) re-checks the `grant:` and `sid:` markers after saving (F-CORE-2, F-CORE-26). naqqa-auth `SsoCredentialRevoker.revokeOidcGrants` revokes each listed grant through `OidcProvider.revokeGrant` (F-A12-AUTH-4, F-CORE-30, SSO-T-auth-683, 693). A host revoking a grant it listed earlier calls `OidcProvider.revokeGrant(grantId)`, which re-reads the grant after the marker and marks the access tokens and refresh family of both copies (F-CORE-30, SSO-T-oidc-144). |
| `findGrant`, `grantsForSid` | Plain reads. `grantsForSid` may miss a grant saved concurrently; the post-save `sid:` re-check covers it. |
| `indexRefresh`, `grantIdForRefresh` | Write and read of the refresh hash to grant index. |
| `purgeExpired(now)` | Removes expired records; returns the count. Never removes a live record. |
| `markRevoked`, `isRevoked` | Marker visible to every replica at once, kept until its expiry. |

Tests: SSO-T-oidc-138, 139, 142, SSO-T-contract-110..112.

## oauth.StateStore, saml.ReplayCache, saml.sp.SamlRequestStore

`StateStore.put` and `SamlRequestStore.save` insert. `StateStore.consume` removes and returns once; `SamlRequestStore.consume` removes once and is true only for a live id. `ReplayCache.markIfAbsent` accepts a live key once (an expired key may be reused). Tests: SSO-T-contract-100..102; through `SamlSp.verifyResponse`, SSO-T-saml-139 (one response posted in parallel) and SSO-T-saml-140 (parallel responses for one request). naqqa-auth implements `SamlRequestStore` as `MongoSamlRequestStore` (`sso_saml_requests`: insert with a duplicate refused, `consume` = `findAndRemove` and true only when the removed record is unexpired, TTL index) and runs SSO-T-contract-102 against it in `MongoStoreContractsTest`; the SP wraps it and `MongoSamlReplayCache` with a per-tenant key prefix.

## scim.ScimResourceStore (users, groups)

| Method | Requirement |
|--------|-------------|
| `find`, `findByUserName`, `findByDisplayName` | Plain reads; the unique-name lookups are case-insensitive. |
| `list(tenant)` | Whole-tenant read. A bounded store may refuse it with `scim.too_many`; naqqa-auth refuses it on the raw Mongo stores and serves `ScimService` through a request scope that lists only the resources the request touches. |
| `insert` | Unique id and case-insensitive unique `userName` (users) or `displayName` (groups) per tenant, also under contention (`scim.uniqueness`). |
| `update(next, expectedVersion)` | Compare-and-swap on the version (`scim.version_conflict`, `scim.not_found`). |
| `delete` | True for exactly one caller. |

Partial failure semantics (ScimService): host hooks run before the store change they describe, so a failing hook leaves the SCIM record unchanged and the IdP retry re-runs it: `onDeleted` before the record delete (F-CORE-15), `onDeactivated` before the update that makes the user inactive (F-CORE-18). A failing `onPasswordSet` on create rolls the new user back (`onDeleted`, then delete) so the retry creates it again (F-CORE-28). Hooks must be idempotent. `deleteUser` answers 204 even when the hook already removed the record, but a hook should leave that record to `ScimService`: naqqa-auth erases the account, its mapping and the other tenant SCIM resources (last, F-CORE-19) and keeps the record being deleted (F-CORE-25). Group cleanup after a user delete is best effort per group. Tests: SSO-T-scim-147..154, SSO-T-auth-660..663, SSO-T-contract-120..122.

## audit.AuditStore

`append` rejects an entry whose sequence number is already taken, so two writers cannot fork the hash chain; `all` returns entries in sequence order. naqqa-auth retries on the duplicate key with a reloaded chain head. Tests: SSO-T-contract-130.

## ratelimit.RateLimitStore

`bucket`/`putBucket`, `window`/`putWindow`, `failures`/`putFailures` and `remove` are plain per-key reads and writes; they are only atomic inside `atomically`, and core never calls them outside it. `atomically(action)` runs the reads and writes of `action` as one unit with respect to every key it touches: in memory under a lock, in Mongo with per-key optimistic versions and retry. A multi-key commit that conflicts on a later key retries the whole action, which can count a failure twice; it never counts less. Tests: SSO-T-contract-131.

## oidc.ConsentStore, ClientStore

| Method | Requirement |
|--------|-------------|
| `ConsentStore.grant(tenant, user, client, scopes, at)` | Atomic union of `scopes` into the record (creates it when absent) and sets `grantedAt`. Core never reads, merges and saves a consent, so a revoke landing during an approval is never undone (F-CORE-29). |
| `ConsentStore.save` | Whole-record replace (host import and admin tools). |
| `ConsentStore.find`, `listByUser` | Plain reads. |
| `ConsentStore.revoke` | True for exactly one caller. |
| `ClientStore.find`, `save` | Plain read; create or replace by client id (naqqa-auth refuses `save` over an operator-configured client). |
| `ClientStore.delete` | True for exactly one caller; false for configured clients. |

Tests: SSO-T-oidc-143, SSO-T-contract-132..134.
