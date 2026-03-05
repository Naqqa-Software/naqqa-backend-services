package com.naqqa.entity.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.naqqa.entity.model.entity.EntityApiConfig;
import com.naqqa.entity.model.entity.FieldProps;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Document(collection = "entity")
@CompoundIndexes({
        @CompoundIndex(name = "main_details_key_unique", def = "{'mainDetails.key': 1}", unique = true),
        @CompoundIndex(name = "main_details_slug_unique", def = "{'mainDetails.slug': 1}", unique = true)
})
public class Entity {

    @Id
    private String id;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @CreatedBy
    private String createdBy;

    private MainDetails mainDetails;
    private EntityApiConfig api;
    private List<EntityField> fields;
    private List<EntityIndex> indexes;
    private List<UniqueConstraint> uniqueConstraints;
    private EntityUiConfig ui;

    @Data
    public static class MainDetails {
        private String key;
        private String slug;
        private String label;
        private Integer version;
        private String description;
        private Boolean isActive;
    }

    @Data
    public static class EntityField {
        private String key;
        private String type;
        private String widget;
        private Object defaultValue;
        private String className;
        private String fieldGroupClassName;
        private String groupName;
        private String arrayGroupName;
        private String template;
        private String wrapper;
        private String wrapperClass;
        private Boolean hide;
        private Boolean resetOnHide;
        private String get;
        private FieldProps props;
        private FieldValidation validation;
        private ExtraValidators validators;
        private ExtraValidators asyncValidators;
        private Expressions expressions;
        private ModelOptions modelOptions;
        private Hooks hooks;
    }

    @Data
    public static class FieldValidation {
        private List<ValidationMessage> messages;
    }

    @Data
    public static class ValidationMessage {
        private String errorKey;
        private String message;
    }

    @Data
    public static class ExtraValidators {
        private List<NamedFn> extra;
    }

    @Data
    public static class NamedFn {
        private String name;
        private String fn;
    }

    @Data
    public static class Expressions {
        private String hide;
        @JsonProperty("props.disabled")
        private String propsDisabled;
        @JsonProperty("props.required")
        private String propsRequired;
        private String className;
    }

    @Data
    public static class ModelOptions {
        private Integer debounce;
        private String updateOn;
    }

    @Data
    public static class Hooks {
        private String onInit;
        private String onChanges;
        private String afterContentInit;
        private String afterViewInit;
        private String onDestroy;
    }

    @Data
    public static class EntityIndex {
        private String name;
        private List<IndexField> fields;
        private Boolean unique;
        private Boolean sparse;
    }

    @Data
    public static class IndexField {
        private String path;
        private Integer direction;
    }

    @Data
    public static class UniqueConstraint {
        private List<PathRef> fields;
        private List<PathRef> scopeFields;
        private Boolean caseInsensitive;
    }

    @Data
    public static class PathRef {
        private String path;
    }

    @Data
    public static class EntityUiConfig {
        private DefaultSort defaultSort;
        private List<TableGroup> tableGroups;
    }

    @Data
    public static class DefaultSort {
        private String path;
        private String direction;
    }

    @Data
    public static class TableGroup {
        private String key;
        private String label;
        private Integer order;
    }
}
