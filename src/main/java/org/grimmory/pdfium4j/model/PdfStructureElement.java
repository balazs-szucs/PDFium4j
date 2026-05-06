package org.grimmory.pdfium4j.model;

import java.util.List;
import java.util.Optional;

/**
 * Represents a logical element in a PDF structure tree (e.g., Heading, Table, Paragraph).
 * Used for accessibility and structured data extraction from Tagged PDFs.
 */
public record PdfStructureElement(
    String type,
    Optional<String> title,
    Optional<String> altText,
    Optional<String> actualText,
    Optional<String> lang,
    List<PdfStructureElement> children
) {
    /**
     * Recursively find all elements of a specific type.
     *
     * @param type the type to search for (e.g. "H1", "Table")
     * @return a list of matching elements
     */
    public List<PdfStructureElement> findAll(String type) {
        java.util.List<PdfStructureElement> result = new java.util.ArrayList<>();
        if (this.type.equalsIgnoreCase(type)) {
            result.add(this);
        }
        for (PdfStructureElement child : children) {
            result.addAll(child.findAll(type));
        }
        return result;
    }
}
