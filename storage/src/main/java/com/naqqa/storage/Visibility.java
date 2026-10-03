package com.naqqa.storage;

/**
 * Where an object lives and who can read it.
 *
 * <ul>
 *   <li>{@link #PUBLIC} — stored in the public bucket with a {@code publicRead} ACL and served
 *       directly by its public URL (cacheable, no signing).</li>
 *   <li>{@link #PRIVATE} — stored in the private bucket (public access prevented) and readable only
 *       through a short-lived V4 signed URL or an authenticated server stream. Use for invoices and
 *       any per-user document.</li>
 * </ul>
 */
public enum Visibility {
    PUBLIC,
    PRIVATE
}
