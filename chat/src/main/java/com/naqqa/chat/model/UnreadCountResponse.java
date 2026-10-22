package com.naqqa.chat.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class UnreadCountResponse {

    private long total;

    private Map<Long, Long> byConversation;
}
