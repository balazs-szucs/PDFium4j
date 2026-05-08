package org.grimmory.pdfium4j.internal;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_LONG;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.StableValue;

/** FFM bindings for PDFium digital signature functions from {@code fpdf_signature.h}. */
public final class SignatureBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
  private SignatureBindings() {}
  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }
  public static void checkRequired() {
    // Signatures are optional
  }
  private static final StableValue<MethodHandle> FPDF_GetSignatureCount_SV = StableValue.of();
  public static MethodHandle FPDF_GetSignatureCount() {
    return FPDF_GetSignatureCount_SV.orElseSet(
        () -> find("FPDF_GetSignatureCount", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }
  private static final StableValue<MethodHandle> FPDF_GetSignatureObject_SV = StableValue.of();
  public static MethodHandle FPDF_GetSignatureObject() {
    return FPDF_GetSignatureObject_SV.orElseSet(
        () ->
            find("FPDF_GetSignatureObject", FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT), false));
  }
  private static final StableValue<MethodHandle> FPDFSignatureObj_GetContents_SV = StableValue.of();
  public static MethodHandle FPDFSignatureObj_GetContents() {
    return FPDFSignatureObj_GetContents_SV.orElseSet(
        () ->
            find(
                "FPDFSignatureObj_GetContents",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }
  private static final StableValue<MethodHandle> FPDFSignatureObj_GetByteRange_SV = StableValue.of();
  public static MethodHandle FPDFSignatureObj_GetByteRange() {
    return FPDFSignatureObj_GetByteRange_SV.orElseSet(
        () ->
            find(
                "FPDFSignatureObj_GetByteRange",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }
  private static final StableValue<MethodHandle> FPDFSignatureObj_GetSubFilter_SV = StableValue.of();
  public static MethodHandle FPDFSignatureObj_GetSubFilter() {
    return FPDFSignatureObj_GetSubFilter_SV.orElseSet(
        () ->
            find(
                "FPDFSignatureObj_GetSubFilter",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }
  private static final StableValue<MethodHandle> FPDFSignatureObj_GetReason_SV = StableValue.of();
  public static MethodHandle FPDFSignatureObj_GetReason() {
    return FPDFSignatureObj_GetReason_SV.orElseSet(
        () ->
            find(
                "FPDFSignatureObj_GetReason",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }
  private static final StableValue<MethodHandle> FPDFSignatureObj_GetTime_SV = StableValue.of();
  public static MethodHandle FPDFSignatureObj_GetTime() {
    return FPDFSignatureObj_GetTime_SV.orElseSet(
        () ->
            find(
                "FPDFSignatureObj_GetTime",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }
}
