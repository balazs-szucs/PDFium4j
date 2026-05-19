package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_BOOL;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_LONG;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;
import static org.grimmory.pdfium4j.internal.FfmHelper.LINKER;
import static org.grimmory.pdfium4j.internal.FfmHelper.LOOKUP;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

/**
 * FFM bindings for PDFium page editing and document saving functions from {@code fpdf_edit.h} and
 * {@code fpdf_save.h}.
 */
public final class EditBindings {

  private EditBindings() {}

  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(
        addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }

  public static void checkRequired() {
    try {
      Objects.requireNonNull(fpdfSaveAsCopy(), "FPDF_SaveAsCopy");
      Objects.requireNonNull(fpdfCreateNewDocument(), "FPDF_CreateNewDocument");
    } catch (NullPointerException e) {
      throw new RuntimeException("Missing required PDFium edit symbol: " + e.getMessage(), e);
    }
  }

  private static final StableValue<MethodHandle> FPDF_CreateNewDocument_V = StableValue.of();

  public static MethodHandle fpdfCreateNewDocument() {
    return FPDF_CreateNewDocument_V.orElseSet(
        () -> find("FPDF_CreateNewDocument", FunctionDescriptor.of(C_POINTER), false));
  }

  private static final StableValue<MethodHandle> FPDFPage_GetRotation_V = StableValue.of();

  public static MethodHandle fpdfPageGetRotation() {
    return FPDFPage_GetRotation_V.orElseSet(
        () -> find("FPDFPage_GetRotation", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFPage_SetRotation_V = StableValue.of();

  public static MethodHandle fpdfPageSetRotation() {
    return FPDFPage_SetRotation_V.orElseSet(
        () -> find("FPDFPage_SetRotation", FunctionDescriptor.ofVoid(C_POINTER, C_INT), false));
  }

  /** FPDF_FILEWRITE struct layout. */
  public static final StructLayout FPDF_FILEWRITE_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_INT.withName("version"),
          MemoryLayout.paddingLayout(4),
          C_POINTER.withName("WriteBlock"),
          C_POINTER.withName("bufferId"));

  /** WriteBlock callback signature. */
  public static final FunctionDescriptor WRITE_BLOCK_DESC =
      FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_LONG);

  private static final StableValue<MethodHandle> FPDF_SaveAsCopy_V = StableValue.of();

  public static MethodHandle fpdfSaveAsCopy() {
    return FPDF_SaveAsCopy_V.orElseSet(
        () ->
            find(
                "FPDF_SaveAsCopy",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT),
                false));
  }

  private static final StableValue<MethodHandle> FPDF_SaveWithVersion_V = StableValue.of();

