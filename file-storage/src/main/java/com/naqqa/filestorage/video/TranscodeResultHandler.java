package com.naqqa.filestorage.video;

public interface TranscodeResultHandler {

    void onTranscodeComplete(String correlationId, boolean success, String detail);
}
