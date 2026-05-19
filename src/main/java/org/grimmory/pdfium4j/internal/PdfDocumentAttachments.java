package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.exception.PdfiumException;
import org.grimmory.pdfium4j.model.PdfAttachment;

/** Internal helper for PDF document attachment extraction. */
public final class PdfDocumentAttachments {

  private PdfDocumentAttachments() {}

  public static List<PdfAttachment> readAttachments(MemorySegment handle) {
    MethodHandle countGetter = AttachmentBindings.fpdfDocGetAttachmentCount();
    if (countGetter == null) {
      return Collections.emptyList();
    }
    try {
      int count = (int) countGetter.invokeExact(handle);
      if (count <= 0) return Collections.emptyList();

      MethodHandle attachmentGetter = AttachmentBindings.fpdfDocGetAttachment();
      if (attachmentGetter == null) return Collections.emptyList();

      List<PdfAttachment> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        MemorySegment attachment = (MemorySegment) attachmentGetter.invokeExact(handle, i);
        if (FfmHelper.isNull(attachment)) continue;

        String name =
            readAttachmentString(attachment, AttachmentBindings.fpdfAttachmentGetName())
                .orElse("unnamed");
        long size = getAttachmentFileSize(attachment);

        result.add(
            new PdfAttachment(
                i,
                name,
                size,
                getAttachmentMetadata(attachment, "Desc"),
                getAttachmentMetadata(attachment, "CreationDate"),
                getAttachmentMetadata(attachment, "ModDate")));
      }
      return Collections.unmodifiableList(result);
    } catch (Throwable t) {
      if (t instanceof PdfiumException pe) throw pe;
      throw new PdfiumException("Failed to read attachments", t);
    }
  }

  private static long getAttachmentFileSize(MemorySegment attachment) {
    MethodHandle getFile = AttachmentBindings.fpdfAttachmentGetFile();
    if (getFile == null) return 0;

    try (var _ = ScratchBuffer.acquireScope()) {
      MemorySegment outBuflen = ScratchBuffer.get(FfmHelper.C_LONG.byteSize());
      // FPDFAttachment_GetFile(attachment, buffer, buflen, out_buflen) returns FPDF_BOOL
      int success = (int) getFile.invoke(attachment, MemorySegment.NULL, 0L, outBuflen);
      if (success == 0) return 0;
      return FfmHelper.readCLong(outBuflen, 0);
    } catch (Throwable t) {
      PdfiumLibrary.ignore(t);
      return 0;
    }
  }

  private static Optional<String> getAttachmentMetadata(MemorySegment attachment, String key) {
    return readAttachmentString(attachment, key);
  }

  private static Optional<String> readAttachmentString(MemorySegment handle, MethodHandle getter) {
    if (getter == null) return Optional.empty();
    try (var _ = ScratchBuffer.acquireScope()) {
      try {
        long needed = (long) getter.invoke(handle, MemorySegment.NULL, 0L);
        if (needed <= 2) return Optional.empty();
        MemorySegment buf = ScratchBuffer.get(needed);
        long copied = (long) getter.invoke(handle, buf, needed);
        return Optional.of(FfmHelper.fromWideString(buf, copied));
      } catch (Throwable _) {
        return Optional.empty();
      }
    }
  }

  private static Optional<String> readAttachmentString(MemorySegment handle, String key) {
    MethodHandle getter = AttachmentBindings.fpdfAttachmentGetStringValue();
    if (getter == null) return Optional.empty();
    try (var _ = ScratchBuffer.acquireScope()) {
      try {
        // Probe size with a key-only allocation.
        MemorySegment keyProbe = ScratchBuffer.getUtf8(key);
        long needed = (long) getter.invoke(handle, keyProbe, MemorySegment.NULL, 0L);
        if (needed <= 2) return Optional.empty();

        // Allocate key + value in non-overlapping slots in one slab.
        var slots = ScratchBuffer.utf8KeyAndWideValue(key, needed);
        long copied = (long) getter.invoke(handle, slots.keySeg(), slots.valueSeg(), needed);
        return Optional.of(FfmHelper.fromWideString(slots.valueSeg(), copied));
      } catch (Throwable _) {
        return Optional.empty();
      }
    }
  }

  public static void addAttachment(
      MemorySegment docHandle, String name, byte[] data, Map<String, String> metadata) {
    MethodHandle addAttachment = AttachmentBindings.fpdfDocAddAttachment();
    MethodHandle setFile = AttachmentBindings.fpdfAttachmentSetFile();
    MethodHandle setMetadata = AttachmentBindings.fpdfAttachmentSetStringValue();

    if (addAttachment == null || setFile == null) {
      throw new UnsupportedOperationException(
          "Attachment editing is not supported by the loaded PDFium library");
    }

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nameSeg = FfmHelper.toWideString(arena, name);
      MemorySegment attachment = (MemorySegment) addAttachment.invokeExact(docHandle, nameSeg);
      if (FfmHelper.isNull(attachment)) {
        throw new PdfiumException("Failed to add attachment: " + name);
      }

      MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, data);
      int ok = (int) setFile.invokeExact(attachment, docHandle, dataSeg, (long) data.length);
      if (ok == 0) {
        throw new PdfiumException("Failed to set attachment file data for: " + name);
      }

      if (metadata != null && setMetadata != null) {
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
          MemorySegment keySeg = arena.allocateFrom(entry.getKey());
          MemorySegment valSeg = FfmHelper.toWideString(arena, entry.getValue());
          int success = (int) setMetadata.invokeExact(attachment, keySeg, valSeg);
          if (success == 0) {
            // Log a warning if a specific metadata key could not be set.
            InternalLogger.warn("Failed to set attachment metadata key: " + entry.getKey());
          }
        }
      }
    } catch (Throwable t) {
      if (t instanceof PdfiumException pe) throw pe;
      throw new PdfiumException("Failed to add attachment: " + name, t);
    }
  }
}
