package com.naqqa.auth.exceptions;

public class InternalServerErrorException extends RuntimeException {
    public InternalServerErrorException() {
        super("Internal Server Error!");
    }

    public InternalServerErrorException(String message) {
        super(message);
    }
}
