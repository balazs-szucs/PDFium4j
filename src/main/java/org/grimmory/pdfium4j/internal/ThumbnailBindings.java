package org.grimmory.pdfium4j.internal;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.StableValue;
/** FFM bindings for PDFium page thumbnail functions from {@code fpdf_thumbnail.h}. */
public final class ThumbnailBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
  private ThumbnailBindings() {}
  private static MethodHandle findOptional(FunctionDescriptor desc) {
    java.util.Optional<java.lang.foreign.MemorySegment> addr = LOOKUP.find("FPDFPage_GetThumbnailAsBitmap");
    return addr.map(memorySegment -> LINKER.downcallHandle(memorySegment, desc)).orElse(null);
  }
  private static final StableValue<MethodHandle> FPDFPage_GetThumbnailAsBitmap_SV = StableValue.of();
  /** Get the thumbnail of a page as a bitmap. (Experimental API) */
  public static MethodHandle FPDFPage_GetThumbnailAsBitmap() {
    return FPDFPage_GetThumbnailAsBitmap_SV.orElseSet(
        () -> findOptional(FunctionDescriptor.of(C_POINTER, C_POINTER)));
  }
}
