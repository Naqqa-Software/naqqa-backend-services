package com.naqqa.commons.error;

import com.naqqa.commons.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorTest {

    @Test
    void enumCodeMapsToItsWireCodeAndStatus() {
        ApiError error = ApiError.from(ApiException.notFound("missing"));

        assertThat(error.status()).isEqualTo(404);
        assertThat(error.code()).isEqualTo("not_found");
        assertThat(error.message()).isEqualTo("missing");
        assertThat(error.details()).isNull();
    }

    @Test
    void customStringCodeIsPreservedVerbatim() {
        ApiException ex = new ApiException(400, "OTP_INVALID", "That code is not right.", List.of("code"));

        ApiError error = ApiError.from(ex);

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("OTP_INVALID");
        assertThat(error.details()).isEqualTo(List.of("code"));
    }

    @Test
    void blankCodeIsRejected() {
        assertThatThrownBy(() -> new ApiException(400, " ", "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlerRendersStatusAndBody() {
        ResponseEntity<ApiError> response = new ApiExceptionHandler()
                .handleApi(new ApiException(429, "RATE_LIMITED", "Slow down"));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
    }
}
