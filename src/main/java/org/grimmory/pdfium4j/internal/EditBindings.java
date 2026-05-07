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
/**
 * FFM bindings for PDFium page editing and document saving functions from {@code fpdf_edit.h} and
 * {@code fpdf_save.h}.
 */
public final class EditBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
  private EditBindings() {}
  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }
  public static void checkRequired() {
    Objects.requireNonNull(FPDF_SaveAsCopy(), "FPDF_SaveAsCopy");
  }
  private static final StableValue<MethodHandle> FPDFPage_GetRotation_SV = StableValue.of();
  public static MethodHandle FPDFPage_GetRotation() {
    return FPDFPage_GetRotation_SV.orElseSet(
        () -> find("FPDFPage_GetRotation", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> FPDFPage_SetRotation_SV = StableValue.of();
  public static MethodHandle FPDFPage_SetRotation() {
    return FPDFPage_SetRotation_SV.orElseSet(
        () -> find("FPDFPage_SetRotation", FunctionDescriptor.ofVoid(C_POINTER, C_INT), false));
  }
  /** FPDF_FILEWRITE struct layout. */
  public static final StructLayout FPDF_FILEWRITE_LAYOUT =
      MemoryLayout.structLayout(
          C_INT.withName("version"),
          MemoryLayout.paddingLayout(4),
          C_POINTER.withName("WriteBlock"),
          C_LONG.withName("bufferId"));
  /** WriteBlock callback signature. */
  public static final FunctionDescriptor WRITE_BLOCK_DESC =
      FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_LONG);
  private static final StableValue<MethodHandle> FPDF_SaveAsCopy_SV = StableValue.of();
  public static MethodHandle FPDF_SaveAsCopy() {
    return FPDF_SaveAsCopy_SV.orElseSet(
        () ->
            find(
                "FPDF_SaveAsCopy",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> FPDF_SaveWithVersion_SV = StableValue.of();
  public static MethodHandle FPDF_SaveWithVersion() {
    return FPDF_SaveWithVersion_SV.orElseSet(
        () ->
            find(
                "FPDF_SaveWithVersion",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT, C_INT),
                false));
  }
  public static final int FPDF_NO_INCREMENTAL = 1 << 1;
  private static final StableValue<MethodHandle> FPDFPage_New_SV = StableValue.of();
  public static MethodHandle FPDFPage_New() {
    return FPDFPage_New_SV.orElseSet(
        () ->
            find(
                "FPDFPage_New",
                FunctionDescriptor.of(
                    C_POINTER, C_POINTER, C_INT, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE),
                false));
  }
  private static final StableValue<MethodHandle> FPDF_ImportPages_SV = StableValue.of();
  public static MethodHandle FPDF_ImportPages() {
    return FPDF_ImportPages_SV.orElseSet(
        () ->
            find(
                "FPDF_ImportPages",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_INT),
                false));
  }
  private static final StableValue<MethodHandle> FPDFPage_CountObjects_SV = StableValue.of();
  public static MethodHandle FPDFPage_CountObjects() {
    return FPDFPage_CountObjects_SV.orElseSet(
        () -> find("FPDFPage_CountObjects", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> FPDFPage_GetObject_SV = StableValue.of();
  public static MethodHandle FPDFPage_GetObject() {
    return FPDFPage_GetObject_SV.orElseSet(
        () -> find("FPDFPage_GetObject", FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT), true));
  }
  private static final StableValue<MethodHandle> FPDFPageObj_GetType_SV = StableValue.of();
  public static MethodHandle FPDFPageObj_GetType() {
    return FPDFPageObj_GetType_SV.orElseSet(
        () -> find("FPDFPageObj_GetType", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  public static final int FPDF_PAGEOBJ_IMAGE = 3;
  private static final StableValue<MethodHandle> FPDFImageObj_GetImageMetadata_SV = StableValue.of();
  public static MethodHandle FPDFImageObj_GetImageMetadata() {
    return FPDFImageObj_GetImageMetadata_SV.orElseSet(
        () ->
            find(
                "FPDFImageObj_GetImageMetadata",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER),
                false));
  }
  public static final StructLayout IMAGE_METADATA_LAYOUT =
      MemoryLayout.structLayout(
          C_INT.withName("width"),
          C_INT.withName("height"),
          ValueLayout.JAVA_FLOAT.withName("horizontal_dpi"),
          ValueLayout.JAVA_FLOAT.withName("vertical_dpi"),
          C_INT.withName("bits_per_pixel"),
          C_INT.withName("colorspace"),
          C_INT.withName("marked_content_id"));
}
