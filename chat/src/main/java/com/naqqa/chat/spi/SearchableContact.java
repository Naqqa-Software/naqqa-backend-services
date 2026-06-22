package com.naqqa.chat.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A candidate user the caller is allowed to start a direct chat with, supplied by the
 * consuming application's {@link ChatSupportProvider}. The chat module further filters
 * these by existing conversations and the search query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchableContact {
    private Long userId;
    /** Free-form role label surfaced to the client, e.g. "ORGANIZER" or "STUDENT". */
    private String role;
}
