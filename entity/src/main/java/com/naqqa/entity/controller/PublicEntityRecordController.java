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
@RequestMapping("/api/public/entities/{entityKey}/records")
public class PublicEntityRecordController {

    private final EntityRecordService recordService;
    private final EntityService entityService;

    @GetMapping
    @PreAuthorize("@entityService.isPublicGetActive(#entityKey)")
    public ResponseEntity<PagedResponse<EntityRecordResponse>> getAll(
            @PathVariable String entityKey,
            @RequestParam Map<String, String> params
    ) {
        PagedResponse<EntityRecord> page = recordService.findAll(entityKey, params);
        List<EntityRecordResponse> items = page.items().stream()
                .map(record -> toResponsePublic(entityKey, record))
                .toList();
        return ResponseEntity.ok(new PagedResponse<>(items, page.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@entityService.isPublicGetActive(#entityKey)")
    public ResponseEntity<EntityRecordResponse> getById(
            @PathVariable String entityKey,
            @PathVariable String id
    ) {
        EntityRecord record = recordService.getById(entityKey, id);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toResponsePublic(entityKey, record));
    }

    private EntityRecordResponse toResponsePublic(String entityKey, EntityRecord record) {
        return new EntityRecordResponse(
                record.getId(),
                record.getEntityKey(),
                recordService.filterDataForPublic(entityKey, record.getData()),
                record.getCreatedAt()
        );
    }
}