  public static MethodHandle fpdfSaveWithVersion() {
    return FPDF_SaveWithVersion_V.orElseSet(
        () ->
            find(
                "FPDF_SaveWithVersion",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT, C_INT),
                false));
  }

  public static final int FPDF_NO_INCREMENTAL = 1 << 1;

  private static final StableValue<MethodHandle> FPDFPage_New_V = StableValue.of();

  public static MethodHandle fpdfPageNew() {
    return FPDFPage_New_V.orElseSet(
        () ->
            find(
                "FPDFPage_New",
                FunctionDescriptor.of(
                    C_POINTER, C_POINTER, C_INT, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE),
                false));
  }

  private static final StableValue<MethodHandle> FPDF_ImportPages_V = StableValue.of();

  public static MethodHandle fpdfImportPages() {
    return FPDF_ImportPages_V.orElseSet(
        () ->
            find(
                "FPDF_ImportPages",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_INT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPage_CountObjects_V = StableValue.of();

  public static MethodHandle fpdfPageCountObjects() {
    return FPDFPage_CountObjects_V.orElseSet(
        () -> find("FPDFPage_CountObjects", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFPage_GetObject_V = StableValue.of();

  public static MethodHandle fpdfPageGetObject() {
    return FPDFPage_GetObject_V.orElseSet(
        () -> find("FPDFPage_GetObject", FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT), true));
  }

  private static final StableValue<MethodHandle> FPDFPageObj_GetType_V = StableValue.of();

  public static MethodHandle fpdfPageObjGetType() {
    return FPDFPageObj_GetType_V.orElseSet(
        () -> find("FPDFPageObj_GetType", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  public static final int FPDF_PAGEOBJ_IMAGE = 3;

  private static final StableValue<MethodHandle> FPDFImageObj_GetImageMetadata_V = StableValue.of();

  public static MethodHandle fpdfImageObjGetImageMetadata() {
    return FPDFImageObj_GetImageMetadata_V.orElseSet(
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

  private static final StableValue<MethodHandle> FPDFPageObj_NewImageObj_V = StableValue.of();

  public static MethodHandle fpdfPageObjNewImageObj() {
    return FPDFPageObj_NewImageObj_V.orElseSet(
        () -> find("FPDFPageObj_NewImageObj", FunctionDescriptor.of(C_POINTER, C_POINTER), false));
  }

  private static final StableValue<MethodHandle> FPDFImageObj_SetBitmap_V = StableValue.of();

  public static MethodHandle fpdfImageObjSetBitmap() {
    return FPDFImageObj_SetBitmap_V.orElseSet(
        () ->
            find(
                "FPDFImageObj_SetBitmap",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER, C_POINTER),
                false));
  }

  private static final StableValue<MethodHandle> FPDFImageObj_SetMatrix_V = StableValue.of();

  public static MethodHandle fpdfImageObjSetMatrix() {
    return FPDFImageObj_SetMatrix_V.orElseSet(
        () ->
            find(
                "FPDFImageObj_SetMatrix",
                FunctionDescriptor.of(
                    C_INT,
                    C_POINTER,
                    JAVA_DOUBLE,
                    JAVA_DOUBLE,
                    JAVA_DOUBLE,
                    JAVA_DOUBLE,
                    JAVA_DOUBLE,
                    JAVA_DOUBLE),
                false));
  }

  private static final StableValue<MethodHandle> FPDFImageObj_LoadJpegFileInline_V =
      StableValue.of();

  public static MethodHandle fpdfImageObjLoadJpegFileInline() {
    return FPDFImageObj_LoadJpegFileInline_V.orElseSet(
        () ->
            find(
                "FPDFImageObj_LoadJpegFileInline",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER, C_POINTER),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPage_Flatten_V = StableValue.of();

  public static MethodHandle fpdfPageFlatten() {
    return FPDFPage_Flatten_V.orElseSet(
        () -> find("FPDFPage_Flatten", FunctionDescriptor.of(C_INT, C_POINTER, C_INT), false));
  }

  private static final StableValue<MethodHandle> FPDFPage_GetMediaBox_V = StableValue.of();

  public static MethodHandle fpdfPageGetMediaBox() {
    return FPDFPage_GetMediaBox_V.orElseSet(
        () ->
            find(
                "FPDFPage_GetMediaBox",
                FunctionDescriptor.of(
                    C_BOOL, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFPage_SetMediaBox_V = StableValue.of();

  public static MethodHandle fpdfPageSetMediaBox() {
    return FPDFPage_SetMediaBox_V.orElseSet(
        () ->
            find(
                "FPDFPage_SetMediaBox",
                FunctionDescriptor.ofVoid(
                    C_POINTER,
                    ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_FLOAT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPage_GetCropBox_V = StableValue.of();

  public static MethodHandle fpdfPageGetCropBox() {
    return FPDFPage_GetCropBox_V.orElseSet(
        () ->
            find(
                "FPDFPage_GetCropBox",
                FunctionDescriptor.of(
                    C_BOOL, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFPage_SetCropBox_V = StableValue.of();

  public static MethodHandle fpdfPageSetCropBox() {
    return FPDFPage_SetCropBox_V.orElseSet(
        () ->
            find(
                "FPDFPage_SetCropBox",
                FunctionDescriptor.ofVoid(
                    C_POINTER,
                    ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_FLOAT,
                    ValueLayout.JAVA_FLOAT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPageObj_CreateNewPath_V = StableValue.of();

  public static MethodHandle fpdfPageObjCreateNewPath() {
    return FPDFPageObj_CreateNewPath_V.orElseSet(
        () ->
            find(
                "FPDFPageObj_CreateNewPath",
                FunctionDescriptor.of(C_POINTER, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPath_LineTo_V = StableValue.of();

  public static MethodHandle fpdfPathLineTo() {
    return FPDFPath_LineTo_V.orElseSet(
        () ->
            find(
                "FPDFPath_LineTo",
                FunctionDescriptor.of(
                    C_BOOL, C_POINTER, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPath_ClosePath_V = StableValue.of();

  public static MethodHandle fpdfPathClosePath() {
    return FPDFPath_ClosePath_V.orElseSet(
        () -> find("FPDFPath_Close", FunctionDescriptor.of(C_BOOL, C_POINTER), false));
  }

  private static final StableValue<MethodHandle> FPDFPath_SetDrawMode_V = StableValue.of();

  public static MethodHandle fpdfPathSetDrawMode() {
    return FPDFPath_SetDrawMode_V.orElseSet(
        () ->
            find(
                "FPDFPath_SetDrawMode",
                FunctionDescriptor.of(C_BOOL, C_POINTER, C_INT, C_BOOL),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPath_SetFillColor_V = StableValue.of();

  public static MethodHandle fpdfPathSetFillColor() {
    return FPDFPath_SetFillColor_V.orElseSet(
        () ->
            find(
                "FPDFPageObj_SetFillColor",
                FunctionDescriptor.of(C_BOOL, C_POINTER, C_INT, C_INT, C_INT, C_INT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPage_InsertObject_V = StableValue.of();

  public static MethodHandle fpdfPageInsertObject() {
    return FPDFPage_InsertObject_V.orElseSet(
        () ->
            find("FPDFPage_InsertObject", FunctionDescriptor.ofVoid(C_POINTER, C_POINTER), false));
  }

  private static final StableValue<MethodHandle> FPDFPage_GenerateContent_V = StableValue.of();

  public static MethodHandle fpdfPageGenerateContent() {
    return FPDFPage_GenerateContent_V.orElseSet(
        () -> find("FPDFPage_GenerateContent", FunctionDescriptor.of(C_BOOL, C_POINTER), false));
  }

  private static final StableValue<MethodHandle> FPDFPageObj_GetBounds_V = StableValue.of();

  public static MethodHandle fpdfPageObjGetBounds() {
    return FPDFPageObj_GetBounds_V.orElseSet(
        () ->
            find(
                "FPDFPageObj_GetBounds",
                FunctionDescriptor.of(
                    C_BOOL, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFPage_RemoveObject_V = StableValue.of();

  public static MethodHandle fpdfPageRemoveObject() {
    return FPDFPage_RemoveObject_V.orElseSet(
        () ->
            find(
                "FPDFPage_RemoveObject",
                FunctionDescriptor.of(C_BOOL, C_POINTER, C_POINTER),
                false));
  }

  private static final StableValue<MethodHandle> FPDFPageObj_Destroy_V = StableValue.of();

  public static MethodHandle fpdfPageObjDestroy() {
    return FPDFPageObj_Destroy_V.orElseSet(
        () -> find("FPDFPageObj_Destroy", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
}
