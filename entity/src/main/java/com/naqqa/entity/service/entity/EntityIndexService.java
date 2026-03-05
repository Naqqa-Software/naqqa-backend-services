package com.naqqa.entity.service.entity;

import com.naqqa.entity.entity.Entity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityIndexService {

    private static final String INDEX_PREFIX = "entity_idx_";

    private final MongoTemplate mongoTemplate;

    public void syncIndexes(Entity definition) {
        if (definition == null || definition.getMainDetails() == null) {
            return;
        }
        String entityKey = definition.getMainDetails().getKey();
        if (entityKey == null || entityKey.isBlank()) {
            return;
        }

        String collectionName = entityKey;
        IndexOperations indexOps = mongoTemplate.indexOps(collectionName);
        List<IndexInfo> existing = indexOps.getIndexInfo();
        Set<String> existingNames = existing.stream()
                .map(IndexInfo::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Entity.EntityIndex> indexDefs = definition.getIndexes() == null
                ? List.of()
                : definition.getIndexes();

        Set<String> desiredNames = indexDefs.stream()
                .map(this::indexName)
                .collect(Collectors.toSet());

        for (Entity.EntityIndex indexDef : indexDefs) {
            Index index = buildIndex(indexDef);
            if (index != null) {
                String name = indexName(indexDef);
                if (existingNames.contains(name)) {
                    indexOps.dropIndex(name);
                }
                indexOps.createIndex(index);
            }
        }

        // Drop indexes that are no longer present in the definition.
        for (String existingName : existingNames) {
            if ("_id_".equals(existingName)) {
                continue;
            }
            if (!desiredNames.contains(existingName)) {
                indexOps.dropIndex(existingName);
            }
        }
    }

    private Index buildIndex(Entity.EntityIndex indexDef) {
        if (indexDef == null || indexDef.getFields() == null || indexDef.getFields().isEmpty()) {
            return null;
        }
        Index index = new Index();
        for (Entity.IndexField field : indexDef.getFields()) {
            if (field == null || field.getPath() == null || field.getPath().isBlank()) {
                continue;
            }
            Sort.Direction direction = (field.getDirection() != null && field.getDirection() == -1)
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            index.on(mapIndexPath(field.getPath()), direction);
        }
        String name = indexName(indexDef);
        index.named(name);
        if (Boolean.TRUE.equals(indexDef.getUnique())) {
            index.unique();
        }
        if (Boolean.TRUE.equals(indexDef.getSparse())) {
            index.sparse();
        }
        return index;
    }

    private String mapIndexPath(String path) {
        if (path.startsWith("data.")) {
            return path;
        }
        if ("_id".equals(path) || "createdAt".equals(path) || "updatedAt".equals(path) || "createdBy".equals(path)) {
            return path;
        }
        return "data." + path;
    }

    private String indexName(Entity.EntityIndex indexDef) {
        if (indexDef.getName() != null && !indexDef.getName().isBlank()) {
            return indexDef.getName();
        }
        String signature = indexDef.getFields().stream()
                .filter(Objects::nonNull)
                .map(field -> (field.getPath() == null ? "" : field.getPath()) + ":" + field.getDirection())
                .collect(Collectors.joining("|"));
        return INDEX_PREFIX + shortHash(signature.toLowerCase(Locale.ROOT));
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed).substring(0, 10);
        } catch (Exception ex) {
            return INDEX_PREFIX + Math.abs(input.hashCode());
        }
    }
}
