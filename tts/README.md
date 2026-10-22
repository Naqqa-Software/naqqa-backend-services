# Naqqa TTS (`com.naqqa:naqqa-tts`)

On-device text-to-speech for Naqqa apps: a public `GET` endpoint that streams a `audio/wav`
rendering of a short text, backed by a local Piper engine with an on-disk cache and a per-client
rate limiter. It serves the naqqa-os "listen" controls.

Spring Boot 3.4.x library jar, own pom, Java 21. Auto-configures only when `naqqa.tts.enabled=true`
(the engine downloads/warms a Piper voice on startup, so it is off by default).

## Layers

- `web/TtsController` - maps HTTP, ETag/cache handling, and renders `TtsException` to a status body.
- `dto/TtsStatus` - the `{ enabled, ready, languages }` status contract.
- `core/` - the service and engine-agnostic domain: `TtsService`, `TtsEngine` (port), `TtsCache`,
  `TtsRateLimiter`, `TtsTextNormalizer`, `TtsAudioProcessor`, `TtsException`.
- `piper/` - the `TtsEngine` adapter over the Piper binary (`PiperEngine`, `PiperInstaller`,
  `PiperProcess`).
- `config/` - `NaqqaTtsAutoConfiguration` + `TtsProperties`.

## Endpoints

Base path `naqqa.tts.path` (default `/api/public/tts`).

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| GET | `/api/public/tts` | `?text=<string>&lang=<string>` | `audio/wav` bytes (ETag + `Cache-Control`; `X-TTS-Cache: hit\|miss`) |
| GET | `/api/public/tts/status` | - | `TtsStatus { enabled, ready, languages }` |

A conditional request matching the ETag returns `304 Not Modified`. On failure the endpoint returns
the mapped status (`400` invalid/unsupported, `429` rate limited, `503` busy/unavailable/failed)
with body `{ "error": "<reason>" }` and a `Retry-After` header where applicable.

## Properties (`naqqa.tts.*`)

- `enabled` (default `false`)
- `path` (default `/api/public/tts`)
- `cache-max-age-seconds`, `cache-max-mb`, `rate-limit-per-minute`, `data-dir`, plus the
  `voice`/`piper`/`audio` groups bound by `TtsProperties`.

## Build

```
cd naqqa-backend-services/tts
mvn -q -o package
```
