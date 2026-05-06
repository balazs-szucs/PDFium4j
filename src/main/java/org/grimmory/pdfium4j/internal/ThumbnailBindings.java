package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * FFM bindings for PDFium page thumbnail functions from {@code fpdf_thumbnail.h}.
 */
public final class ThumbnailBindings {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private ThumbnailBindings() {}

  private static MethodHandle downcall(String name, FunctionDescriptor desc) {
    return LOOKUP.find(name).map(addr -> LINKER.downcallHandle(addr, desc)).orElse(null);
  }

  /**
   * Get the thumbnail of a page as a bitmap. (Experimental API)
   */
  public static final MethodHandle FPDFPage_GetThumbnailAsBitmap =
      downcall("FPDFPage_GetThumbnailAsBitmap", FunctionDescriptor.of(ADDRESS, ADDRESS));
}
