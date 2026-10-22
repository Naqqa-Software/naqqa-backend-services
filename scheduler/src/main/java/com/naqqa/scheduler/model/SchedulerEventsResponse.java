package com.naqqa.scheduler.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SchedulerEventsResponse {

    private List<SchedulerEventResponse> events;

    private List<SchedulerEventResponse> todayEvents;

    private List<SchedulerEventResponse> tomorrowEvents;
}
