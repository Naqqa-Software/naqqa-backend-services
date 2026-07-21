package com.naqqa.analytics.config;

import com.naqqa.analytics.service.AnalyticsTracker;
import com.naqqa.analytics.web.AnalyticsTrackingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AnalyticsTrackingInterceptor} so {@code @TrackView}-annotated endpoints in the
 * host project are tracked with zero code. Picked up by the host's component scan of
 * {@code com.naqqa.analytics}.
 */
@Configuration
@RequiredArgsConstructor
public class AnalyticsWebConfig implements WebMvcConfigurer {

    private final AnalyticsTracker tracker;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AnalyticsTrackingInterceptor(tracker));
    }
}
