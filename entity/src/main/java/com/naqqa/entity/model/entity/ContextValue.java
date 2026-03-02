package com.naqqa.entity.model.entity;

import lombok.Data;

@Data
public class ContextValue {
    private Object mainDetails;
    private Object api;
    private Object fields;
    private Object indexes;
    private Object uniqueConstraints;
    private Object ui;
}

