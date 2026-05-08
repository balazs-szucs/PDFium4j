package org.grimmory.pdfium4j.internal;

import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_LONG;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

/** FFM bindings for PDFium bitmap functions from {@code fpdfview.h}. */
public final class BitmapBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private BitmapBindings() {}

  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(
        addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }

  public static void checkRequired() {
    Objects.requireNonNull(FPDFBitmap_Create(), "FPDFBitmap_Create");
    Objects.requireNonNull(FPDFBitmap_Destroy(), "FPDFBitmap_Destroy");
    Objects.requireNonNull(FPDFBitmap_GetBuffer(), "FPDFBitmap_GetBuffer");
    Objects.requireNonNull(FPDFBitmap_GetWidth(), "FPDFBitmap_GetWidth");
    Objects.requireNonNull(FPDFBitmap_GetHeight(), "FPDFBitmap_GetHeight");
    Objects.requireNonNull(FPDFBitmap_GetStride(), "FPDFBitmap_GetStride");
  }

  private static final StableValue<MethodHandle> FPDFBitmap_Create_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_Create() {
    return FPDFBitmap_Create_SV.orElseSet(
        () ->
            find(
                "FPDFBitmap_Create", FunctionDescriptor.of(C_POINTER, C_INT, C_INT, C_INT), false));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_CreateEx_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_CreateEx() {
    return FPDFBitmap_CreateEx_SV.orElseSet(
        () ->
            find(
                "FPDFBitmap_CreateEx",
                FunctionDescriptor.of(C_POINTER, C_INT, C_INT, C_INT, C_POINTER, C_INT),
                false));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_FillRect_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_FillRect() {
    return FPDFBitmap_FillRect_SV.orElseSet(
        () ->
            find(
                "FPDFBitmap_FillRect",
                FunctionDescriptor.ofVoid(C_POINTER, C_INT, C_INT, C_INT, C_INT, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_GetBuffer_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_GetBuffer() {
    return FPDFBitmap_GetBuffer_SV.orElseSet(
        () -> find("FPDFBitmap_GetBuffer", FunctionDescriptor.of(C_POINTER, C_POINTER), false));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_GetWidth_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_GetWidth() {
    return FPDFBitmap_GetWidth_SV.orElseSet(
        () -> find("FPDFBitmap_GetWidth", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_GetHeight_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_GetHeight() {
    return FPDFBitmap_GetHeight_SV.orElseSet(
        () -> find("FPDFBitmap_GetHeight", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_GetStride_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_GetStride() {
    return FPDFBitmap_GetStride_SV.orElseSet(
        () -> find("FPDFBitmap_GetStride", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFBitmap_Destroy_SV = StableValue.of();

  public static MethodHandle FPDFBitmap_Destroy() {
    return FPDFBitmap_Destroy_SV.orElseSet(
        () -> find("FPDFBitmap_Destroy", FunctionDescriptor.ofVoid(C_POINTER), false));
  }
}
