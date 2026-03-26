package org.pdfium4j.exception;

import org.pdfium4j.model.PdfErrorCode;

import java.io.Serial;

/**
 * Thrown when a PDF document requires a password that was not provided
 * or when the provided password is incorrect.
 */
public class PdfPasswordException extends PdfiumException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PdfPasswordException(String message) {
        super(message, PdfErrorCode.PASSWORD, "open", null);
    }

    public PdfPasswordException(String message, String filePath) {
        super(message, PdfErrorCode.PASSWORD, "open", filePath);
    }
}
