package com.naqqa.entity.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body for AI entity-definition generation. {@code current} is optional — when
 * present (the definition currently open in the form), the prompt edits it;
 * otherwise a new definition is created.
 */
public record GenerateEntityRequest(String prompt, JsonNode current) {
}
