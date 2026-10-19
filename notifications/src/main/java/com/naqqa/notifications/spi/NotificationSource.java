package com.naqqa.notifications.spi;

import com.naqqa.notifications.dto.Notification;

import java.util.List;

public interface NotificationSource {

    List<Notification> all();
}
