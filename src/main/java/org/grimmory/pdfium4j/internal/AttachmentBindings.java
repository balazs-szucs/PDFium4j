package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * FFM bindings for PDFium document attachment functions from {@code fpdf_attachment.h}.
 */
public final class AttachmentBindings {

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private AttachmentBindings() {}

  private static MethodHandle downcall(String name, FunctionDescriptor desc) {
    return LOOKUP.find(name).map(addr -> LINKER.downcallHandle(addr, desc)).orElse(null);
  }

  private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
    return LOOKUP
        .find(name)
        .map(addr -> LINKER.downcallHandle(addr, desc, Linker.Option.critical(false)))
        .orElse(null);
  }

  public static final MethodHandle FPDFDoc_GetAttachmentCount =
      downcallCritical("FPDFDoc_GetAttachmentCount", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  public static final MethodHandle FPDFDoc_GetAttachment =
      downcallCritical("FPDFDoc_GetAttachment", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

  public static final MethodHandle FPDFAttachment_GetName =
      downcall("FPDFAttachment_GetName", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDFAttachment_HasKey =
      downcall("FPDFAttachment_HasKey", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

  public static final MethodHandle FPDFAttachment_GetStringValue =
      downcall("FPDFAttachment_GetStringValue", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

  public static final MethodHandle FPDFAttachment_GetFile =
      downcall("FPDFAttachment_GetFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
}
