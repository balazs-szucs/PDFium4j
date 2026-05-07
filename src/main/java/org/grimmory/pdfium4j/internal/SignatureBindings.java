package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** FFM bindings for PDFium digital signature functions from {@code fpdf_signature.h}. */
public final class SignatureBindings {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private SignatureBindings() {}

  private static MethodHandle downcall(String name, FunctionDescriptor desc) {
    return LOOKUP.find(name).map(addr -> LINKER.downcallHandle(addr, desc)).orElse(null);
  }

  private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
    return LOOKUP
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, desc, Linker.Option.critical(false)))
        .orElse(null);
  }

  /** Get the number of signatures in the document. */
  public static final MethodHandle FPDF_GetSignatureCount =
      downcallCritical("FPDF_GetSignatureCount", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /** Get the signature object at the given index. */
  public static final MethodHandle FPDF_GetSignatureObject =
      downcall("FPDF_GetSignatureObject", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  /** Get the contents of the signature object. */
  public static final MethodHandle FPDFSignatureObj_GetContents =
      downcall(
          "FPDFSignatureObj_GetContents",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  /** Get the byte range of the signature object. */
  public static final MethodHandle FPDFSignatureObj_GetByteRange =
      downcall(
          "FPDFSignatureObj_GetByteRange",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  /** Get the subfilter of the signature object. */
  public static final MethodHandle FPDFSignatureObj_GetSubFilter =
      downcall(
          "FPDFSignatureObj_GetSubFilter",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  /** Get the reason for the signature. */
  public static final MethodHandle FPDFSignatureObj_GetReason =
      downcall(
          "FPDFSignatureObj_GetReason",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  /** Get the time of the signature. */
  public static final MethodHandle FPDFSignatureObj_GetTime =
      downcall(
          "FPDFSignatureObj_GetTime",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
}
