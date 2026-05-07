package org.grimmory.pdfium4j.model;

import java.time.Instant;
import java.util.Optional;

/** Represents a digital signature in a PDF document. */
public record PdfSignature(
    int index,
    Optional<String> reason,
    Optional<Instant> time,
    Optional<String> subFilter,
    long contentsSize,
    long byteRangeSize) {}
