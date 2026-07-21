package com.naqqa.analytics.repository;

import com.naqqa.analytics.entity.AnalyticsDailyRollupEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AnalyticsDailyRollupRepository extends MongoRepository<AnalyticsDailyRollupEntity, String> {
}
