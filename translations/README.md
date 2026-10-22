# Naqqa Translations (`com.naqqa:naqqa-translations`)

Dynamic i18n catalog library for Naqqa backend apps. Serves the catalog that the naqqa-os admin translations editor edits: the base seed tree, runtime overrides, the list of supported and added languages, and the default/reference language. Mirrors the OS dev-data contract in `naqqa-os-fe/src/app/core/dev-data/devdata-translations.ts` (+ `translations-storage.ts`).

Spring Boot 3.4.x library jar, own pom (no aggregator), Java 21, like the other modules. Auto-configures on a servlet web app unless `naqqa.translations.enabled=false`.

## Endpoints

Base path `naqqa.translations.path` (default `/api/translations`). Shapes below are the JSON the OS expects.

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| GET | `/api/translations` | - | `{ seed, overrides, languages: [{ key, label, base }], defaultLanguage, referenceLanguage }` |
| PUT | `/api/translations/overrides` | override tree (`{ [lang]: { ...nested } }`) | `{ ok: true, overrides }` |
| POST | `/api/translations/overrides` | override tree | `{ ok: true, overrides }` |
| POST | `/api/translations/languages` | `{ key, label?, copyFrom? }` | `{ languages, overrides }` |

- `seed` / `overrides` are arbitrarily nested string trees (`Map<String, Object>`), merged deep on the client; `copyFrom` deep-clones a merged (seed + overrides) language branch into the new language's overrides.
- `languages` lists base languages (from the seed keys, or `naqqa.translations.base-languages` when the seed is empty) first, then persisted added languages; `base` flags seed languages.
- `referenceLanguage` prefers `en`, then the default language, then the first available.

## Host integration (SPI)

- `TranslationsSeedProvider.seed()` - supply the base catalog tree keyed by language. Default bean returns an empty tree; the host app provides its own bean (e.g. reading `assets/translation/translation.json` or a DB-backed catalog).
- `TranslationsStore` - persistence for overrides and added languages. Default `InMemoryTranslationsStore` keeps them in memory (parity with the OS dev-data `localStorage` store); a host can supply a DB/Redis-backed bean.

## Properties (`naqqa.translations.*`)

- `enabled` (default `true`)
- `path` (default `/api/translations`)
- `default-language` (default `ro`)
- `base-languages` (default `ro,ru,en`) - used only when the seed provider returns an empty tree
- `language-labels` - key -> display label map (defaults: ro/ru/en)

## Build

```
cd naqqa-backend-services/translations
mvn -q -o package
```
