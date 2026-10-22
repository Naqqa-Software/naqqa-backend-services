package com.naqqa.scheduler.model;

import lombok.Data;

import java.time.LocalDate;

@Data
public class SchedulerEventRequest {

    private Long userId;
    private String title;
    private LocalDate date;
    private String startTime;
    private String endTime;
    private String color;
    private String textColor;
    private String description;
}
