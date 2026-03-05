package com.naqqa.entity.dto;

import com.naqqa.entity.entity.Entity;
import com.naqqa.entity.model.entity.EntityApiConfig;

import java.util.List;

public record EntitySchemaResponse(List<Entity.EntityField> fields, Entity.EntityUiConfig ui, EntityApiConfig api) {
}
