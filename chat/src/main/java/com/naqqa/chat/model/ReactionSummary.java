package com.naqqa.chat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionSummary {
    private String emoji;
    private long count;
    private boolean reactedByMe;
}
