package com.naqqa.filestorage;

import com.google.cloud.storage.Storage;
import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.repository.FileRepository;
import com.naqqa.filestorage.service.RoutingFileStorageService;
import com.naqqa.filestorage.video.TranscodeCallbackController;
import com.naqqa.filestorage.video.TranscodeResultHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LibraryOptionalityTest {

    private static final String TOKEN = "s3cret-token";

    private ObjectProvider<TranscodeResultHandler> providerOf(TranscodeResultHandler handler) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TranscodeResultHandler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(handler);
        return provider;
    }

    private FileStorageProperties propsWithToken() {
        FileStorageProperties props = new FileStorageProperties();
        props.getTranscode().setCallbackToken(TOKEN);
        return props;
    }


    @Test
    void callbackWithoutHandlerAnswersServiceUnavailable() {
        var controller = new TranscodeCallbackController(propsWithToken(), providerOf(null));

        var response = controller.callback(TOKEN,
                new TranscodeCallbackController.CallbackRequest("vod/1", "READY", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void callbackWithHandlerDeliversTheResult() {
        var delivered = new String[1];
        TranscodeResultHandler handler = (groupId, ready, detail) -> delivered[0] = groupId + ":" + ready;

        var controller = new TranscodeCallbackController(propsWithToken(), providerOf(handler));
        var response = controller.callback(TOKEN,
                new TranscodeCallbackController.CallbackRequest("vod/7", "READY", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(delivered[0]).isEqualTo("vod/7:true");
    }

    @Test
    void missingHandlerIsStillNotAWayPastTheToken() {
        var controller = new TranscodeCallbackController(propsWithToken(), providerOf(null));

        var response = controller.callback("wrong-token",
                new TranscodeCallbackController.CallbackRequest("vod/1", "READY", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }


    private RoutingFileStorageService serviceHolding(FileEntity file) {
        FileRepository repo = mock(FileRepository.class);
        when(repo.findById(file.getId())).thenReturn(Optional.of(file));

        FileStorageProperties props = new FileStorageProperties();
        props.setBucketName("private-bucket");
        return new RoutingFileStorageService(repo, mock(Storage.class), props);
    }

    private FileEntity ownerlessFile() {
        FileEntity file = new FileEntity();
        file.setId(9L);
        file.setFileName("courses/videos/system-generated.mp4");
        file.setOwnerId(null);
        return file;
    }

    @Test
    void deletingAnOwnerlessFileIsRefused() {
        var service = serviceHolding(ownerlessFile());

        assertThatThrownBy(() -> service.deleteFile(9L, 42L, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAdminCanStillDeleteAnOwnerlessFile() {
        var service = serviceHolding(ownerlessFile());

        assertThatCode(() -> service.deleteFile(9L, 42L, true)).doesNotThrowAnyException();
    }

    @Test
    void theOwnerCanStillDeleteTheirOwnFile() {
        FileEntity owned = ownerlessFile();
        owned.setOwnerId(42L);
        var service = serviceHolding(owned);

        assertThatCode(() -> service.deleteFile(9L, 42L, false)).doesNotThrowAnyException();
    }
}
