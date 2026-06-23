package com.naqqa.scheduler.service;

import com.naqqa.scheduler.entity.SchedulerEventEntity;
import com.naqqa.scheduler.exception.ForbiddenException;
import com.naqqa.scheduler.exception.ResourceNotFoundException;
import com.naqqa.scheduler.model.SchedulerEventRequest;
import com.naqqa.scheduler.model.SchedulerEventResponse;
import com.naqqa.scheduler.model.SchedulerEventsResponse;
import com.naqqa.scheduler.repository.SchedulerEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generic per-user scheduler: manual event CRUD, day/week/month/year queries and
 * iCalendar export. Domain-specific event generation (e.g. course lessons) lives in
 * the consuming application, which builds {@link SchedulerEventEntity} instances and
 * persists them through {@link #replaceCourseEvents}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerEventService {

    private static final String DEFAULT_COLOR = "#c8f5d8";
    private static final String DEFAULT_START = "09:00";
    private static final String DEFAULT_END   = "18:00";
    private static final DateTimeFormatter HH_MM    = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ICS_DT   = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /** Branding for the exported .ics calendar — override per app. */
    @Value("${naqqa.scheduler.calendar-name:Events}")
    private String calendarName;
    @Value("${naqqa.scheduler.product-id:-//Naqqa//Scheduler//EN}")
    private String productId;
    @Value("${naqqa.scheduler.uid-domain:naqqa}")
    private String uidDomain;

    private final SchedulerEventRepository repository;

    // ─── CRUD ────────────────────────────────────────────────────────────────

    public SchedulerEventResponse create(Long userId, SchedulerEventRequest req) {
        SchedulerEventEntity entity = new SchedulerEventEntity();
        entity.setUserId(userId);
        applyDefaults(entity, req);
        return toResponse(repository.save(entity));
    }

    public SchedulerEventResponse update(Long id, Long userId, SchedulerEventRequest req) {
        SchedulerEventEntity entity = findOwned(id, userId);
        applyDefaults(entity, req);
        return toResponse(repository.save(entity));
    }

    public void delete(Long id, Long userId) {
        repository.delete(findOwned(id, userId));
    }

    // ─── Queries ─────────────────────────────────────────────────────────────

    public SchedulerEventsResponse getEvents(Long userId, String type, LocalDate date) {
        if (date == null) date = LocalDate.now();

        List<SchedulerEventResponse> events = switch (type == null ? "day" : type.toLowerCase()) {
            case "week"  -> getWeek(userId, date);
            case "month" -> getRange(userId, date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()));
            case "year"  -> getRange(userId, date.withDayOfYear(1),  date.withDayOfYear(date.lengthOfYear()));
            default      -> getDay(userId, date);
        };

        LocalDate today    = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        return SchedulerEventsResponse.builder()
                .events(events)
                .todayEvents(getDay(userId, today))
                .tomorrowEvents(getDay(userId, tomorrow))
                .build();
    }

    private List<SchedulerEventResponse> getDay(Long userId, LocalDate date) {
        return repository.findAllByUserIdAndDate(userId, date)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private List<SchedulerEventResponse> getWeek(Long userId, LocalDate date) {
        LocalDate monday = date.with(WeekFields.ISO.dayOfWeek(), 1);
        return getRange(userId, monday, monday.plusDays(6));
    }

    private List<SchedulerEventResponse> getRange(Long userId, LocalDate from, LocalDate to) {
        return repository.findAllByUserIdAndDateBetween(userId, from, to)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ─── iCalendar export ────────────────────────────────────────────────────

    public String exportIcs(Long userId) {
        List<SchedulerEventEntity> events = repository
                .findAllByUserIdAndDateGreaterThanEqualOrderByDateAscStartTimeAsc(userId, LocalDate.now());

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:").append(productId).append("\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:").append(calendarName).append("\r\n");
        sb.append("X-WR-TIMEZONE:UTC\r\n");

        String stamp = LocalDateTime.now().format(ICS_DT) + "Z";

        for (SchedulerEventEntity e : events) {
            if (e.getDate() == null) continue;
            LocalTime start = parseTime(e.getStartTime(), DEFAULT_START);
            LocalTime end   = parseTime(e.getEndTime(),   DEFAULT_END);
            String dtStart  = e.getDate().atTime(start).format(ICS_DT);
            String dtEnd    = e.getDate().atTime(end).format(ICS_DT);

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(e.getId()).append("@").append(uidDomain).append("\r\n");
            sb.append("DTSTAMP:").append(stamp).append("\r\n");
            sb.append("DTSTART:").append(dtStart).append("\r\n");
            sb.append("DTEND:").append(dtEnd).append("\r\n");
            sb.append("SUMMARY:").append(escapeIcs(e.getTitle())).append("\r\n");
            if (e.getDescription() != null && !e.getDescription().isBlank()) {
                sb.append("DESCRIPTION:").append(escapeIcs(e.getDescription())).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    // ─── Domain-linked events (generation lives in the consuming app) ──────────

    /**
     * Replaces all events previously generated for {@code userId} from {@code courseId}
     * with the supplied set. The consuming application builds the entities (with
     * {@code userId}/{@code courseId} already set) from its own domain model.
     */
    public void replaceCourseEvents(Long userId, Long courseId, List<SchedulerEventEntity> events) {
        repository.deleteAllByUserIdAndCourseId(userId, courseId);
        if (events != null && !events.isEmpty()) {
            repository.saveAll(events);
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private SchedulerEventEntity findOwned(Long id, Long userId) {
        SchedulerEventEntity entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scheduler event not found: " + id));
        if (!entity.getUserId().equals(userId)) {
            throw new ForbiddenException("Access denied to scheduler event: " + id);
        }
        return entity;
    }

    private void applyDefaults(SchedulerEventEntity entity, SchedulerEventRequest req) {
        entity.setTitle(req.getTitle() != null ? req.getTitle() : "");
        entity.setDate(req.getDate() != null ? req.getDate() : LocalDate.now());
        entity.setStartTime(nonBlank(req.getStartTime(), DEFAULT_START));
        entity.setEndTime(nonBlank(req.getEndTime(), DEFAULT_END));
        entity.setColor(nonBlank(req.getColor(), DEFAULT_COLOR));
        entity.setTextColor(req.getTextColor());
        entity.setDescription(req.getDescription() != null ? req.getDescription() : "");
        // Manual events have no message key / params
        entity.setMessageKey(null);
        entity.setMessageParams(null);
        entity.setCourseId(null);
        entity.setLessonId(null);
        entity.setSlotId(null);
    }

    private String nonBlank(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private LocalTime parseTime(String hhmm, String fallback) {
        try {
            return LocalTime.parse(hhmm != null ? hhmm : fallback, HH_MM);
        } catch (Exception ex) {
            return LocalTime.parse(fallback, HH_MM);
        }
    }

    private String escapeIcs(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace(";", "\\;")
                    .replace(",", "\\,")
                    .replace("\n", "\\n")
                    .replace("\r", "");
    }

    private SchedulerEventResponse toResponse(SchedulerEventEntity e) {
        return SchedulerEventResponse.builder()
                .id(String.valueOf(e.getId()))
                .userId(e.getUserId())
                .title(e.getTitle())
                .date(e.getDate() != null ? e.getDate().toString() : null)
                .startTime(e.getStartTime())
                .endTime(e.getEndTime())
                .color(e.getColor())
                .textColor(e.getTextColor())
                .description(e.getDescription() != null && !e.getDescription().isBlank()
                        ? e.getDescription() : null)
                .messageKey(e.getMessageKey())
                .messageParams(e.getMessageParams())
                .build();
    }
}
