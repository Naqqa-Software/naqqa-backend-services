package com.naqqa.scheduler.persistence;

import com.naqqa.scheduler.entity.SchedulerEventEntity;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;

import java.time.LocalDateTime;

/**
 * Assigns auto-increment Long ids and maintains createdAt/updatedAt timestamps for
 * {@link SchedulerEventEntity}, replacing the JPA identity generation and
 * {@code @PrePersist}/{@code @PreUpdate} lifecycle hooks.
 */
public class SchedulerEventSequenceListener extends AbstractMongoEventListener<SchedulerEventEntity> {

    public static final String SEQ_NAME = "scheduler_events_seq";

    private final SequenceGenerator sequenceGenerator;

    public SchedulerEventSequenceListener(SequenceGenerator sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    @Override
    public void onBeforeConvert(BeforeConvertEvent<SchedulerEventEntity> event) {
        SchedulerEventEntity entity = event.getSource();
        LocalDateTime now = LocalDateTime.now();
        if (entity.getId() == null) {
            entity.setId(sequenceGenerator.generateSequence(SEQ_NAME));
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }
}
