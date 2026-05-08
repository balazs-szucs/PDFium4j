package org.grimmory.pdfium4j.internal;

import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_LONG;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

/** FFM bindings for PDFium document metadata and bookmark functions from {@code fpdf_doc.h}. */
public final class DocBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private DocBindings() {}

  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(
        addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }

  public static void checkRequired() {
    Objects.requireNonNull(FPDF_GetMetaText(), "FPDF_GetMetaText");
    Objects.requireNonNull(FPDFBookmark_GetFirstChild(), "FPDFBookmark_GetFirstChild");
    Objects.requireNonNull(FPDFBookmark_GetNextSibling(), "FPDFBookmark_GetNextSibling");
    Objects.requireNonNull(FPDFBookmark_GetTitle(), "FPDFBookmark_GetTitle");
    Objects.requireNonNull(FPDFBookmark_GetDest(), "FPDFBookmark_GetDest");
    Objects.requireNonNull(FPDFBookmark_GetAction(), "FPDFBookmark_GetAction");
    Objects.requireNonNull(FPDFAction_GetType(), "FPDFAction_GetType");
    Objects.requireNonNull(FPDFAction_GetDest(), "FPDFAction_GetDest");
    Objects.requireNonNull(FPDFDest_GetDestPageIndex(), "FPDFDest_GetDestPageIndex");
  }

  private static final StableValue<MethodHandle> FPDF_GetMetaText_SV = StableValue.of();

  public static MethodHandle FPDF_GetMetaText() {
    return FPDF_GetMetaText_SV.orElseSet(
        () ->
            find(
                "FPDF_GetMetaText",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDF_GetXMPMetadata_SV = StableValue.of();

  public static MethodHandle FPDF_GetXMPMetadata() {
    return FPDF_GetXMPMetadata_SV.orElseSet(
        () ->
            find(
                "FPDF_GetXMPMetadata",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDF_GetFileVersion_SV = StableValue.of();

  public static MethodHandle FPDF_GetFileVersion() {
    return FPDF_GetFileVersion_SV.orElseSet(
        () ->
            find("FPDF_GetFileVersion", FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFPage_Delete_SV = StableValue.of();

  public static MethodHandle FPDFPage_Delete() {
    return FPDFPage_Delete_SV.orElseSet(
        () -> find("FPDFPage_Delete", FunctionDescriptor.ofVoid(C_POINTER, C_INT), false));
  }

  private static final StableValue<MethodHandle> FPDFBookmark_GetFirstChild_SV = StableValue.of();

  public static MethodHandle FPDFBookmark_GetFirstChild() {
    return FPDFBookmark_GetFirstChild_SV.orElseSet(
        () ->
            find(
                "FPDFBookmark_GetFirstChild",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFBookmark_GetNextSibling_SV = StableValue.of();

  public static MethodHandle FPDFBookmark_GetNextSibling() {
    return FPDFBookmark_GetNextSibling_SV.orElseSet(
        () ->
            find(
                "FPDFBookmark_GetNextSibling",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFBookmark_GetTitle_SV = StableValue.of();

  public static MethodHandle FPDFBookmark_GetTitle() {
    return FPDFBookmark_GetTitle_SV.orElseSet(
        () ->
            find(
                "FPDFBookmark_GetTitle",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDFBookmark_GetDest_SV = StableValue.of();

  public static MethodHandle FPDFBookmark_GetDest() {
    return FPDFBookmark_GetDest_SV.orElseSet(
        () ->
            find(
                "FPDFBookmark_GetDest",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFBookmark_GetAction_SV = StableValue.of();

  public static MethodHandle FPDFBookmark_GetAction() {
    return FPDFBookmark_GetAction_SV.orElseSet(
        () -> find("FPDFBookmark_GetAction", FunctionDescriptor.of(C_POINTER, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFAction_GetType_SV = StableValue.of();

  public static MethodHandle FPDFAction_GetType() {
    return FPDFAction_GetType_SV.orElseSet(
        () -> find("FPDFAction_GetType", FunctionDescriptor.of(C_LONG, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFAction_GetDest_SV = StableValue.of();

  public static MethodHandle FPDFAction_GetDest() {
    return FPDFAction_GetDest_SV.orElseSet(
        () ->
            find(
                "FPDFAction_GetDest",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                true));
  }

  private static final StableValue<MethodHandle> FPDFDest_GetDestPageIndex_SV = StableValue.of();

  public static MethodHandle FPDFDest_GetDestPageIndex() {
    return FPDFDest_GetDestPageIndex_SV.orElseSet(
        () ->
            find(
                "FPDFDest_GetDestPageIndex",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER),
                true));
  }
}
