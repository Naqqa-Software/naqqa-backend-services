package com.naqqa.entity.service.entity;

import com.naqqa.entity.dto.PagedResponse;
import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.entity.EntityRecord;
import com.naqqa.entity.entity.Entity.UniqueConstraint;
import com.naqqa.entity.entity.Entity.PathRef;
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
            "sort.key", "sort.dir", "page", "pageSize", "offset", "take", "language"
    );

    private final MongoTemplate mongoTemplate;
    private final EntityRepository entityRepository;

    public PagedResponse<EntityRecord> findAll(String entityKey, Map<String, String> params) {
        Entity definition = getDefinition(entityKey);
        boolean allowFilters = definition.getApi() != null && Boolean.TRUE.equals(definition.getApi().getAllowFilters());
        boolean allowSort = definition.getApi() != null && Boolean.TRUE.equals(definition.getApi().getAllowSort());
        boolean allowSearch = definition.getApi() != null && Boolean.TRUE.equals(definition.getApi().getAllowSearch());
        String language = params.get("language");
        Map<String, Boolean> avoidTranslateMap = buildAvoidTranslateMap(definition);

        FilterResult filterResult = allowFilters
                ? buildFilters(params, allowSearch, language, avoidTranslateMap)
                : new FilterResult(List.of(), Map.of());

        Query query = new Query();
        if (!filterResult.criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(filterResult.criteria.toArray(new Criteria[0])));
        }
        applyRanges(query, filterResult, language, avoidTranslateMap);

        if (allowSort) {
            Sort sort = buildSort(definition, params, language, avoidTranslateMap);
            if (sort != null) {
                query.with(sort);
            }
        }

        Pagination pagination = buildPagination(params);
        query.skip(pagination.offset).limit(pagination.limit);

        Query countQuery = new Query();
        if (!filterResult.criteria.isEmpty()) {
            countQuery.addCriteria(new Criteria().andOperator(filterResult.criteria.toArray(new Criteria[0])));
        }
        applyRanges(countQuery, filterResult, language, avoidTranslateMap);

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
        validateUniqueConstraints(definition, record.getData(), null);
        return mongoTemplate.insert(record, collectionName(entityKey));
    }

    public EntityRecord update(String entityKey, String id, Map<String, Object> payload) {
        Entity definition = getDefinition(entityKey);
        EntityRecord existing = mongoTemplate.findById(id, EntityRecord.class, collectionName(entityKey));
        if (existing == null) {
            throw new IllegalArgumentException("Record not found");
        }
        existing.setData(normalizePayload(definition, payload, existing.getData()));
        validateUniqueConstraints(definition, existing.getData(), id);
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
        if (payload == null) {
            return existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        }
        if (existing == null) {
            return new LinkedHashMap<>(payload);
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(payload);
        return merged;
    }

    private FilterResult buildFilters(Map<String, String> params, boolean allowSearch, String language, Map<String, Boolean> avoidTranslateMap) {
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
            if (!allowSearch && (op == TableQQFilterOperator.CONTAINS
                    || op == TableQQFilterOperator.STARTS_WITH
                    || op == TableQQFilterOperator.ENDS_WITH)) {
                continue;
            }

            String field = key.substring(0, key.length() - op.getSuffix().length());
            String value = entry.getValue();

            if (op == TableQQFilterOperator.BETWEEN_START || op == TableQQFilterOperator.BETWEEN_END) {
                RangeBounds bounds = ranges.computeIfAbsent(field, k -> new RangeBounds());
                if (op == TableQQFilterOperator.BETWEEN_START) {
                    bounds.start = parseValue(value);
                } else {
                    bounds.end = parseValue(value);
                }
                continue;
            }

            List<String> paths = resolveFilterPaths(field, language, avoidTranslateMap);
            Criteria built = buildCriteriaForPaths(op, paths, value);
            if (built != null) {
                criteria.add(built);
            }
        }

        return new FilterResult(criteria, ranges);
    }

    private List<String> resolveFilterPaths(String rawPath, String language, Map<String, Boolean> avoidTranslateMap) {
        String normalized = mapDataPath(rawPath);
        if (language == null || language.isBlank() || isSystemField(normalized)) {
            return List.of(normalized);
        }
        String lookup = normalized.startsWith("data.") ? normalized.substring(5) : normalized;
        Boolean avoid = avoidTranslateMap.get(lookup);
        if (Boolean.TRUE.equals(avoid)) {
            return List.of(normalized);
        }
        return List.of(normalized + "." + language, normalized);
    }

    private Criteria buildCriteriaForPaths(TableQQFilterOperator op, List<String> paths, String value) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        List<Criteria> ors = new ArrayList<>();
        for (String path : paths) {
            Criteria c;
            switch (op) {
                case EQUALS -> c = Criteria.where(path).is(parseValue(value));
                case NOT_EQUALS -> c = Criteria.where(path).ne(parseValue(value));
                case CONTAINS -> c = Criteria.where(path)
                        .regex(Pattern.quote(value == null ? "" : value), "i");
                case STARTS_WITH -> c = Criteria.where(path)
                        .regex("^" + Pattern.quote(value == null ? "" : value), "i");
                case ENDS_WITH -> c = Criteria.where(path)
                        .regex(Pattern.quote(value == null ? "" : value) + "$", "i");
                case GREATER_THAN -> c = Criteria.where(path).gt(parseValue(value));
                case GREATER_THAN_OR_EQUAL -> c = Criteria.where(path).gte(parseValue(value));
                case LESS_THAN -> c = Criteria.where(path).lt(parseValue(value));
                case LESS_THAN_OR_EQUAL -> c = Criteria.where(path).lte(parseValue(value));
                case IN -> c = Criteria.where(path).in(parseList(value));
                case NOT_IN -> c = Criteria.where(path).nin(parseList(value));
                case IS_NULL -> c = Criteria.where(path).is(null);
                case IS_NOT_NULL -> c = Criteria.where(path).ne(null);
                default -> c = null;
            }
            if (c != null) {
                ors.add(c);
            }
        }
        if (ors.isEmpty()) {
            return null;
        }
        if (ors.size() == 1) {
            return ors.get(0);
        }
        return new Criteria().orOperator(ors.toArray(new Criteria[0]));
    }

    private Sort buildSort(Entity definition, Map<String, String> params, String language, Map<String, Boolean> avoidTranslateMap) {
        String sortKey = params.get("sort.key");
        String sortDir = params.get("sort.dir");
        if (sortKey == null || sortKey.isBlank()) {
            if (definition != null && definition.getUi() != null && definition.getUi().getDefaultSort() != null) {
                String defaultPath = definition.getUi().getDefaultSort().getPath();
                String defaultDir = definition.getUi().getDefaultSort().getDirection();
                if (defaultPath != null && !defaultPath.isBlank()) {
                    return buildSortForPath(defaultPath, defaultDir, language, avoidTranslateMap);
                }
            }
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return buildSortForPath(sortKey, sortDir, language, avoidTranslateMap);
    }

    private Sort buildSortForPath(String sortKey, String sortDir, String language, Map<String, Boolean> avoidTranslateMap) {
        List<String> paths = resolveFilterPaths(sortKey, language, avoidTranslateMap);
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        if (paths.size() == 1) {
            return Sort.by(direction, paths.get(0));
        }
        return Sort.by(direction, paths.get(0)).and(Sort.by(direction, paths.get(1)));
    }

    private void applyRanges(Query query, FilterResult filterResult, String language, Map<String, Boolean> avoidTranslateMap) {
        for (Map.Entry<String, RangeBounds> entry : filterResult.ranges.entrySet()) {
            String rawField = entry.getKey();
            RangeBounds bounds = entry.getValue();
            if (bounds == null || (bounds.start == null && bounds.end == null)) {
                continue;
            }
            List<String> paths = resolveFilterPaths(rawField, language, avoidTranslateMap);
            List<Criteria> ors = new ArrayList<>();
            for (String path : paths) {
                ors.add(buildRangeCriteria(path, bounds));
            }
            if (ors.size() == 1) {
                query.addCriteria(ors.get(0));
            } else {
                query.addCriteria(new Criteria().orOperator(ors.toArray(new Criteria[0])));
            }
        }
    }

    private Criteria buildRangeCriteria(String field, RangeBounds bounds) {
        if (bounds.start != null && bounds.end != null) {
            return Criteria.where(field).gte(bounds.start).lte(bounds.end);
        }
        if (bounds.start != null) {
            return Criteria.where(field).gte(bounds.start);
        }
        return Criteria.where(field).lte(bounds.end);
    }

    private Map<String, Boolean> buildAvoidTranslateMap(Entity definition) {
        Map<String, Boolean> result = new HashMap<>();
        if (definition == null || definition.getFields() == null) {
            return result;
        }
        for (Entity.EntityField field : definition.getFields()) {
            if (field == null || field.getKey() == null || field.getKey().isBlank()) {
                continue;
            }
            String path;
            if (field.getGroupName() != null && !field.getGroupName().isBlank()) {
                path = field.getGroupName() + "." + field.getKey();
            } else if (field.getArrayGroupName() != null && !field.getArrayGroupName().isBlank()) {
                path = field.getArrayGroupName() + "." + field.getKey();
            } else {
                path = field.getKey();
            }
            boolean avoid = field.getProps() != null && Boolean.TRUE.equals(field.getProps().getAvoidTranslate());
            result.put(path, avoid);
        }
        return result;
    }

    private String mapDataPath(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        if (key.startsWith("data.")) {
            return key;
        }
        if (isSystemField(key)) {
            return key;
        }
        return "data." + key;
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

    private void validateUniqueConstraints(Entity definition, Map<String, Object> data, String recordId) {
        if (definition.getUniqueConstraints() == null || definition.getUniqueConstraints().isEmpty()) {
            return;
        }
        for (UniqueConstraint constraint : definition.getUniqueConstraints()) {
            List<PathRef> fields = constraint.getFields();
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            Criteria criteria = new Criteria();
            List<Criteria> clauses = new ArrayList<>();

            for (PathRef ref : fields) {
                if (ref == null || ref.getPath() == null || ref.getPath().isBlank()) {
                    continue;
                }
                Object value = getValueByPath(data, ref.getPath());
                if (value == null) {
                    clauses.clear();
                    break;
                }
                clauses.add(buildValueCriteria("data." + ref.getPath(), value, Boolean.TRUE.equals(constraint.getCaseInsensitive())));
            }

            if (clauses.isEmpty()) {
                continue;
            }

            List<PathRef> scopeFields = constraint.getScopeFields();
            if (scopeFields != null) {
                for (PathRef ref : scopeFields) {
                    if (ref == null || ref.getPath() == null || ref.getPath().isBlank()) {
                        continue;
                    }
                    Object value = getValueByPath(data, ref.getPath());
                    clauses.add(buildValueCriteria("data." + ref.getPath(), value, Boolean.TRUE.equals(constraint.getCaseInsensitive())));
                }
            }

            criteria.andOperator(clauses.toArray(new Criteria[0]));
            Query query = new Query(criteria);
            if (recordId != null) {
                query.addCriteria(Criteria.where("_id").ne(recordId));
            }

            long count = mongoTemplate.count(query, EntityRecord.class, collectionName(definition.getMainDetails().getKey()));
            if (count > 0) {
                throw new IllegalArgumentException("Entity.Errors.UniqueConstraint");
            }
        }
    }

    private Criteria buildValueCriteria(String path, Object value, boolean caseInsensitive) {
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>();
            for (Object item : collection) {
                if (item != null) {
                    values.add(item);
                }
            }
            if (values.isEmpty()) {
                return Criteria.where(path).is(null);
            }
            if (caseInsensitive && values.stream().allMatch(v -> v instanceof String)) {
                List<Criteria> ors = new ArrayList<>();
                for (Object item : values) {
                    ors.add(Criteria.where(path).regex("^" + Pattern.quote(item.toString()) + "$", "i"));
                }
                return new Criteria().orOperator(ors.toArray(new Criteria[0]));
            }
            return Criteria.where(path).in(values);
        }
        if (caseInsensitive && value instanceof String str) {
            return Criteria.where(path).regex("^" + Pattern.quote(str) + "$", "i");
        }
        return Criteria.where(path).is(value);
    }

    private Object getValueByPath(Map<String, Object> data, String path) {
        if (data == null || path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = data;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private record Pagination(int offset, int limit) {
    }

    private record FilterResult(List<Criteria> criteria, Map<String, RangeBounds> ranges) {
    }

    private static class RangeBounds {
        private Object start;
        private Object end;
    }

    public Map<String, Object> filterDataForPublic(String entityKey, Map<String, Object> data) {
        Entity definition = getDefinition(entityKey);
        if (definition.getApi() == null || !Boolean.TRUE.equals(definition.getApi().getIsPublicGet())) {
            return data;
        }
        if (definition.getFields() == null) {
            return Map.of();
        }
        Set<String> allowedPaths = new LinkedHashSet<>();
        for (Entity.EntityField field : definition.getFields()) {
            if (field == null || field.getKey() == null || field.getKey().isBlank()) {
                continue;
            }
            if (field.getProps() == null || !Boolean.TRUE.equals(field.getProps().getGetPublicApi())) {
                continue;
            }
            String path;
            if (field.getGroupName() != null && !field.getGroupName().isBlank()) {
                path = field.getGroupName() + "." + field.getKey();
            } else if (field.getArrayGroupName() != null && !field.getArrayGroupName().isBlank()) {
                path = field.getArrayGroupName() + "." + field.getKey();
            } else {
                path = field.getKey();
            }
            allowedPaths.add(path);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String path : allowedPaths) {
            Object value = extractByPath(data, path);
            if (value != null) {
                putByPath(result, path, value);
            }
        }
        return result;
    }

    private Object extractByPath(Map<String, Object> source, String path) {
        if (source == null || path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        return extractByParts(source, parts, 0);
    }

    private Object extractByParts(Object current, String[] parts, int index) {
        if (current == null) {
            return null;
        }
        if (index >= parts.length) {
            return current;
        }
        String part = parts[index];
        if (current instanceof Map<?, ?> map) {
            Object next = map.get(part);
            return extractByParts(next, parts, index + 1);
        }
        if (current instanceof List<?> list) {
            List<Object> projected = new ArrayList<>();
            for (Object item : list) {
                Object extracted = extractByParts(item, parts, index);
                if (extracted != null) {
                    projected.add(extracted);
                }
            }
            return projected.isEmpty() ? null : projected;
        }
        return null;
    }

    private void putByPath(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = current.get(part);
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(part, created);
                current = created;
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = (Map<String, Object>) next;
                current = existing;
            }
        }
        current.put(parts[parts.length - 1], value);
    }
}
