package com.naqqa.chat.model;

import com.naqqa.chat.enums.ChatMessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddedMessage {
    private Long id;
    private Long senderId;
    private String content;
    private ChatMessageType type;
}
