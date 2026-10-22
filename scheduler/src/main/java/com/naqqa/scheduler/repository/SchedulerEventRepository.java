package com.naqqa.scheduler.repository;

import com.naqqa.scheduler.entity.SchedulerEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SchedulerEventRepository extends JpaRepository<SchedulerEventEntity, Long> {

    List<SchedulerEventEntity> findAllByUserIdAndDate(Long userId, LocalDate date);

    List<SchedulerEventEntity> findAllByUserIdAndDateBetween(Long userId, LocalDate from, LocalDate to);

    List<SchedulerEventEntity> findAllByUserIdAndDateGreaterThanEqualOrderByDateAscStartTimeAsc(
            Long userId, LocalDate fromDate);

    void deleteAllByUserIdAndCourseId(Long userId, Long courseId);
}
