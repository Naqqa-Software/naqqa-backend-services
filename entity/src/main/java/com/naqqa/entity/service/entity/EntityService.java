package com.naqqa.entity.service.entity;

import com.naqqa.entity.dto.PagedResponse;
import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.enums.TableQQFilterOperator;
import com.naqqa.entity.repository.mongo.EntityRepository;
import com.naqqa.entity.service.authorities.EntityAuthorityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EntityService {

    private static final Set<String> RESERVED_PARAMS = Set.of(
            "sort.key", "sort.dir", "page", "pageSize", "offset", "take"
    );

    private final EntityRepository entityRepository;
    private final MongoTemplate mongoTemplate;
    private final EntityRecordService recordService;
    private final EntityAuthorityService authorityService;
    private final EntityIndexService indexService;

    public Entity save(Entity entity) {
        Entity saved = entityRepository.save(entity);
        if (saved.getMainDetails() != null && saved.getMainDetails().getKey() != null) {
            recordService.ensureCollection(saved.getMainDetails().getKey());
            authorityService.ensureAuthoritiesForEntityKey(saved.getMainDetails().getKey());
            indexService.syncIndexes(saved);
        }
        return saved;
    }

    public Entity findById(String id) {
        return entityRepository.findById(id).orElse(null);
    }

    public Entity findByKey(String key) {
        return entityRepository.findByMainDetailsKey(key).orElse(null);
    }

    public PagedResponse<Entity> findAll(Map<String, String> params) {
        FilterResult filterResult = buildFilters(params);

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

        long total = mongoTemplate.count(countQuery, Entity.class);
        List<Entity> items = mongoTemplate.find(query, Entity.class);

        return new PagedResponse<>(items, total);
    }

    public void delete(String id) {
        Entity existing = entityRepository.findById(id).orElse(null);
        if (existing != null && existing.getMainDetails() != null) {
            String entityKey = existing.getMainDetails().getKey();
            recordService.deleteCollection(entityKey);
            authorityService.removeAuthoritiesForEntityKey(entityKey);
        }
        entityRepository.deleteById(id);
    }

    public List<Entity> saveAll(List<Entity> entities) {
        return entityRepository.saveAll(entities);
    }

    public Entity update(String id, Entity entity) {
        Entity existing = entityRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Entity not found"));

        entity.setId(id);

        String existingKey = existing.getMainDetails() != null ? existing.getMainDetails().getKey() : null;
        if (entity.getMainDetails() == null) {
            entity.setMainDetails(existing.getMainDetails());
            Entity saved = entityRepository.save(entity);
            recordService.syncRecordsWithSchema(saved);
            return saved;
        }

        String incomingKey = entity.getMainDetails().getKey();
        if (existingKey != null && incomingKey != null && !existingKey.equals(incomingKey)) {
            throw new IllegalArgumentException("mainDetails.key is immutable");
        }
        if (incomingKey == null && existingKey != null) {
            entity.getMainDetails().setKey(existingKey);
        }

        Entity saved = entityRepository.save(entity);
        recordService.syncRecordsWithSchema(saved);
        indexService.syncIndexes(saved);
        return saved;
    }

    public boolean canReadByKey(String key, Authentication authentication) {
        Entity entity = findByKey(key);
        if (entity == null) {
            return false;
        }
        if (entity.getApi() != null && Boolean.TRUE.equals(entity.getApi().getIsPublicGet())) {
            return true;
        }
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("entity:read".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public boolean canCreateByKey(String key, Authentication authentication) {
        Entity entity = findByKey(key);
        if (entity == null || entity.getMainDetails() == null || !Boolean.TRUE.equals(entity.getMainDetails().getIsActive())) {
            return false;
        }
        if (entity.getApi() != null && Boolean.TRUE.equals(entity.getApi().getIsPublicPost())) {
            return true;
        }
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ((key + ":create").equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public boolean canUpdateByKey(String key, Authentication authentication) {
        Entity entity = findByKey(key);
        if (entity == null || entity.getMainDetails() == null || !Boolean.TRUE.equals(entity.getMainDetails().getIsActive())) {
            return false;
        }
        if (entity.getApi() != null && Boolean.TRUE.equals(entity.getApi().getIsPublicPost())) {
            return true;
        }
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ((key + ":update").equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public boolean canReadRecordsByKey(String key, Authentication authentication) {
        Entity entity = findByKey(key);
        if (entity == null || entity.getMainDetails() == null || !Boolean.TRUE.equals(entity.getMainDetails().getIsActive())) {
            return false;
        }
        if (entity.getApi() != null && Boolean.TRUE.equals(entity.getApi().getIsPublicGet())) {
            return true;
        }
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ((key + ":read").equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private Sort buildSort(Map<String, String> params) {
        String sortKey = params.get("sort.key");
        String sortDir = params.get("sort.dir");
        if (sortKey == null || sortKey.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, sortKey);
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

    private FilterResult buildFilters(Map<String, String> params) {
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
            String value = entry.getValue();

            switch (op) {
                case EQUALS -> criteria.add(Criteria.where(field).is(parseValue(value)));
                case NOT_EQUALS -> criteria.add(Criteria.where(field).ne(parseValue(value)));
                case CONTAINS -> criteria.add(Criteria.where(field)
                        .regex(Pattern.quote(value == null ? "" : value), "i"));
                case STARTS_WITH -> criteria.add(Criteria.where(field)
                        .regex("^" + Pattern.quote(value == null ? "" : value), "i"));
                case ENDS_WITH -> criteria.add(Criteria.where(field)
                        .regex(Pattern.quote(value == null ? "" : value) + "$", "i"));
                case GREATER_THAN -> criteria.add(Criteria.where(field).gt(parseValue(value)));
                case GREATER_THAN_OR_EQUAL -> criteria.add(Criteria.where(field).gte(parseValue(value)));
                case LESS_THAN -> criteria.add(Criteria.where(field).lt(parseValue(value)));
                case LESS_THAN_OR_EQUAL -> criteria.add(Criteria.where(field).lte(parseValue(value)));
                case IN -> criteria.add(Criteria.where(field).in(parseList(value)));
                case NOT_IN -> criteria.add(Criteria.where(field).nin(parseList(value)));
                case BETWEEN_START -> ranges.computeIfAbsent(field, k -> new RangeBounds()).start = parseValue(value);
                case BETWEEN_END -> ranges.computeIfAbsent(field, k -> new RangeBounds()).end = parseValue(value);
                case IS_NULL -> criteria.add(Criteria.where(field).is(null));
                case IS_NOT_NULL -> criteria.add(Criteria.where(field).ne(null));
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

    private record Pagination(int offset, int limit) {
    }

    private record FilterResult(List<Criteria> criteria, Map<String, RangeBounds> ranges) {
    }

    private static class RangeBounds {
        private Object start;
        private Object end;
    }
}
