package com.naqqa.scheduler.controller;

import com.naqqa.scheduler.model.SchedulerEventRequest;
import com.naqqa.scheduler.model.SchedulerEventResponse;
import com.naqqa.scheduler.model.SchedulerEventsResponse;
import com.naqqa.scheduler.service.SchedulerEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SchedulerController {

    private final SchedulerEventService service;

    @Value("${naqqa.scheduler.ics-filename:events.ics}")
    private String icsFilename;

    /**
     * GET /api/scheduler?type=day|week|month|year&date=2026-05-21
     * <ul>
     *   <li>day   → List&lt;SchedulerEventResponse&gt;</li>
     *   <li>week  → List&lt;SchedulerEventResponse&gt;</li>
     *   <li>month → List&lt;String&gt; (dates with events)</li>
     *   <li>year  → List&lt;String&gt; (dates with events)</li>
     * </ul>
     */
    @GetMapping
    public ResponseEntity<SchedulerEventsResponse> getEvents(
            @RequestParam(defaultValue = "day") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(service.getEvents(userId, type, date));
    }

    /** POST /api/scheduler – create a new event for the authenticated user. */
    @PostMapping
    public ResponseEntity<SchedulerEventResponse> create(
            @RequestBody SchedulerEventRequest request,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(service.create(userId, request));
    }

    /** PUT /api/scheduler/{id} – update own event. */
    @PutMapping("/{id}")
    public ResponseEntity<SchedulerEventResponse> update(
            @PathVariable Long id,
            @RequestBody SchedulerEventRequest request,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(service.update(id, userId, request));
    }

    /** DELETE /api/scheduler/{id} – delete own event. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        service.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/scheduler/export.ics
     * Downloads an iCalendar (.ics) file containing all events from today onwards.
     * Compatible with Google Calendar ("Import") and Apple Calendar ("File → Import").
     */
    @GetMapping(value = "/export.ics", produces = "text/calendar")
    public ResponseEntity<byte[]> exportIcs(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        byte[] ics = service.exportIcs(userId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + icsFilename + "\"")
                .body(ics);
    }
}
