package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.ADDRESS;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.internal.StructTreeBindings;
import org.grimmory.pdfium4j.model.PdfStructureElement;

/**
 * Internal helper to read the logical structure tree of a page.
 */
final class StructureTreeReader {

    private StructureTreeReader() {}

    static List<PdfStructureElement> read(MemorySegment pageHandle) {
        if (StructTreeBindings.FPDF_StructTree_GetForPage == null) {
            return List.of();
        }

        try {
            MemorySegment treeHandle = (MemorySegment) StructTreeBindings.FPDF_StructTree_GetForPage.invokeExact(pageHandle);
            if (FfmHelper.isNull(treeHandle)) {
                return List.of();
            }

            try {
                int count = (int) StructTreeBindings.FPDF_StructTree_CountChildren.invokeExact(treeHandle);
                List<PdfStructureElement> roots = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    MemorySegment elementHandle = (MemorySegment) StructTreeBindings.FPDF_StructTree_GetChildAtIndex.invokeExact(treeHandle, i);
                    if (!FfmHelper.isNull(elementHandle)) {
                        roots.add(readElement(elementHandle));
                    }
                }
                return roots;
            } finally {
                StructTreeBindings.FPDF_StructTree_Close.invokeExact(treeHandle);
            }
        } catch (Throwable t) {
            PdfiumLibrary.ignore(t);
            return List.of();
        }
    }

    private static PdfStructureElement readElement(MemorySegment elementHandle) throws Throwable {
        String type = readString(elementHandle, StructTreeBindings.FPDF_StructElement_GetType).orElse("Unknown");
        Optional<String> title = readString(elementHandle, StructTreeBindings.FPDF_StructElement_GetTitle);
        Optional<String> altText = readString(elementHandle, StructTreeBindings.FPDF_StructElement_GetAltText);
        Optional<String> actualText = readString(elementHandle, StructTreeBindings.FPDF_StructElement_GetActualText);
        Optional<String> lang = readString(elementHandle, StructTreeBindings.FPDF_StructElement_GetLang);

        int childCount = (int) StructTreeBindings.FPDF_StructElement_CountChildren.invokeExact(elementHandle);
        List<PdfStructureElement> children = new ArrayList<>(childCount);
        for (int i = 0; i < childCount; i++) {
            MemorySegment childHandle = (MemorySegment) StructTreeBindings.FPDF_StructElement_GetChildAtIndex.invokeExact(elementHandle, i);
            if (!FfmHelper.isNull(childHandle)) {
                children.add(readElement(childHandle));
            }
        }

        return new PdfStructureElement(type, title, altText, actualText, lang, children);
    }

    private static Optional<String> readString(MemorySegment handle, java.lang.invoke.MethodHandle getter) throws Throwable {
        if (getter == null) return Optional.empty();
        try (var _ = ScratchBuffer.acquireScope()) {
            long needed = (long) getter.invokeExact(handle, MemorySegment.NULL, 0L);
            if (needed <= 2) return Optional.empty();
            
            MemorySegment buf = ScratchBuffer.get(needed);
            long copied = (long) getter.invokeExact(handle, buf, needed);
            long byteLen = FfmHelper.normalizeWideByteLength(buf, copied, needed);
            return byteLen == 0 ? Optional.empty() : Optional.of(FfmHelper.fromWideString(buf, byteLen));
        }
    }
}
