package com.naqqa.chat.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateConversationRequest {
    /** "DIRECT" or "GROUP" */
    private String kind;
    /** DIRECT only */
    private Long targetUserId;
    /** GROUP only — multilingual title map, e.g. {"ro":"...", "en":"..."} */
    private Map<String, String> title;
    /** GROUP only */
    private List<Long> memberIds;
}
