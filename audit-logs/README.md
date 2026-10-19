# Naqqa Audit Logs (`com.naqqa:naqqa-audit-logs`)

Activity / audit log feed (jurnale) for Naqqa backend apps. Serves the rows the naqqa-os activity-log UI reads from `GET /api/logs`, using the `UserLog` domains and types the OS expects. Mirrors the OS dev-data contract: the `/api/logs` route in `naqqa-os-fe/src/app/core/dev-data/devdata-work.ts` and the `UserLog` / `UserLogType` / `UserLogDomain` shapes in `.../features/portal/shared/interfaces/user-log.interface.ts` (the full domain/type matrix also appears in `devdata-audit.ts`).

Spring Boot 3.4.x library jar, own pom, Java 21. Auto-configures on a servlet web app unless `naqqa.audit-logs.enabled=false`.

## Endpoint

Base path `naqqa.audit-logs.path` (default `/api/logs`).

| Method | Path | Response |
| --- | --- | --- |
| GET | `/api/logs?page=0&size=20` | `{ logs: [ UserLog ], total, totalItems, totalPages, page, currentPage, size }` |

Optional `domain` and `type` query params filter the feed (case-insensitive); omitting them returns the whole feed, matching the OS mock.

### UserLog shape

```
{ id, userId, userName, type, domain, body, createdAt }
```

- `type` is a `UserLogType` name, `domain` a `UserLogDomain` name, derived from `UserLogType.getDomain()`.
- `UserLogDomain`: `COMMENT, TASK, BUILDER, ENTITY, OFFER, SECURITY, SHIELD`.
- `UserLogType`: 29 values across those domains (`COMMENT_ADDED`, `TASK_ASSIGNED`, `BUILDER_PUBLISHED`, `ENTITY_*`, `OFFER_STATUS_CHANGED`, `ROLE_*`, `PERMISSION_*`, `USER_*`, `SHIELD_*`), matching the OS `UserLogType` enum.
- `body` is a free-form `Map<String, Object>` whose keys depend on the type (e.g. `pageName/text`, `taskTitle`, `projectName/pages`, `entityKey`, `offerName/status`, `role`, `targetUserId/roles`, `siteName`, `kind/keyId/graceHours`, `email`, `permission`).

## Host integration (SPI)

`AuditLogSource.all()` returns the log rows. The default `DevAuditLogSource` reproduces the 12 OS dev-data seed rows. A host app provides its own `AuditLogSource` bean backed by its persisted audit store; the service, filtering and paging stay the same.

## Properties (`naqqa.audit-logs.*`)

- `enabled` (default `true`)
- `path` (default `/api/logs`)

## Build

```
cd naqqa-backend-services/audit-logs
mvn -q -o package
```
