package com.naqqa.chat.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParticipantInfo {
    private Long id;
    private String fullName;
    private String avatarUrl;
}
