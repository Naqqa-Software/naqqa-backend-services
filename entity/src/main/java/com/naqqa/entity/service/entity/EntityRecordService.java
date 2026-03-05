package com.naqqa.entity.service.entity;

import com.naqqa.entity.dto.PagedResponse;
import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.entity.EntityRecord;
import com.naqqa.entity.enums.TableQQFilterOperator;
import com.naqqa.entity.repository.mongo.EntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EntityRecordService {

    private static final Set<String> RESERVED_PARAMS = Set.of(
            "sort.key", "sort.dir", "page", "pageSize", "offset", "take"
    );

    private final MongoTemplate mongoTemplate;
    private final EntityRepository entityRepository;

    public PagedResponse<EntityRecord> findAll(String entityKey, Map<String, String> params) {
        Entity definition = getDefinition(entityKey);
        FilterResult filterResult = buildFilters(params, definition);

        Query query = new Query();
        if (!filterResult.criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(filterResult.criteria.toArray(new Criteria[0])));
        }
        applyRanges(query, filterResult);

        Sort sort = buildSort(params);
        if (sort != null) {
            query.with(sort);
        }

        Pagination pagination = buildPagination(params);
        query.skip(pagination.offset).limit(pagination.limit);

        Query countQuery = new Query();
        if (!filterResult.criteria.isEmpty()) {
            countQuery.addCriteria(new Criteria().andOperator(filterResult.criteria.toArray(new Criteria[0])));
        }
        applyRanges(countQuery, filterResult);

        long total = mongoTemplate.count(countQuery, EntityRecord.class, collectionName(entityKey));
        List<EntityRecord> items = mongoTemplate.find(query, EntityRecord.class, collectionName(entityKey));

        return new PagedResponse<>(items, total);
    }

    public EntityRecord getById(String entityKey, String id) {
        return mongoTemplate.findById(id, EntityRecord.class, collectionName(entityKey));
    }

    public EntityRecord create(String entityKey, Map<String, Object> payload) {
        Entity definition = getDefinition(entityKey);
        EntityRecord record = new EntityRecord();
        record.setEntity(definition);
        record.setEntityKey(entityKey);
        record.setData(normalizePayload(definition, payload, null));
        return mongoTemplate.insert(record, collectionName(entityKey));
    }

    public EntityRecord update(String entityKey, String id, Map<String, Object> payload) {
        Entity definition = getDefinition(entityKey);
        EntityRecord existing = mongoTemplate.findById(id, EntityRecord.class, collectionName(entityKey));
        if (existing == null) {
            throw new IllegalArgumentException("Record not found");
        }
        existing.setData(normalizePayload(definition, payload, existing.getData()));
        return mongoTemplate.save(existing, collectionName(entityKey));
    }

    public void delete(String entityKey, String id) {
        Query query = new Query(Criteria.where("_id").is(id));
        mongoTemplate.remove(query, collectionName(entityKey));
    }

    public void deleteCollection(String entityKey) {
        if (mongoTemplate.collectionExists(collectionName(entityKey))) {
            mongoTemplate.dropCollection(collectionName(entityKey));
        }
    }

    public void syncRecordsWithSchema(Entity definition) {
        String entityKey = definition.getMainDetails().getKey();
        if (!mongoTemplate.collectionExists(collectionName(entityKey))) {
            return;
        }
        List<EntityRecord> records = mongoTemplate.findAll(EntityRecord.class, collectionName(entityKey));
        for (EntityRecord record : records) {
            record.setData(normalizePayload(definition, record.getData(), record.getData()));
            mongoTemplate.save(record, collectionName(entityKey));
        }
    }

    public void ensureCollection(String entityKey) {
        if (!mongoTemplate.collectionExists(collectionName(entityKey))) {
            mongoTemplate.createCollection(collectionName(entityKey));
        }
    }

    private Entity getDefinition(String entityKey) {
        return entityRepository.findByMainDetailsKey(entityKey)
                .orElseThrow(() -> new IllegalArgumentException("Entity not found"));
    }

    private Map<String, Object> normalizePayload(Entity definition, Map<String, Object> payload, Map<String, Object> existing) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> source = payload == null ? Map.of() : payload;
        Map<String, Object> fallback = existing == null ? Map.of() : existing;

        for (Entity.EntityField field : definition.getFields()) {
            String key = field.getKey();
            Object value = source.containsKey(key) ? source.get(key) : fallback.get(key);

            if (value == null && field.getDefaultValue() != null) {
                value = field.getDefaultValue();
            }

            result.put(key, normalizeValue(field.getType(), value));
        }
        return result;
    }

    private Object normalizeValue(String type, Object value) {
        if (value == null || type == null) {
            return value;
        }
        String typeLower = type.toLowerCase(Locale.ROOT);
        if ("object".equals(typeLower)) {
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            if (value instanceof String str) {
                return parseObject(str);
            }
        }
        if ("array".equals(typeLower)) {
            if (value instanceof Collection<?> col) {
                return new ArrayList<>(col);
            }
            if (value instanceof String str) {
                return parseArray(str);
            }
        }
        if ("number".equals(typeLower)) {
            return parseNumber(value);
        }
        if ("boolean".equals(typeLower)) {
            return parseBoolean(value);
        }
        return value;
    }

    private Map<String, Object> parseObject(String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        String[] pairs = value.split(",");
        for (String pair : pairs) {
            String[] parts = pair.split(":", 2);
            String key = parts[0].trim();
            String val = parts.length > 1 ? parts[1].trim() : "";
            result.put(key, parseValue(val));
        }
        return result;
    }

    private List<Object> parseArray(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<Object> results = new ArrayList<>(parts.length);
        for (String part : parts) {
            results.add(parseValue(part.trim()));
        }
        return results;
    }

    private Object parseNumber(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private Object parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return value;
    }

    private Sort buildSort(Map<String, String> params) {
        String sortKey = params.get("sort.key");
        String sortDir = params.get("sort.dir");
        if (sortKey == null || sortKey.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String key = isSystemField(sortKey) ? sortKey : "data." + sortKey;
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, key);
    }

    private boolean isSystemField(String key) {
        return "createdAt".equals(key) || "updatedAt".equals(key) || "createdBy".equals(key) || "_id".equals(key);
    }

    private Pagination buildPagination(Map<String, String> params) {
        Integer offset = parseInt(params.get("offset"));
        Integer take = parseInt(params.get("take"));
        if (offset != null || take != null) {
            return new Pagination(offset == null ? 0 : offset, take == null ? 10 : take);
        }

        Integer page = parseInt(params.get("page"));
        Integer pageSize = parseInt(params.get("pageSize"));
        int p = page == null ? 0 : page;
        int size = pageSize == null ? 10 : pageSize;
        return new Pagination(p * size, size);
    }

    private FilterResult buildFilters(Map<String, String> params, Entity definition) {
        List<Criteria> criteria = new ArrayList<>();
        Map<String, RangeBounds> ranges = new HashMap<>();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_PARAMS.contains(key)) {
                continue;
            }

            TableQQFilterOperator op = TableQQFilterOperator.fromParam(key);
            if (op == null) {
                continue;
            }

            String field = key.substring(0, key.length() - op.getSuffix().length());
            String fieldPath = "data." + field;
            String value = entry.getValue();

            switch (op) {
                case EQUALS -> criteria.add(Criteria.where(fieldPath).is(parseValue(value)));
                case NOT_EQUALS -> criteria.add(Criteria.where(fieldPath).ne(parseValue(value)));
                case CONTAINS -> criteria.add(Criteria.where(fieldPath)
                        .regex(Pattern.quote(value == null ? "" : value), "i"));
                case STARTS_WITH -> criteria.add(Criteria.where(fieldPath)
                        .regex("^" + Pattern.quote(value == null ? "" : value), "i"));
                case ENDS_WITH -> criteria.add(Criteria.where(fieldPath)
                        .regex(Pattern.quote(value == null ? "" : value) + "$", "i"));
                case GREATER_THAN -> criteria.add(Criteria.where(fieldPath).gt(parseValue(value)));
                case GREATER_THAN_OR_EQUAL -> criteria.add(Criteria.where(fieldPath).gte(parseValue(value)));
                case LESS_THAN -> criteria.add(Criteria.where(fieldPath).lt(parseValue(value)));
                case LESS_THAN_OR_EQUAL -> criteria.add(Criteria.where(fieldPath).lte(parseValue(value)));
                case IN -> criteria.add(Criteria.where(fieldPath).in(parseList(value)));
                case NOT_IN -> criteria.add(Criteria.where(fieldPath).nin(parseList(value)));
                case BETWEEN_START -> ranges.computeIfAbsent(fieldPath, k -> new RangeBounds()).start = parseValue(value);
                case BETWEEN_END -> ranges.computeIfAbsent(fieldPath, k -> new RangeBounds()).end = parseValue(value);
                case IS_NULL -> criteria.add(Criteria.where(fieldPath).is(null));
                case IS_NOT_NULL -> criteria.add(Criteria.where(fieldPath).ne(null));
            }
        }

        return new FilterResult(criteria, ranges);
    }

    private void applyRanges(Query query, FilterResult filterResult) {
        for (Map.Entry<String, RangeBounds> entry : filterResult.ranges.entrySet()) {
            String field = entry.getKey();
            RangeBounds bounds = entry.getValue();
            if (bounds.start != null && bounds.end != null) {
                query.addCriteria(Criteria.where(field).gte(bounds.start).lte(bounds.end));
            } else if (bounds.start != null) {
                query.addCriteria(Criteria.where(field).gte(bounds.start));
            } else if (bounds.end != null) {
                query.addCriteria(Criteria.where(field).lte(bounds.end));
            }
        }
    }

    private List<Object> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<Object> results = new ArrayList<>(parts.length);
        for (String part : parts) {
            results.add(parseValue(part.trim()));
        }
        return results;
    }

    private Object parseValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed;
        }
    }

    private Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String collectionName(String entityKey) {
        return entityKey;
    }

    private record Pagination(int offset, int limit) {
    }

    private record FilterResult(List<Criteria> criteria, Map<String, RangeBounds> ranges) {
    }

    private static class RangeBounds {
        private Object start;
        private Object end;
    }
}

