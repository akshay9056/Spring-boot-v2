package com.avangrid.gui.avangrid_backend.exception;

public class BlobAccessException extends RuntimeException {
    public BlobAccessException(String message) {
        super(message);
    }

    public BlobAccessException(String message, Throwable cause) {super( message, cause );}
}
