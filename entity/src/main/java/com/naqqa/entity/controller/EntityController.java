package com.naqqa.entity.controller;

import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.service.entity.EntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ResponseEntity<List<Entity>> getAll() {
        return ResponseEntity.ok(entityService.findAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('entity:read')")
    public ResponseEntity<Entity> getById(@PathVariable String id) {
        Entity entity = entityService.findById(id);
        return entity == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(entity);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('entity:update')")
    public ResponseEntity<Entity> update(@PathVariable String id, @RequestBody Entity entity) {
        entity.setId(id);
        return ResponseEntity.ok(entityService.save(entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('entity:delete')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        entityService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
