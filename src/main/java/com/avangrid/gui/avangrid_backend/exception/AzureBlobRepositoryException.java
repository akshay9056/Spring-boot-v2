package com.avangrid.gui.avangrid_backend.exception;

public  class AzureBlobRepositoryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AzureBlobRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
