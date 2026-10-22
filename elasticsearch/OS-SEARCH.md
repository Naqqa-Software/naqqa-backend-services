# OS search query layer (`com.naqqa.elasticsearch.os`)

This package adds the query-layer contract behind the naqqa-os `POST /api/search` endpoint to the
otherwise JDK-only engine. It is pure JDK (no Spring), consistent with the rest of this module; the
REST endpoint itself lives in the thin `naqqa-search` module, which depends on this one.

Mirrors `naqqa-os-fe/src/app/core/search/*.ts`:

- `SearchModels` - `SearchQuery`, `SearchFilter` (term/terms/range/exists/prefix via a `type`
  discriminator), `SortClause`, `AggRequest` (terms/range/date_histogram), `SearchHit`,
  `FacetBucket`, `SearchResult`. Matches `search.models.ts` field-for-field so it deserializes the
  OS request JSON directly.
- `SearchText` - diacritic folding, tokenization and the exact>prefix>substring token scoring from
  `search-text.ts`.
- `SearchDataset` / `MapDataset` - a searchable index over plain JSON documents
  (`Map<String,Object>`), with dotted-path field access and a configurable full-text body.
- `SearchExecutor` - `runSearch` ported from `search-engine.ts`: filtering, scoring, multi-key sort
  with `_score`/`_id` tie-breakers, `from`/`size` and `searchAfter` pagination, and
  terms/range/date_histogram aggregations.
- `SearchDatasetRegistry` - registers datasets by index name and runs a `SearchQuery`, returning an
  empty result for unknown indexes (the OS fallback).
- `GlobalDocs` - the default `global` dataset (projects/tasks/clients/pages) from
  `devdata-search.ts buildGlobal()`.

A host can either register `MapDataset`s over its own documents or implement `SearchDataset` on top
of the Lucene-backed engine in this module to make `POST /api/search` truly engine-backed, without
changing the contract.
