package com.naqqa.entity.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldProps {
    private String type;
    private String label;
    private String placeholder;
    private Boolean disabled;
    private Boolean hidden;
    private Boolean required;
    private Boolean readonly;
    private Boolean avoidTranslate;
    private Boolean showTableFilter;
    private Boolean showTableSort;
    private Boolean getPublicApi;
    private Integer min;
    private Integer max;
    private Integer minLength;
    private Integer maxLength;
    private String pattern;
    private List<Option> options;
    private String asyncOptions;
    private String focus;
    private String blur;
    private String keyup;
    private String keydown;
    private String click;
    private String change;
    private String keypress;
    private String wheel;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Option {
        private String label;
        private Object value;
        private Boolean disabled;
    }
}
