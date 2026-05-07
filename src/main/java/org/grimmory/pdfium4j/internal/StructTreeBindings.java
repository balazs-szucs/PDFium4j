package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * FFM bindings for PDFium accessibility and logical structure functions from {@code
 * fpdf_structtree.h}.
 */
public final class StructTreeBindings {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private StructTreeBindings() {}

  private static MethodHandle downcall(String name, FunctionDescriptor desc) {
    return LOOKUP.find(name).map(addr -> LINKER.downcallHandle(addr, desc)).orElse(null);
  }

  private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
    return LOOKUP
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, desc, Linker.Option.critical(false)))
        .orElse(null);
  }

  // Structure Tree functions
  public static final MethodHandle FPDF_StructTree_GetForPage =
      downcall("FPDF_StructTree_GetForPage", FunctionDescriptor.of(ADDRESS, ADDRESS));

  public static final MethodHandle FPDF_StructTree_Close =
      downcallCritical("FPDF_StructTree_Close", FunctionDescriptor.ofVoid(ADDRESS));

  public static final MethodHandle FPDF_StructTree_CountChildren =
      downcallCritical("FPDF_StructTree_CountChildren", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle FPDF_StructTree_GetChildAtIndex =
      downcallCritical(
          "FPDF_StructTree_GetChildAtIndex", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  // Structure Element functions
  public static final MethodHandle FPDF_StructElement_GetType =
      downcall(
          "FPDF_StructElement_GetType",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDF_StructElement_GetAltText =
      downcall(
          "FPDF_StructElement_GetAltText",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDF_StructElement_GetActualText =
      downcall(
          "FPDF_StructElement_GetActualText",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDF_StructElement_GetLang =
      downcall(
          "FPDF_StructElement_GetLang",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDF_StructElement_CountChildren =
      downcallCritical(
          "FPDF_StructElement_CountChildren", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle FPDF_StructElement_GetChildAtIndex =
      downcallCritical(
          "FPDF_StructElement_GetChildAtIndex", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle FPDF_StructElement_GetTitle =
      downcall(
          "FPDF_StructElement_GetTitle",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDF_StructElement_GetParent =
      downcallCritical("FPDF_StructElement_GetParent", FunctionDescriptor.of(ADDRESS, ADDRESS));

  public static final MethodHandle FPDF_StructElement_GetAttributeCount =
      downcallCritical(
          "FPDF_StructElement_GetAttributeCount", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle FPDF_StructElement_GetAttributeAtIndex =
      downcallCritical(
          "FPDF_StructElement_GetAttributeAtIndex",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle FPDF_StructElement_GetMarkedContentIdAtIndex =
      downcallCritical(
          "FPDF_StructElement_GetMarkedContentIdAtIndex",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
}
