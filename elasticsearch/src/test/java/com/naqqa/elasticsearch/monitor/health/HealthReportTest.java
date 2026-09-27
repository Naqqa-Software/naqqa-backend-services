package com.naqqa.elasticsearch.monitor.health;

import com.naqqa.elasticsearch.test.Test;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class HealthReportTest {

    @Test
    public void overallStatusIsWorstOfIndicators() {
        ClusterCoordinationHealthIndicator coordination = new ClusterCoordinationHealthIndicator(
                new ClusterCoordinationHealthIndicator.Input(true, false));
        DiskHealthIndicator disk = new DiskHealthIndicator(
                new DiskHealthIndicator.Input(Map.of("node-1", 92.0), 85.0, 95.0));
        ShardsAvailabilityHealthIndicator shards = new ShardsAvailabilityHealthIndicator(
                new ShardsAvailabilityHealthIndicator.Input(0, 0, 0, 10));

        HealthReport report = new HealthReport(List.of(coordination, disk, shards));

        assertEquals(HealthStatus.YELLOW, report.overallStatus());

        Map<String, Object> map = report.toMap();
        assertEquals("YELLOW", map.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> indicators = (Map<String, Object>) map.get("indicators");
        @SuppressWarnings("unchecked")
        Map<String, Object> diskMap = (Map<String, Object>) indicators.get("disk");
        assertEquals("YELLOW", diskMap.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> coordinationMap = (Map<String, Object>) indicators.get("cluster_coordination");
        assertEquals("GREEN", coordinationMap.get("status"));
    }

    @Test
    public void redIndicatorMakesOverallRedEvenIfOthersGreen() {
        ClusterCoordinationHealthIndicator coordination = new ClusterCoordinationHealthIndicator(
                new ClusterCoordinationHealthIndicator.Input(false, false));
        ShardsAvailabilityHealthIndicator shards = new ShardsAvailabilityHealthIndicator(
                new ShardsAvailabilityHealthIndicator.Input(0, 0, 0, 10));

        HealthReport report = new HealthReport(List.of(coordination, shards));
        assertEquals(HealthStatus.RED, report.overallStatus());
    }

    @Test
    public void allGreenIndicatorsYieldGreenOverall() {
        ShardsAvailabilityHealthIndicator shards = new ShardsAvailabilityHealthIndicator(
                new ShardsAvailabilityHealthIndicator.Input(0, 0, 0, 10));
        HealthReport report = new HealthReport(List.of(shards));
        assertEquals(HealthStatus.GREEN, report.overallStatus());
    }
}
