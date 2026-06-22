package com.naqqa.chat.model;

import lombok.Data;

@Data
public class ReadReceiptRequest {
    private Long lastMessageId;
}
