package org.grimmory.pdfium4j.internal;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_LONG;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.StableValue;
import java.util.Objects;
/** FFM bindings for PDFium core functions from {@code fpdfview.h}. */
public final class ViewBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
  private ViewBindings() {}
  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }
  public static void checkRequired() {
    Objects.requireNonNull(FPDF_InitLibraryWithConfig(), "FPDF_InitLibraryWithConfig");
    Objects.requireNonNull(FPDF_DestroyLibrary(), "FPDF_DestroyLibrary");
    Objects.requireNonNull(FPDF_LoadDocument(), "FPDF_LoadDocument");
    Objects.requireNonNull(FPDF_CloseDocument(), "FPDF_CloseDocument");
    Objects.requireNonNull(FPDF_GetLastError(), "FPDF_GetLastError");
    Objects.requireNonNull(FPDF_GetPageCount(), "FPDF_GetPageCount");
    Objects.requireNonNull(FPDF_LoadPage(), "FPDF_LoadPage");
    Objects.requireNonNull(FPDF_ClosePage(), "FPDF_ClosePage");
    Objects.requireNonNull(FPDF_RenderPageBitmap(), "FPDF_RenderPageBitmap");
  }
  public static final StructLayout LIBRARY_CONFIG_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_INT.withName("version"),
          MemoryLayout.paddingLayout(4),
          C_POINTER.withName("m_pUserFontPaths"),
          C_POINTER.withName("m_pIsolate"),
          ValueLayout.JAVA_INT.withName("m_v8EmbedderSlot"),
          MemoryLayout.paddingLayout(4),
          C_POINTER.withName("m_pPlatform"),
          C_POINTER.withName("m_pRendererType"));
  public static final StructLayout FPDF_FILEACCESS_LAYOUT =
      MemoryLayout.structLayout(
          C_LONG.withName("m_FileLen"), C_POINTER.withName("m_GetBlock"), C_POINTER.withName("m_Param"));
  public static final FunctionDescriptor GET_BLOCK_DESC =
      FunctionDescriptor.of(C_INT, C_POINTER, C_LONG, C_POINTER, C_LONG);
  private static final StableValue<MethodHandle> FPDF_InitLibraryWithConfig_SV = StableValue.of();
  public static MethodHandle FPDF_InitLibraryWithConfig() {
    return FPDF_InitLibraryWithConfig_SV.orElseSet(
        () -> find("FPDF_InitLibraryWithConfig", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDF_DestroyLibrary_SV = StableValue.of();
  public static MethodHandle FPDF_DestroyLibrary() {
    return FPDF_DestroyLibrary_SV.orElseSet(
        () -> find("FPDF_DestroyLibrary", FunctionDescriptor.ofVoid(), false));
  }
  private static final StableValue<MethodHandle> FPDF_LoadDocument_SV = StableValue.of();
  public static MethodHandle FPDF_LoadDocument() {
    return FPDF_LoadDocument_SV.orElseSet(
        () ->
            find("FPDF_LoadDocument", FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDF_LoadMemDocument_SV = StableValue.of();
  public static MethodHandle FPDF_LoadMemDocument() {
    return FPDF_LoadMemDocument_SV.orElseSet(
        () ->
            find(
                "FPDF_LoadMemDocument",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> FPDF_LoadCustomDocument_SV = StableValue.of();
  public static MethodHandle FPDF_LoadCustomDocument() {
    return FPDF_LoadCustomDocument_SV.orElseSet(
        () ->
            find(
                "FPDF_LoadCustomDocument",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                false));
  }
  private static final StableValue<MethodHandle> FPDF_CloseDocument_SV = StableValue.of();
  public static MethodHandle FPDF_CloseDocument() {
    return FPDF_CloseDocument_SV.orElseSet(
        () -> find("FPDF_CloseDocument", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDF_GetLastError_SV = StableValue.of();
  public static MethodHandle FPDF_GetLastError() {
    return FPDF_GetLastError_SV.orElseSet(
        () -> find("FPDF_GetLastError", FunctionDescriptor.of(C_LONG), true));
  }
  private static final StableValue<MethodHandle> FPDF_DocumentHasValidCrossReferenceTable_SV =
      StableValue.of();
  public static MethodHandle FPDF_DocumentHasValidCrossReferenceTable() {
    return FPDF_DocumentHasValidCrossReferenceTable_SV.orElseSet(
        () ->
            find(
                "FPDF_DocumentHasValidCrossReferenceTable",
                FunctionDescriptor.of(C_INT, C_POINTER),
                true));
  }
  private static final StableValue<MethodHandle> FPDF_GetTrailerEnds_SV = StableValue.of();
  public static MethodHandle FPDF_GetTrailerEnds() {
    return FPDF_GetTrailerEnds_SV.orElseSet(
        () ->
            find(
                "FPDF_GetTrailerEnds",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }
  private static final StableValue<MethodHandle> FPDF_GetPageCount_SV = StableValue.of();
  public static MethodHandle FPDF_GetPageCount() {
    return FPDF_GetPageCount_SV.orElseSet(
        () -> find("FPDF_GetPageCount", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> FPDF_LoadPage_SV = StableValue.of();
  public static MethodHandle FPDF_LoadPage() {
    return FPDF_LoadPage_SV.orElseSet(
        () -> find("FPDF_LoadPage", FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT), false));
  }
  private static final StableValue<MethodHandle> FPDF_ClosePage_SV = StableValue.of();
  public static MethodHandle FPDF_ClosePage() {
    return FPDF_ClosePage_SV.orElseSet(
        () -> find("FPDF_ClosePage", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
  private static final StableValue<MethodHandle> FPDF_GetPageWidthF_SV = StableValue.of();
  public static MethodHandle FPDF_GetPageWidthF() {
    return FPDF_GetPageWidthF_SV.orElseSet(
        () ->
            find(
                "FPDF_GetPageWidthF",
                FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDF_GetPageHeightF_SV = StableValue.of();
  public static MethodHandle FPDF_GetPageHeightF() {
    return FPDF_GetPageHeightF_SV.orElseSet(
        () ->
            find(
                "FPDF_GetPageHeightF",
                FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDF_RenderPageBitmap_SV = StableValue.of();
  public static MethodHandle FPDF_RenderPageBitmap() {
    return FPDF_RenderPageBitmap_SV.orElseSet(
        () ->
            find(
                "FPDF_RenderPageBitmap",
                FunctionDescriptor.ofVoid(
                    C_POINTER, C_POINTER, C_INT, C_INT, C_INT, C_INT, C_INT, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> FPDF_SetRendererType_SV = StableValue.of();
  public static MethodHandle FPDF_SetRendererType() {
    return FPDF_SetRendererType_SV.orElseSet(
        () -> find("FPDF_SetRendererType", FunctionDescriptor.ofVoid(C_INT), false));
  }
  private static final StableValue<MethodHandle> FPDF_LoadMemDocument64_SV = StableValue.of();
  public static MethodHandle FPDF_LoadMemDocument64() {
    return FPDF_LoadMemDocument64_SV.orElseSet(
        () ->
            find(
                "FPDF_LoadMemDocument64",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_LONG, C_POINTER),
                false));
  }
  public static final int FPDF_RENDERER_TYPE_SKIA = 1;
  public static final int FPDF_ERR_FORMAT = 3;
  public static final int FPDF_ERR_PASSWORD = 4;
  public static final int FPDF_ERR_SECURITY = 5;
  public static final int FPDF_ANNOT = 0x01;
  public static final int FPDF_LCD_TEXT = 0x02;
  public static final int FPDF_GRAYSCALE = 0x08;
  public static final int FPDF_REVERSE_BYTE_ORDER = 0x10;
  public static final int FPDF_PRINTING = 0x800;
  public static final int FPDF_RENDER_NO_SMOOTHTEXT = 0x1000;
  public static final int FPDF_RENDER_NO_SMOOTHIMAGE = 0x2000;
  public static final int FPDF_RENDER_NO_SMOOTHPATH = 0x4000;
}
