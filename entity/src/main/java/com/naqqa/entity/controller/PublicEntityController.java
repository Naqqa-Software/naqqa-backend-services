package com.naqqa.entity.controller;

import com.naqqa.entity.dto.EntitySchemaResponse;
import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.service.entity.EntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/public/entities")
public class PublicEntityController {

    private final EntityService entityService;

    @GetMapping("/key/{key}")
    @PreAuthorize("@entityService.isPublicGetActive(#key)")
    public ResponseEntity<EntitySchemaResponse> getByKey(@PathVariable String key) {
        Entity entity = entityService.findByKey(key);
        if (entity == null || entity.getMainDetails() == null || !Boolean.TRUE.equals(entity.getMainDetails().getIsActive())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new EntitySchemaResponse(filterPublicFields(entity.getFields()), entity.getUi(), entity.getApi()));
    }

    private List<Entity.EntityField> filterPublicFields(List<Entity.EntityField> fields) {
        if (fields == null) {
            return List.of();
        }
        List<Entity.EntityField> results = new ArrayList<>();
        for (Entity.EntityField field : fields) {
            if (field != null && field.getProps() != null && Boolean.TRUE.equals(field.getProps().getGetPublicApi())) {
                results.add(field);
            }
        }
        return results;
    }
}
