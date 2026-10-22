# SSO cross-language test vectors

Deterministic JSON files generated from the Java implementation. Any adapter (Angular `@naqqa/auth`, Spring `naqqa-auth`, other stacks) must pass every case in every file and produce the same outcome.

## Files

| File | Covers |
| --- | --- |
| `jwt.json` | RS256, ES256, EdDSA test keys (JWK), signed `at+jwt` access tokens, negative cases (alg none, alg switch, expired, wrong aud/iss/typ, tampering, non-canonical base64url) |
| `jwks.json` | JWKS publish output, RFC 7638 thumbprints (with the exact thumbprint input string), JWKS parsing rules |
| `totp.json` | RFC 6238 vectors, verify windows and replay, otpauth URIs, Base32 |
| `pkce.json` | RFC 7636 S256 verifier to challenge, verifier validation |
| `webauthn.json` | Registration and authentication responses (none and packed self attestation; ES256, RS256, EdDSA) with negative cases |
| `api-token.json` | `sso_<kind>_<env>_<id>_<secret>_<crc>` parse cases |
| `password.json` | PBKDF2-SHA512 PHC strings, pepper, `needsRehash` |

## Format

Every file has `description`, `version` and one or more case lists. A case is:

```json
{ "name": "...", "description": "...", "input": { }, "expected": { } }
```

- Binary values are base64url without padding, or lowercase hex where the field name ends in `Hex`.
- `expected.error` (or `valid:false`, `ok:false`) carries the Java error code. An adapter may map codes to its own errors, but the accept/reject decision and the failure category must match.
- `jwt.json`: `validator` gives issuer, audience, typ, leeway and the fixed clock. Verify each `input.token` against the public keys in `keys[].publicJwk`, looked up by `kid`.
- `webauthn.json`: `input.policy` is the relying party policy, `nowIso` the clock, `challenge` the issued challenge. Authentication cases carry the stored credential in `input.stored`.
- `password.json`: cases with `testOnly: true` use low iteration counts and a public pepper. Never use those values outside tests.
- Private keys in `jwt.json` are test keys committed on purpose.

## Rules for adapters

1. Load every file, run every case, compare to `expected`. Unknown fields must be ignored.
2. A failing vector is a bug in the adapter, not in the vector, unless the Java side changed. Do not edit the files by hand.
3. Reusable without a server: the Angular side can run `pkce.json` (challenge computation and verifier validation) and the client side of `webauthn.json` (base64url handling, clientDataJSON and authenticatorData layouts) from `sso/vectors/pkce.json` and `sso/vectors/webauthn.json`.

## Regenerating

The generator is test scope: `src/test/java/com/naqqa/sso/vectors/VectorGenerator.java`, run by `VectorsTest`.

```
./mvnw test -Dtest='com.naqqa.sso.vectors.**' -Dsso.vectors.write=true
```

Without the flag the same test regenerates in memory and fails the build if the committed files differ from what the implementation produces, so behaviour changes cannot go unnoticed. Existing keys, signatures and password hashes are reused when the file exists (ECDSA signatures are random), so regeneration is stable. Delete a file to mint new keys.
