package com.naqqa.auth.exceptions;

import lombok.Getter;

@Getter
public class BadRequestException extends RuntimeException {

    public BadRequestException() {
        super("Bad request!");
    }

    public BadRequestException(String message) {
        super(message);
    }
}
