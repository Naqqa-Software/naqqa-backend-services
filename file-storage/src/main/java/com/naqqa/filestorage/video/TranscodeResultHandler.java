package com.naqqa.filestorage.video;

/**
 * Receives the outcome of a transcode job.
 *
 * <p>The library knows how to start a job and how to authenticate its callback; it has no idea where
 * the application wants the result recorded — a lesson row, a media table, an event bus. Implement
 * this and expose it as a bean, and the built-in callback endpoint will drive it.
 *
 * <p>Optional: with no implementation present the endpoint is not registered at all, and an
 * application can consume the callback however it likes.
 */
public interface TranscodeResultHandler {

    /**
     * @param correlationId the value passed to {@code VideoTranscodeService.submit(...)} — whatever
     *                      identifies the thing being transcoded in the application's own model
     * @param success       true when the ladder was published and is safe to serve
     * @param detail        rung count on success, failure reason otherwise; may be null
     */
    void onTranscodeComplete(String correlationId, boolean success, String detail);
}
