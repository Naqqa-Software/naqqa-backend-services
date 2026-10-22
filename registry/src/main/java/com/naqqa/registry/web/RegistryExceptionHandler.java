package com.naqqa.registry.web;

import com.naqqa.commons.error.ApiError;
import com.naqqa.commons.error.ApiException;
import com.naqqa.registry.service.RegistryErrors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RegistryAdminController.class, NpmRegistryController.class})
public class RegistryExceptionHandler {

    static final String CHALLENGE = "Bearer realm=\"naqqa-registry\"";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_JSON);
        if (ex.status() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        }
        return response.body(ApiError.from(ex));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return handleApi(RegistryErrors.invalid("The request body is not valid."));
    }
}
