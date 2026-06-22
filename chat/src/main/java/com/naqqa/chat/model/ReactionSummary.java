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
    /** The emoji character(s), e.g. "❤️" */
    private String emoji;
    /** Total number of users who reacted with this emoji */
    private long count;
    /** True when the requesting user has this reaction on the message */
    private boolean reactedByMe;
}
