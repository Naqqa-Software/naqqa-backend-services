package com.naqqa.scheduler.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerEventResponse {

    /** String ID so the FE can use it directly as "s1", "123", etc. */
    private String id;
    private Long userId;
    private String title;
    /** ISO date string: "YYYY-MM-DD" */
    private String date;
    private String startTime;
    private String endTime;
    private String color;
    /** Optional (e.g. "#fff" for dark backgrounds). */
    private String textColor;
    private String description;

    /**
     * i18n key the FE uses to build the display label.
     * e.g. "Scheduler.Lesson.Start"
     * Only present for auto-generated course events.
     */
    private String messageKey;

    /**
     * Params to interpolate with messageKey.
     * e.g. {courseId, courseTitle:{ro,en,ru}, lessonId, lessonTitle:{ro,en,ru}, lessonNumber}
     */
    private Map<String, Object> messageParams;
}
