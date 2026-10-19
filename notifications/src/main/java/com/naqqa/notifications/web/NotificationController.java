package com.naqqa.notifications.web;

import com.naqqa.notifications.dto.NotificationsPage;
import com.naqqa.notifications.service.NotificationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${naqqa.notifications.path:/api/notifications}")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationsPage list(@RequestParam(value = "page", defaultValue = "0") int page,
                                  @RequestParam(value = "size", defaultValue = "30") int size) {
        return service.list(page, size);
    }

    @PutMapping("/read-all")
    public String readAll() {
        service.markAllRead();
        return "ok";
    }

    @PutMapping("/{id}/read")
    public String read(@PathVariable String id) {
        service.markRead(id);
        return "ok";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable String id) {
        service.delete(id);
        return "ok";
    }
}
