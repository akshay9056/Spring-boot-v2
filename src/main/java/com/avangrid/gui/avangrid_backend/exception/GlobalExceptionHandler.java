package com.avangrid.gui.avangrid_backend.exception;

import com.avangrid.gui.avangrid_backend.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
        return buildErrorResponse(ex.getMessage(),  HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(RecordingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(RecordingNotFoundException ex) {
        return buildErrorResponse(ex.getMessage(),  HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(DuplicateRecordingException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateRecordingException ex) {
        return buildErrorResponse(ex.getMessage(),  HttpStatus.CONFLICT);
    }

    @ExceptionHandler(RecordingProcessingException.class)
    public ResponseEntity<ErrorResponse> handleRecordingAccessFailure(RecordingProcessingException ex) {
        return buildErrorResponse(ex.getMessage(),  HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class) // fallback
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        return buildErrorResponse("Unexpected error: " + ex.getMessage(),
                 HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(String message,
                                                                   HttpStatus status) {
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .build();
        return new ResponseEntity<>(errorResponse, status);
    }
}
