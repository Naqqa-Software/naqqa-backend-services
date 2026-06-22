package com.naqqa.scheduler.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SchedulerEventsResponse {

    /** Events matching the requested type/date window. */
    private List<SchedulerEventResponse> events;

    /** Today's events — always returned regardless of the requested window. */
    private List<SchedulerEventResponse> todayEvents;

    /** Tomorrow's events — always returned regardless of the requested window. */
    private List<SchedulerEventResponse> tomorrowEvents;
}
