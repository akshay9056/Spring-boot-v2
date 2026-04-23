package com.avangrid.gui.avangrid_backend.exception;


public class RecordingNotFoundException extends RuntimeException {
    public RecordingNotFoundException(String message) {
        super(message);
    }

    public RecordingNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
