package com.naqqa.auth.exceptionhandlers;

import com.naqqa.auth.dto.auth.AuthErrorResponse; // you can reuse this DTO
import com.naqqa.auth.exceptions.authorities.*;
import com.naqqa.auth.config.authorities.AuthoritiesErrors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class AuthoritiesExceptionHandler {

    // ----------------------------
    // Roles
    // ----------------------------

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleRoleNotFound(RoleNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.ROLE_NOT_FOUND,
                        null
                ));
    }

    @ExceptionHandler(RoleAlreadyExistsException.class)
    public ResponseEntity<AuthErrorResponse> handleRoleAlreadyExists(RoleAlreadyExistsException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.ROLE_ALREADY_EXISTS,
                        null
                ));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidRole(InvalidRoleException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.ROLE_INVALID_NAME,
                        null
                ));
    }

    @ExceptionHandler(NoAuthoritiesException.class)
    public ResponseEntity<AuthErrorResponse> handleNoAuthorities(NoAuthoritiesException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.ROLE_NO_AUTHORITIES,
                        null
                ));
    }

    @ExceptionHandler(InvalidAuthoritiesException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidAuthorities(InvalidAuthoritiesException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.ROLE_INVALID_AUTHORITIES,
                        null
                ));
    }

    // ----------------------------
    // Authorities
    // ----------------------------

    @ExceptionHandler(AuthorityNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleAuthorityNotFound(AuthorityNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.AUTHORITY_NOT_FOUND,
                        null
                ));
    }

    @ExceptionHandler(AuthorityAlreadyExistsException.class)
    public ResponseEntity<AuthErrorResponse> handleAuthorityAlreadyExists(AuthorityAlreadyExistsException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.AUTHORITY_ALREADY_EXISTS,
                        null
                ));
    }

    @ExceptionHandler(InvalidAuthorityException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidAuthority(InvalidAuthorityException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new AuthErrorResponse(
                        ex.getMessage() != null ? ex.getMessage() : AuthoritiesErrors.AUTHORITY_INVALID_NAME,
                        null
                ));
    }

    // ----------------------------
    // Generic / fallback
    // ----------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception: ", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AuthErrorResponse(
                        AuthoritiesErrors.INTERNAL_ERROR,
                        null
                ));
    }
}
