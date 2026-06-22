package com.naqqa.scheduler.repository;

import com.naqqa.scheduler.entity.SchedulerEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SchedulerEventRepository extends JpaRepository<SchedulerEventEntity, Long> {

    /** All events for a user on a specific day. */
    List<SchedulerEventEntity> findAllByUserIdAndDate(Long userId, LocalDate date);

    /** All events for a user in a date range (week / month / year view). */
    List<SchedulerEventEntity> findAllByUserIdAndDateBetween(Long userId, LocalDate from, LocalDate to);

    /** All future events for export (date >= fromDate), ordered by date then startTime. */
    List<SchedulerEventEntity> findAllByUserIdAndDateGreaterThanEqualOrderByDateAscStartTimeAsc(
            Long userId, LocalDate fromDate);

    /** Delete all events auto-generated from a specific course for a user. */
    void deleteAllByUserIdAndCourseId(Long userId, Long courseId);
}
