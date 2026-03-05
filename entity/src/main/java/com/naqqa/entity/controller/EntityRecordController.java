package com.naqqa.entity.controller;

import com.naqqa.entity.dto.EntityRecordResponse;
import com.naqqa.entity.dto.PagedResponse;
import com.naqqa.entity.entity.EntityRecord;
import com.naqqa.entity.service.entity.EntityRecordService;
import com.naqqa.entity.service.entity.EntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/entities/{entityKey}/records")
public class EntityRecordController {

    private final EntityRecordService recordService;
    private final EntityService entityService;

    @PostMapping
    @PreAuthorize("@entityService.canCreateByKey(#entityKey, authentication)")
    public ResponseEntity<EntityRecordResponse> create(
            @PathVariable String entityKey,
            @RequestBody Map<String, Object> payload
    ) {
        EntityRecord record = recordService.create(entityKey, payload);
        return ResponseEntity.ok(toResponse(entityKey, record));
    }

    @GetMapping
    @PreAuthorize("@entityService.canReadPrivateRecordsByKey(#entityKey, authentication)")
    public ResponseEntity<PagedResponse<EntityRecordResponse>> getAll(
            @PathVariable String entityKey,
            @RequestParam Map<String, String> params
    ) {
        PagedResponse<EntityRecord> page = recordService.findAll(entityKey, params);
        List<EntityRecordResponse> items = page.items().stream()
                .map(this::toResponseFull)
                .toList();
        return ResponseEntity.ok(new PagedResponse<>(items, page.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@entityService.canReadPrivateRecordsByKey(#entityKey, authentication)")
    public ResponseEntity<EntityRecordResponse> getById(
            @PathVariable String entityKey,
            @PathVariable String id
    ) {
        EntityRecord record = recordService.getById(entityKey, id);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toResponseFull(record));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@entityService.canUpdateByKey(#entityKey, authentication)")
    public ResponseEntity<EntityRecordResponse> update(
            @PathVariable String entityKey,
            @PathVariable String id,
            @RequestBody Map<String, Object> payload
    ) {
        EntityRecord record = recordService.update(entityKey, id, payload);
        return ResponseEntity.ok(toResponseFull(record));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@entityService.canUpdateByKey(#entityKey, authentication)")
    public ResponseEntity<Void> delete(
            @PathVariable String entityKey,
            @PathVariable String id
    ) {
        recordService.delete(entityKey, id);
        return ResponseEntity.noContent().build();
    }

    private EntityRecordResponse toResponse(String entityKey, EntityRecord record) {
        Map<String, Object> data = recordService.filterDataForPublic(entityKey, record.getData());
        return new EntityRecordResponse(record.getId(), record.getEntityKey(), data, record.getCreatedAt());
    }

    private EntityRecordResponse toResponseFull(EntityRecord record) {
        return new EntityRecordResponse(record.getId(), record.getEntityKey(), record.getData(), record.getCreatedAt());
    }
}
