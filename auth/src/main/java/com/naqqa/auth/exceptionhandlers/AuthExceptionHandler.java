package com.naqqa.auth.exceptionhandlers;

import com.naqqa.auth.dto.AuthErrorResponse;
import com.naqqa.auth.exceptions.*;
import com.naqqa.auth.exceptions.auth.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(EmailInUseException.class)
    public ResponseEntity<AuthErrorResponse> handleEmailInUse(EmailInUseException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(WrongCredentialsException.class)
    public ResponseEntity<AuthErrorResponse> handleWrongCredentials(WrongCredentialsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<AuthErrorResponse> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new AuthErrorResponse(ex.getMessage(), ex.getUuid()));
    }


    @ExceptionHandler(EmailNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleEmailNotVerified(EmailNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new AuthErrorResponse(ex.getMessage(), null));
    }

    @ExceptionHandler(SamePasswordException.class)
    public ResponseEntity<AuthErrorResponse> samePassword(SamePasswordException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(ex.getMessage(), null));
    }
}
