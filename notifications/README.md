# Naqqa Notifications (`com.naqqa:naqqa-notifications`)

Categorized in-app notification feed for Naqqa backend apps. Serves the list the naqqa-os notifications UI reads, with per micro-app categories, an unread count, and mark-read / delete actions. Mirrors the OS dev-data contract in `naqqa-os-fe/src/app/core/dev-data/devdata-notifications.ts`.

Spring Boot 3.4.x library jar, own pom, Java 21. Auto-configures on a servlet web app unless `naqqa.notifications.enabled=false`.

## Endpoints

Base path `naqqa.notifications.path` (default `/api/notifications`).

| Method | Path | Response |
| --- | --- | --- |
| GET | `/api/notifications?page=0&size=30` | `{ notifications: [ item ], totalItems, totalPages, currentPage, count }` |
| PUT | `/api/notifications/read-all` | `"ok"` |
| PUT | `/api/notifications/{id}/read` | `"ok"` |
| DELETE | `/api/notifications/{id}` | `"ok"` |

`count` is the current unread total. Items are sorted by `createdAt` descending.

### Notification item shape

Common fields plus category-specific extras spread at the top level (mirroring the OS mock):

```
{ id, type, category, seen, createdAt, actor, action, text, route, ...extra }
```

- `category` is one of `BOARD, BUILDER, OBSERVABILITY, CLIENTS, OFFERS, TENDERS, TIME, SHIELD, USERS, SYSTEM` (`NotificationCategory`).
- `type` examples: `TASK_ASSIGNED`, `TASK_COMMENTED`, `COMMENT_ADDED`, `COMMENT_REPLY`, `BUILDER_PUBLISHED`, `TENDER_GREAT_FIT`, `ADMIN_MESSAGE`.
- `extra` varies by type: board/builder carry `taskId/taskTitle/byName` or `pageName/author/projectName/pages`; tenders carry `tenderId/title/fitScore/country/buyer`; the rest carry `message`.

`mark-read`, `read-all` and `delete` are applied as overlays on top of the source feed, so a refetched list reflects them (seen flipped, deleted removed) while the generated seed itself is immutable.

## Host integration (SPI)

`NotificationSource.all()` returns the feed. The default `DevNotificationSource` reproduces the OS dev-data seeds (34 items, relative timestamps). A host app provides its own `NotificationSource` bean backed by its real notification store; the service, paging, unread count and overlays stay the same.

## Properties (`naqqa.notifications.*`)

- `enabled` (default `true`)
- `path` (default `/api/notifications`)

## Build

```
cd naqqa-backend-services/notifications
mvn -q -o package
```
