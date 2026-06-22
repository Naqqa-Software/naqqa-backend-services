package com.naqqa.scheduler.model;

import lombok.Data;

import java.time.LocalDate;

@Data
public class SchedulerEventRequest {

    private Long userId;         // ignored on create/update (set from JWT); used for course auto-import only
    private String title;
    private LocalDate date;
    private String startTime;    // "HH:mm", default "09:00"
    private String endTime;      // "HH:mm", default "18:00"
    private String color;        // hex, default "#c8f5d8"
    private String textColor;    // optional label colour override (e.g. "#fff")
    private String description;
}
