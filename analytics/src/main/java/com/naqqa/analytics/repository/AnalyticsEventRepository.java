package com.naqqa.analytics.repository;

import com.naqqa.analytics.entity.AnalyticsEventEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface AnalyticsEventRepository extends MongoRepository<AnalyticsEventEntity, String> {

    boolean existsByVisitorId(String visitorId);

    long deleteByTimestampBefore(Instant cutoff);
}
