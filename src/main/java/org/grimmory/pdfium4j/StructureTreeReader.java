package org.grimmory.pdfium4j;
 
import static java.lang.foreign.ValueLayout.ADDRESS;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.internal.ShimBindings;
import org.grimmory.pdfium4j.model.PdfStructureElement;
 
/**
 * Internal helper to read the logical structure tree of a page using optimized shim bindings.
 */
final class StructureTreeReader {
 
    private StructureTreeReader() {}
 
    static List<PdfStructureElement> read(MemorySegment pageHandle) {
        if (ShimBindings.pdfium4j_struct_tree_get == null) {
            return List.of();
        }
 
        try {
            MemorySegment treeHandle = (MemorySegment) ShimBindings.pdfium4j_struct_tree_get.invokeExact(pageHandle);
            if (FfmHelper.isNull(treeHandle)) {
                return List.of();
            }
 
            try {
                int count = (int) ShimBindings.pdfium4j_struct_tree_count_children.invokeExact(treeHandle);
                List<PdfStructureElement> roots = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    MemorySegment elementHandle = (MemorySegment) ShimBindings.pdfium4j_struct_tree_get_child.invokeExact(treeHandle, i);
                    if (!FfmHelper.isNull(elementHandle)) {
                        roots.add(readElement(elementHandle));
                    }
                }
                return roots;
            } finally {
                ShimBindings.pdfium4j_struct_tree_close.invokeExact(treeHandle);
            }
        } catch (Throwable t) {
            PdfiumLibrary.ignore(t);
            return List.of();
        }
    }
 
    private static PdfStructureElement readElement(MemorySegment elementHandle) throws Throwable {
        String type = readUtf8(elementHandle, ShimBindings.pdfium4j_struct_element_get_type).orElse("Unknown");
        Optional<String> title = readUtf8(elementHandle, ShimBindings.pdfium4j_struct_element_get_title);
        Optional<String> altText = readUtf8(elementHandle, ShimBindings.pdfium4j_struct_element_get_alt_text);
        Optional<String> actualText = readUtf8(elementHandle, ShimBindings.pdfium4j_struct_element_get_actual_text);
        Optional<String> lang = readUtf8(elementHandle, ShimBindings.pdfium4j_struct_element_get_lang);
 
        int attributeCount = (int) ShimBindings.pdfium4j_struct_element_get_attribute_count.invokeExact(elementHandle);
        int childCount = (int) ShimBindings.pdfium4j_struct_element_count_children.invokeExact(elementHandle);
        
        List<Integer> mcids = new ArrayList<>();
        List<PdfStructureElement> children = new ArrayList<>();
        
        for (int i = 0; i < childCount; i++) {
            MemorySegment childHandle = (MemorySegment) ShimBindings.pdfium4j_struct_element_get_child.invokeExact(elementHandle, i);
            if (FfmHelper.isNull(childHandle)) {
                int mcid = (int) ShimBindings.pdfium4j_struct_element_get_mcid.invokeExact(elementHandle, i);
                if (mcid >= 0) {
                    mcids.add(mcid);
                }
            } else {
                children.add(readElement(childHandle));
            }
        }
 
        return new PdfStructureElement(type, title, altText, actualText, lang, children, mcids, attributeCount);
    }
 
    private static Optional<String> readUtf8(MemorySegment handle, MethodHandle getter) throws Throwable {
        if (getter == null) return Optional.empty();
        try (var _ = ScratchBuffer.acquireScope()) {
            int needed = (int) getter.invokeExact(handle, MemorySegment.NULL, 0);
            if (needed <= 1) return Optional.empty();
            
            MemorySegment buf = ScratchBuffer.get(needed);
            int copied = (int) getter.invokeExact(handle, buf, needed);
            if (copied <= 1) return Optional.empty();
            
            return Optional.of(buf.reinterpret(needed).getString(0));
        }
    }
}
