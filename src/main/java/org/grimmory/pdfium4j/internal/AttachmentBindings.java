package org.grimmory.pdfium4j.internal;

import static org.grimmory.pdfium4j.internal.FfmHelper.C_INT;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_LONG;
import static org.grimmory.pdfium4j.internal.FfmHelper.C_POINTER;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** FFM bindings for PDFium document attachment functions from {@code fpdf_attachment.h}. */
public final class AttachmentBindings {
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

  private AttachmentBindings() {}

  private static MethodHandle find(String name, FunctionDescriptor desc, boolean critical) {
    java.lang.foreign.MemorySegment addr = LOOKUP.find(name).orElse(null);
    if (addr == null) return null;
    return LINKER.downcallHandle(
        addr, desc, critical ? FfmHelper.CRITICAL_OPTIONS : FfmHelper.NO_OPTIONS);
  }

  public static void checkRequired() {
    // Attachments are optional
  }

  private static final StableValue<MethodHandle> FPDFDoc_GetAttachmentCount_SV = StableValue.of();

  public static MethodHandle FPDFDoc_GetAttachmentCount() {
    return FPDFDoc_GetAttachmentCount_SV.orElseSet(
        () -> find("FPDFDoc_GetAttachmentCount", FunctionDescriptor.of(C_INT, C_POINTER), true));
  }

  private static final StableValue<MethodHandle> FPDFDoc_GetAttachment_SV = StableValue.of();

  public static MethodHandle FPDFDoc_GetAttachment() {
    return FPDFDoc_GetAttachment_SV.orElseSet(
        () ->
            find(
                "FPDFDoc_GetAttachment", FunctionDescriptor.of(C_POINTER, C_POINTER, C_INT), true));
  }

  private static final StableValue<MethodHandle> FPDFAttachment_GetName_SV = StableValue.of();

  public static MethodHandle FPDFAttachment_GetName() {
    return FPDFAttachment_GetName_SV.orElseSet(
        () ->
            find(
                "FPDFAttachment_GetName",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDFAttachment_GetStringValue_SV =
      StableValue.of();

  public static MethodHandle FPDFAttachment_GetStringValue() {
    return FPDFAttachment_GetStringValue_SV.orElseSet(
        () ->
            find(
                "FPDFAttachment_GetStringValue",
                FunctionDescriptor.of(C_LONG, C_POINTER, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDFAttachment_GetFile_SV = StableValue.of();

  public static MethodHandle FPDFAttachment_GetFile() {
    return FPDFAttachment_GetFile_SV.orElseSet(
        () ->
            find(
                "FPDFAttachment_GetFile",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_LONG, C_POINTER),
                false));
  }

  private static final StableValue<MethodHandle> FPDFDoc_AddAttachment_SV = StableValue.of();

  public static MethodHandle FPDFDoc_AddAttachment() {
    return FPDFDoc_AddAttachment_SV.orElseSet(
        () ->
            find(
                "FPDFDoc_AddAttachment",
                FunctionDescriptor.of(C_POINTER, C_POINTER, C_POINTER),
                false));
  }

  private static final StableValue<MethodHandle> FPDFAttachment_SetFile_SV = StableValue.of();

  public static MethodHandle FPDFAttachment_SetFile() {
    return FPDFAttachment_SetFile_SV.orElseSet(
        () ->
            find(
                "FPDFAttachment_SetFile",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_LONG),
                false));
  }

  private static final StableValue<MethodHandle> FPDFAttachment_SetStringValue_SV =
      StableValue.of();

  public static MethodHandle FPDFAttachment_SetStringValue() {
    return FPDFAttachment_SetStringValue_SV.orElseSet(
        () ->
            find(
                "FPDFAttachment_SetStringValue",
                FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER),
                false));
  }
}
