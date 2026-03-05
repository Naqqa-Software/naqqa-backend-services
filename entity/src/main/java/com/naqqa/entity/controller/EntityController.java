package com.naqqa.entity.controller;

import com.naqqa.entity.dto.EntitySchemaResponse;
import com.naqqa.entity.dto.PagedResponse;
import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.service.entity.EntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/entities")
public class EntityController {

    private final EntityService entityService;

    @PostMapping
    @PreAuthorize("hasAuthority('entity:create')")
    public ResponseEntity<Entity> create(@RequestBody Entity entity) {
        return ResponseEntity.ok(entityService.save(entity));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('entity:read')")
    public ResponseEntity<PagedResponse<Entity>> getAll(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(entityService.findAll(params));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('entity:read')")
    public ResponseEntity<Entity> getById(@PathVariable String id) {
        Entity entity = entityService.findById(id);
        return entity == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(entity);
    }

    @GetMapping("/key/{key}")
    @PreAuthorize("@entityService.canReadByKey(#key, authentication)")
    public ResponseEntity<EntitySchemaResponse> getByKey(@PathVariable String key) {
        Entity entity = entityService.findByKey(key);
        if (entity == null || entity.getMainDetails() == null || !Boolean.TRUE.equals(entity.getMainDetails().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new EntitySchemaResponse(entity.getFields(), entity.getUi()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('entity:update')")
    public ResponseEntity<Entity> update(@PathVariable String id, @RequestBody Entity entity) {
        return ResponseEntity.ok(entityService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('entity:delete')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        entityService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
