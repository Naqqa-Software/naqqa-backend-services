package com.naqqa.notifications.config;

import com.naqqa.notifications.service.NotificationService;
import com.naqqa.notifications.spi.DevNotificationSource;
import com.naqqa.notifications.spi.NotificationSource;
import com.naqqa.notifications.web.NotificationController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.notifications", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotificationSource naqqaNotificationSource() {
        return new DevNotificationSource();
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationService naqqaNotificationService(NotificationSource source) {
        return new NotificationService(source);
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationController naqqaNotificationController(NotificationService service) {
        return new NotificationController(service);
    }
}
