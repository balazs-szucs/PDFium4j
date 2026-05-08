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
import java.util.Objects;

/** FFM bindings for PDFium annotation functions from {@code fpdf_annot.h}. */
public final class AnnotBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private AnnotBindings() {}

  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(
        addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }

  public static void checkRequired() {
    Objects.requireNonNull(FPDFPage_GetAnnotCount(), "FPDFPage_GetAnnotCount");
    Objects.requireNonNull(FPDFPage_GetAnnot(), "FPDFPage_GetAnnot");
    Objects.requireNonNull(FPDFPage_CloseAnnot(), "FPDFPage_CloseAnnot");
    Objects.requireNonNull(FPDFAnnot_GetSubtype(), "FPDFAnnot_GetSubtype");
    Objects.requireNonNull(FPDFAnnot_GetStringValue(), "FPDFAnnot_GetStringValue");
    Objects.requireNonNull(FPDFAnnot_GetRect(), "FPDFAnnot_GetRect");
  }

  /** FS_RECTF struct layout: left, bottom, right, top (all floats). */
  public static final StructLayout FS_RECTF_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_FLOAT.withName("left"),
          ValueLayout.JAVA_FLOAT.withName("bottom"),
          ValueLayout.JAVA_FLOAT.withName("right"),
          ValueLayout.JAVA_FLOAT.withName("top"));

  private static final StableValue<MethodHandle> FPDFPage_GetAnnotCount_SV = StableValue.of();

  public static MethodHandle FPDFPage_GetAnnotCount() {
    return FPDFPage_GetAnnotCount_SV.orElseSet(
        () -> find("FPDFPage_GetAnnotCount", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFPage_GetAnnot_SV = StableValue.of();

  public static MethodHandle FPDFPage_GetAnnot() {
    return FPDFPage_GetAnnot_SV.orElseSet(
        () -> find("FPDFPage_GetAnnot", FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT), true));
  }

  private static final StableValue<MethodHandle> FPDFPage_CloseAnnot_SV = StableValue.of();

  public static MethodHandle FPDFPage_CloseAnnot() {
    return FPDFPage_CloseAnnot_SV.orElseSet(
        () -> find("FPDFPage_CloseAnnot", FunctionDescriptor.ofVoid(C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFAnnot_GetSubtype_SV = StableValue.of();

  public static MethodHandle FPDFAnnot_GetSubtype() {
    return FPDFAnnot_GetSubtype_SV.orElseSet(
        () -> find("FPDFAnnot_GetSubtype", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFAnnot_GetStringValue_SV = StableValue.of();

  public static MethodHandle FPDFAnnot_GetStringValue() {
    return FPDFAnnot_GetStringValue_SV.orElseSet(
        () ->
            find(
                "FPDFAnnot_GetStringValue",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDFAnnot_GetRect_SV = StableValue.of();

  public static MethodHandle FPDFAnnot_GetRect() {
    return FPDFAnnot_GetRect_SV.orElseSet(
        () -> find("FPDFAnnot_GetRect", FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER), true));
  }
}
