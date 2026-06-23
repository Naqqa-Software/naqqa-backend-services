package com.naqqa.scheduler.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "scheduler_events")
@Getter
@Setter
@CompoundIndexes({
        @CompoundIndex(name = "idx_scheduler_user_date", def = "{'userId': 1, 'date': 1}"),
        @CompoundIndex(name = "idx_scheduler_user_course", def = "{'userId': 1, 'courseId': 1}")
})
public class SchedulerEventEntity implements Serializable {

    @Id
    private Long id;

    private Long userId;

    private String title;

    private LocalDate date;

    private String startTime;

    private String endTime;

    private String color;

    private String description;

    /** Optional text/label colour override (e.g. "#fff" for dark backgrounds). */
    private String textColor;

    /** i18n key the FE uses to build the display label, e.g. "Scheduler.Lesson.Start". */
    private String messageKey;

    /**
     * Arbitrary params the FE needs to interpolate the message key.
     * Example: {courseId, courseTitle:{ro,en,ru}, lessonId, lessonTitle:{ro,en,ru}, lessonNumber}
     */
    private Map<String, Object> messageParams;

    /** Lesson that originated this event (null for manual events). */
    private Long lessonId;

    /** Lesson slot that originated this event (null for manual events). */
    private Long slotId;

    /** Course that originated this event (null for manual events). */
    private Long courseId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
