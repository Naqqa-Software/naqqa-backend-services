package com.naqqa.entity.model.entity;

import lombok.Data;

@Data
public class EntityApiConfig {
    private Boolean isPublicGet;
    private Boolean isPublicPost;
    private Boolean isPublicPut;
    private Boolean allowFilters;
    private Boolean allowSort;
    private Boolean allowSearch;
}

