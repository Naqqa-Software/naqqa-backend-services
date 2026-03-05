package com.naqqa.entity.controller;

import com.naqqa.entity.dto.PagedResponse;
import com.naqqa.entity.entity.EntityRecord;
import com.naqqa.entity.service.entity.EntityRecordService;
import com.naqqa.entity.service.entity.EntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/entities/{entityKey}/records")
public class EntityRecordController {

    private final EntityRecordService recordService;
    private final EntityService entityService;

    @PostMapping
    @PreAuthorize("@entityService.canCreateByKey(#entityKey, authentication)")
    public ResponseEntity<EntityRecord> create(
            @PathVariable String entityKey,
            @RequestBody Map<String, Object> payload
    ) {
        return ResponseEntity.ok(recordService.create(entityKey, payload));
    }

    @GetMapping
    @PreAuthorize("@entityService.canReadRecordsByKey(#entityKey, authentication)")
    public ResponseEntity<PagedResponse<EntityRecord>> getAll(
            @PathVariable String entityKey,
            @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(recordService.findAll(entityKey, params));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@entityService.canReadRecordsByKey(#entityKey, authentication)")
    public ResponseEntity<EntityRecord> getById(
            @PathVariable String entityKey,
            @PathVariable String id
    ) {
        EntityRecord record = recordService.getById(entityKey, id);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(record);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@entityService.canUpdateByKey(#entityKey, authentication)")
    public ResponseEntity<EntityRecord> update(
            @PathVariable String entityKey,
            @PathVariable String id,
            @RequestBody Map<String, Object> payload
    ) {
        return ResponseEntity.ok(recordService.update(entityKey, id, payload));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority(#entityKey + ':delete')")
    public ResponseEntity<Void> delete(
            @PathVariable String entityKey,
            @PathVariable String id
    ) {
        recordService.delete(entityKey, id);
        return ResponseEntity.noContent().build();
    }
}
