package com.avangrid.gui.avangrid_backend.exception;

public class DuplicateRecordingException extends RuntimeException {
    public DuplicateRecordingException(String message) {
        super(message);
    }

    public DuplicateRecordingException(String message, Throwable cause) {super( message, cause );}
}
