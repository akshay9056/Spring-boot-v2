package com.avangrid.gui.avangrid_backend.exception;

public class RecordingProcessingException extends RuntimeException{
    public RecordingProcessingException(String message) {
        super(message);
    }
    public RecordingProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
