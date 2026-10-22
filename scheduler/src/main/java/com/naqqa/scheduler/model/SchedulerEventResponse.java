package com.naqqa.scheduler.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerEventResponse {

    private String id;
    private Long userId;
    private String title;
    private String date;
    private String startTime;
    private String endTime;
    private String color;
    private String textColor;
    private String description;

    private String messageKey;

    private Map<String, Object> messageParams;
}
