package com.avangrid.gui.avangrid_backend.exception;

import java.io.Serial;

public  class AzureBlobRepositoryException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public AzureBlobRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
