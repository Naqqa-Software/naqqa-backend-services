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

    @GetMapping
    public ResponseEntity<SchedulerEventsResponse> getEvents(
            @RequestParam(defaultValue = "day") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(service.getEvents(userId, type, date));
    }

    @PostMapping
    public ResponseEntity<SchedulerEventResponse> create(
            @RequestBody SchedulerEventRequest request,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(service.create(userId, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SchedulerEventResponse> update(
            @PathVariable Long id,
            @RequestBody SchedulerEventRequest request,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(service.update(id, userId, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        service.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

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
