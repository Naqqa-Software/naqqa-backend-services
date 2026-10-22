package com.naqqa.chat.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateConversationRequest {
    private String kind;
    private Long targetUserId;
    private Map<String, String> title;
    private List<Long> memberIds;
}
