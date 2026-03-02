# Naqqa Entity Service

This module provides shared entity models and MongoDB-backed storage for dynamic entity definitions.

## MongoDB

Configure connection in `src/main/resources/application.properties`:

```
spring.data.mongodb.host=localhost
spring.data.mongodb.port=27017
spring.data.mongodb.database=naqqa_entity
spring.data.mongodb.auto-index-creation=true
```

## Endpoints

Base path: `/api/entities`

- `POST /api/entities` create a new definition
- `POST /api/entities/bulk` create many definitions
- `GET /api/entities` list all definitions
- `GET /api/entities/{id}` get a definition by id
- `PUT /api/entities/{id}` update a definition
- `DELETE /api/entities/{id}` delete a definition

## Run

```
mvn clean package
```
