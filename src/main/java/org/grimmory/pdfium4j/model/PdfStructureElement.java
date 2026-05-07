package org.grimmory.pdfium4j.model;

import java.util.List;
import java.util.Optional;

/**
 * Represents a logical element in a PDF structure tree (e.g., Heading, Table, Paragraph). Used for
 * accessibility and structured data extraction from Tagged PDFs.
 */
public record PdfStructureElement(
    String type,
    Optional<String> title,
    Optional<String> altText,
    Optional<String> actualText,
    Optional<String> lang,
    List<PdfStructureElement> children,
    List<Integer> markedContentIds,
    int attributeCount) {
}
