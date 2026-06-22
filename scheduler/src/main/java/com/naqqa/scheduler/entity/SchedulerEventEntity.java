package com.naqqa.scheduler.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Getter
@Setter
@Table(name = "scheduler_events", indexes = {
        @Index(name = "idx_scheduler_user_date", columnList = "user_id, event_date"),
        @Index(name = "idx_scheduler_user_course", columnList = "user_id, course_id")
})
public class SchedulerEventEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "event_date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private String startTime;

    @Column(name = "end_time", nullable = false)
    private String endTime;

    @Column(nullable = false)
    private String color;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Optional text/label colour override (e.g. "#fff" for dark backgrounds). */
    @Column(name = "text_color")
    private String textColor;

    /** i18n key the FE uses to build the display label, e.g. "Scheduler.Lesson.Start". */
    @Column(name = "message_key")
    private String messageKey;

    /**
     * Arbitrary params the FE needs to interpolate the message key.
     * Example: {courseId, courseTitle:{ro,en,ru}, lessonId, lessonTitle:{ro,en,ru}, lessonNumber}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_params", columnDefinition = "jsonb")
    private Map<String, Object> messageParams;

    /** Lesson that originated this event (null for manual events). */
    @Column(name = "lesson_id")
    private Long lessonId;

    /** Lesson slot that originated this event (null for manual events). */
    @Column(name = "slot_id")
    private Long slotId;

    /** Course that originated this event (null for manual events). */
    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "created_at", updatable = false, columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
