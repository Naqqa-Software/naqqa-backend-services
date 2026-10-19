# Naqqa Search (`com.naqqa:naqqa-search`)

The unified `POST /api/search` REST endpoint the naqqa-os global search and observability log/issue search call. A `web` controller maps HTTP to a `service.SearchService`, which owns the query handling (null-query fallback) and delegates matching to the JDK-only query layer in the `naqqa-elasticsearch` module (`com.naqqa.elasticsearch.os`). The request/response model matches `naqqa-os-fe/src/app/core/search/search.models.ts` (and `search.service.ts`, which posts to `${apiUrl}/search`); the `SearchModels` records are owned by `naqqa-elasticsearch`, which is this module's DTO + query-engine dependency.

Spring Boot 3.4.x library jar, own pom, Java 21. Depends on `com.naqqa:naqqa-elasticsearch`. Auto-configures on a servlet web app unless `naqqa.search.enabled=false`.

## Endpoint

Base path `naqqa.search.path` (default `/api/search`).

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| POST | `/api/search` | `SearchQuery` | `SearchResult` |

### SearchQuery

```
{
  index: string,
  text?: string,
  filters?: [ Filter ],
  sort?: [ { field, dir: "asc"|"desc" } ],
  from?: number,
  size?: number,
  searchAfter?: (string|number)[],
  aggs?: [ Agg ]
}
```

- Filter is a discriminated union on `type`: `term {field,value}`, `terms {field,values[]}`, `range {field,gte?,lte?,gt?,lt?}`, `exists {field}`, `prefix {field,value}`.
- Agg is a union on `type`: `terms {name,field,size?}`, `range {name,field,ranges:[{key?,from?,to?}]}`, `date_histogram {name,field,intervalMs,fieldUnit?:"ms"|"ns"}`.

### SearchResult

```
{
  hits: [ { id, score, source, sort } ],
  total: number,
  aggregations: { [aggName]: [ { key, count, from?, to? } ] },
  took: number
}
```

Full-text uses the same multi_match-style scoring as the OS (exact > prefix > substring per token; a query matches only if every token matches). Sort defaults to `_score desc` (when `text` is set) then `_id asc`; `from`/`size` and `searchAfter` cursor pagination both match the OS engine.

## Datasets

Queries run against datasets registered in the `SearchDatasetRegistry` by `index` name:

- The `global` index (projects, tasks, clients, pages) is registered by default, mirroring `devdata-search.ts buildGlobal()`. Disable with `naqqa.search.register-global-dataset=false`.
- A host registers more indexes (`logs`, `issues`, `audit`, ...) by exposing `com.naqqa.elasticsearch.os.SearchDataset` beans (e.g. `MapDataset` over its documents, or a dataset backed by the Lucene engine in `naqqa-elasticsearch`). All such beans are auto-registered. When an `index` has no dataset, the endpoint returns an empty result, matching the OS fallback.

## Properties (`naqqa.search.*`)

- `enabled` (default `true`)
- `path` (default `/api/search`)
- `register-global-dataset` (default `true`)

## Build

```
cd naqqa-backend-services/search
mvn -q -o package
```
