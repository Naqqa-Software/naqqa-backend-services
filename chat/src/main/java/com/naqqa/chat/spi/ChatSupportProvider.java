package com.naqqa.chat.spi;

import java.util.List;

/**
 * Service Provider Interface the consuming application must implement so the reusable
 * chat module can resolve user/course presentation data and contact discovery without
 * depending on the host application's domain model.
 *
 * <p>Register a single Spring bean implementing this interface in the consuming app.</p>
 */
public interface ChatSupportProvider {

    /**
     * Human-readable display name for a user.
     * Implementations should return a sensible fallback (never null), e.g. "User #" + userId.
     */
    String displayName(Long userId);

    /** Avatar/profile-picture URL for a user, or {@code null} if none. */
    String avatarUrl(Long userId);

    /** Avatar URL for a course-linked group conversation (e.g. course thumbnail), or {@code null}. */
    String courseAvatarUrl(Long courseId);

    /**
     * Candidate users the caller is allowed to start a DIRECT conversation with.
     * Should already exclude the caller; the chat module removes users the caller
     * already has a direct chat with and applies the text query.
     */
    List<SearchableContact> searchableContacts(Long callerId);
}
