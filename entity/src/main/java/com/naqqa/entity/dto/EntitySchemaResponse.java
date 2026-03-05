package com.naqqa.entity.dto;

import com.naqqa.entity.entity.Entity;

import java.util.List;

public record EntitySchemaResponse(List<Entity.EntityField> fields, Entity.EntityUiConfig ui) {
}

