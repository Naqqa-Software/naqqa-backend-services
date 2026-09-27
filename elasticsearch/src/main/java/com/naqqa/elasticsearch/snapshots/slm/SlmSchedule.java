package com.naqqa.elasticsearch.snapshots.slm;

import java.time.Duration;
import java.time.Instant;

public interface SlmSchedule {

    Instant nextFireTime(Instant after);

    static SlmSchedule parse(String scheduleSpec) {
        if (scheduleSpec.startsWith("interval:")) {
            Duration duration = Duration.parse(scheduleSpec.substring("interval:".length()));
            return new IntervalSchedule(duration);
        }
        return new CronSchedule(scheduleSpec);
    }
}
