# Naqqa Entity Service

This module provides shared entity models and MongoDB-backed storage for dynamic entity definitions and records.

## MongoDB

Configure connection in `src/main/resources/application.properties`:

```
spring.data.mongodb.host=localhost
spring.data.mongodb.port=27017
spring.data.mongodb.database=naqqa_entity
spring.data.mongodb.auto-index-creation=true
```

## Endpoints

### Entity definitions
Base path: `/api/entities`

- `POST /api/entities` create a new definition
- `GET /api/entities` list all definitions
- `GET /api/entities/{id}` get a definition by id
- `PUT /api/entities/{id}` update a definition
- `DELETE /api/entities/{id}` delete a definition

### Entity records
Base path: `/api/entities/{entityKey}/records`

- `POST /api/entities/{entityKey}/records` create a record
- `GET /api/entities/{entityKey}/records` list records
- `PUT /api/entities/{entityKey}/records/{id}` update a record
- `DELETE /api/entities/{entityKey}/records/{id}` delete a record

## Filtering/sorting examples

```
GET /api/entities?sort.key=mainDetails.key&sort.dir=asc&page=0&pageSize=10&mainDetails.key_contains=test
```

```
GET /api/entities/students/records?sort.key=createdAt&sort.dir=desc&page=0&pageSize=10&firstName_contains=alex
```

## Record payload example

```
POST /api/entities/students/records
{
  "firstName": "Alex",
  "lastName": "Popescu",
  "age": 21,
  "address": "street:Main St,zip:12345",
  "courses": "math,physics"
}
```

## Run

```
mvn clean package
```
