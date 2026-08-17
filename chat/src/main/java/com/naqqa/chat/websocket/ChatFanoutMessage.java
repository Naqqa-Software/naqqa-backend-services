package com.naqqa.chat.websocket;

import java.util.List;

/**
 * Envelope published over Redis pub/sub so a chat frame produced on one instance
 * reaches the target users' WebSocket sessions no matter which auto-scaled instance
 * currently holds those sessions.
 *
 * <p>Every instance subscribes to the chat channel and, on receipt, delivers only to
 * the sessions it holds locally (others simply no-op). Two flavours:</p>
 * <ul>
 *   <li>{@link Kind#DELIVER} — {@code payload} is a ready-to-send JSON frame (MESSAGE,
 *       CALL, REACTION, …) forwarded verbatim to each user in {@code userIds}.</li>
 *   <li>{@link Kind#UNREAD} — {@code payload} is unused; each receiving instance rebuilds
 *       a personalised UNREAD_COUNT frame per user (unread state is per-user, so it cannot
 *       be shipped as one shared payload).</li>
 * </ul>
 */
public record ChatFanoutMessage(Kind kind, List<Long> userIds, String payload) {

    public enum Kind { DELIVER, UNREAD }

    public static ChatFanoutMessage deliver(List<Long> userIds, String payload) {
        return new ChatFanoutMessage(Kind.DELIVER, userIds, payload);
    }

    public static ChatFanoutMessage unread(List<Long> userIds) {
        return new ChatFanoutMessage(Kind.UNREAD, userIds, null);
    }
}
