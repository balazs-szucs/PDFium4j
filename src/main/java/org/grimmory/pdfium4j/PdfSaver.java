package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.grimmory.pdfium4j.exception.PdfiumException;
import org.grimmory.pdfium4j.internal.EditBindings;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.IoUtils;
import org.grimmory.pdfium4j.model.MetadataTag;

/**
 * Handles saving PDF documents. Uses PDFium's native FPDF_SaveAsCopy for the base save, then
 * applies pure-Java incremental updates for Info dictionary and XMP metadata.
 *
 * <p>Uses byte-level scanning to avoid OOM issues with large files.
 */
final class PdfSaver {

  /**
   * Per-thread callback sink for native FPDF_SaveAsCopy bytes. Set for one save call and removed in
   * finally so large backing arrays are not retained by pooled threads.
   */
  private static final int SAVE_CALLBACK_INITIAL_CAPACITY = 8192;

  private static final int SAVE_CALLBACK_MAX_RETAINED_CAPACITY = 65536;
  private static final int STREAM_DECODE_INITIAL_CAPACITY = 8192;
  private static final int STREAM_DECODE_MAX_RETAINED_CAPACITY = 65536;

  private static final ThreadLocal<ReusableByteArrayOutputStream> SAVE_CALLBACK_TARGET =
      ThreadLocal.withInitial(
          () -> new ReusableByteArrayOutputStream(SAVE_CALLBACK_INITIAL_CAPACITY));

  /** Reused per-thread staging buffer for native save callbacks; bounded to 64 KiB. */
  private static final ThreadLocal<byte[]> SAVE_CALLBACK_BUF =
      ThreadLocal.withInitial(() -> new byte[8192]);

  private static final ThreadLocal<ReusableByteArrayOutputStream> STREAM_DECODE_TARGET =
      ThreadLocal.withInitial(
          () -> new ReusableByteArrayOutputStream(STREAM_DECODE_INITIAL_CAPACITY));

  private static final ThreadLocal<byte[]> STREAM_DECODE_BUF =
      ThreadLocal.withInitial(() -> new byte[8192]);

  private static final XmpMetadataWriter XMP_WRITER = new XmpMetadataWriter();
  private static final long INITIAL_TAIL_SCAN_BYTES = 64L << 10;
  private static final long SECONDARY_TAIL_SCAN_BYTES = 256L << 10;
  private static final long TAIL_SCAN_BYTES = 1024L << 10;
  private static final long[] TAIL_SCAN_STEPS =
      new long[] {
        INITIAL_TAIL_SCAN_BYTES, SECONDARY_TAIL_SCAN_BYTES, TAIL_SCAN_BYTES, Long.MAX_VALUE
      };
  private static final long XREF_OFFSET_FUZZ_BYTES = 1024L;
  private static final long MAX_XREF_OFFSET = 9_999_999_999L;

  /** Parameters for saving a PDF document. */
  record SaveParams(
      MemorySegment docHandle,
      Map<MetadataTag, String> allMetadata,
      boolean hasInfoUpdate,
      org.grimmory.pdfium4j.internal.XmpUpdate pendingXmp,
      SeekableByteChannel originalSource,
      Path sourcePath,
      byte[] originalBytes,
      boolean structurallyModified,
      OutputStream out,
      boolean allowIncrementalOutput) {}

  private record ObjectRef(int num, int gen) {
    @Override
    public String toString() {
      return num + " " + gen + " R";
    }
  }

  // Byte constants for zero-allocation dictionary key scanning (replacing regex patterns).
  private static final byte[] METADATA_KEY = "/Metadata".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] FILTER_KEY = "/Filter".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] LENGTH_KEY = "/Length".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] FIRST_KEY = "/First".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] N_KEY = "/N".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] PREDICTOR_KEY = "/Predictor".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] COLUMNS_KEY = "/Columns".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] W_KEY = "/W".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] INDEX_KEY = "/Index".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] FLATEDECODE_NAME = "FlateDecode".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] FL_NAME = "Fl".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] R_KEYWORD = "R".getBytes(StandardCharsets.ISO_8859_1);

  private static final byte[] DICT_START = "<<".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] TRAILER_KEYWORD = "trailer".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] STARTXREF_KEYWORD = "startxref".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_KEYWORD = "xref".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] STREAM_KEYWORD = "stream".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ENDSTREAM_KEYWORD = "endstream".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] TYPE_KEY = "/Type".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_TYPE_NAME = "/XRef".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] CATALOG_TYPE_NAME = "/Catalog".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] PAGES_TYPE_NAME = "/Pages".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ROOT_KEY = "/Root".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] INFO_KEY = "/Info".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] PREV_KEY = "/Prev".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] PAGES_KEY = "/Pages".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] SIZE_KEY = "/Size".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] OBJ_KEYWORD = "obj".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ENCRYPT_KEY = "/Encrypt".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] AUTHOR_KEY = "/Author".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] TITLE_KEY = "/Title".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] CREATION_DATE_KEY = "/CreationDate".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_HEADER = "xref\n".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_ENTRY_TEMPLATE =
      "0000000000 00000 n \n".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] REPAIR_XREF_SECTION =
      "xref\n0 1\n0000000000 65535 f \n".getBytes(StandardCharsets.ISO_8859_1);

  private record BasePdf(MemorySegment segment, @CheckForNull Path tempPath) {}

  private PdfSaver() {
    super();
  }

  static void save(SaveParams params) throws IOException {
    boolean hasXmpUpdate = hasXmpUpdate(params.pendingXmp());
    boolean hasUpdate = params.hasInfoUpdate() || hasXmpUpdate;

    if (hasUpdate && !params.allowIncrementalOutput()) {
      throw new IOException(
          "Incremental save to a generic OutputStream is disabled; use save(Path) or saveToBytes() instead");
    }

    try (Arena arena = Arena.ofConfined()) {
      if (hasUpdate) {
        try {
          writeIncrementalUpdate(params, arena);
        } catch (PdfiumException e) {
          if (!hasXmpUpdate && e.getCause() instanceof IOException) {
            writeSegment(MemorySegment.ofArray(nativeSaveBytes(params.docHandle())), params.out());
          } else {
            throw e;
          }
        }
      } else {
        BasePdf base = getBaseSegment(params, arena);
        try {
          writeSource(params, base.segment(), params.out());
        } finally {
          deleteIfExists(base.tempPath());
        }
      }
    }
  }

  static void repair(Path source, OutputStream out) throws IOException {
    try (Arena arena = Arena.ofConfined();
        FileChannel fc = FileChannel.open(source, StandardOpenOption.READ)) {
      MemorySegment pdf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
      repair(pdf, out);
    }
  }

  static byte[] repair(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 256);
    repair(MemorySegment.ofArray(data), out);
    return out.toByteArray();
  }

  private static void repair(MemorySegment pdf, OutputStream out) throws IOException {
    long currentXrefOffset = findLastStartxrefValue(pdf);
    
    TrailerInfo repairedTrailer;
    try {
        repairedTrailer = recoverTrailerInfo(pdf, currentXrefOffset, null);
    } catch (IOException e) {
        repairedTrailer = bruteForceRecoverTrailerInfo(pdf);
    }


    writeSegment(pdf, out);
    out.write('\n');
    long xrefOffset = pdf.byteSize() + 1;
    writeRepairXrefTable(out);
    writeTrailer(out, repairedTrailer, 0, repairedTrailer.size(), Math.max(0, currentXrefOffset), xrefOffset);
  }

  private static TrailerInfo bruteForceRecoverTrailerInfo(MemorySegment pdf) throws IOException {
    int maxObj;
    ObjectRef bestCatalog = null;
    ObjectRef bestInfo = null;

    long searchPos = pdf.byteSize();
    
    // First pass: find max object number (needed for trailer Size)
    maxObj = findMaxObjectNumber(pdf);

    // Second pass: find latest Catalog and Info by scanning backwards
    while (searchPos > 0) {
      long pos = lastIndexOf(pdf, OBJ_KEYWORD, searchPos);
      if (pos < 0) break;
      searchPos = pos - 1;

      ObjectRef ref = parseObjectHeaderRef(pdf, pos);
      if (ref == null) continue;

      if (bestCatalog == null && isCatalogAt(pdf, ref, pos)) {
          bestCatalog = ref;
      } else if (bestInfo == null && isInfoAt(pdf, ref, pos)) {
          bestInfo = ref;
      }
      
      if (bestCatalog != null && bestInfo != null) break;
    }

    if (bestCatalog == null) {
      throw new IOException("Failed to locate Catalog root via brute-force scan");
    }

    return new TrailerInfo(bestCatalog, bestInfo, maxObj + 1);
  }

  private static boolean isCatalogAt(MemorySegment pdf, ObjectRef ref, long headerPos) {
      try {
          DictionaryRange range = findObjectDictionaryRangeFromHeader(pdf, headerPos);
          if (range == null) return false;
          byte[] dict = pdf.asSlice(range.start(), range.endExclusive() - range.start()).toArray(JAVA_BYTE);
          if (!isCatalogDictionary(dict)) return false;
          
          // Double check: does it have Pages?
          MemorySegment root = MemorySegment.ofArray(dict);
          ObjectRef pagesRef = findTopLevelObjectRef(root, root.byteSize());
          return pagesRef != null;
      } catch (Exception _) {
          return false;
      }
  }

  private static boolean isInfoAt(MemorySegment pdf, ObjectRef ref, long headerPos) {
      try {
          DictionaryRange range = findObjectDictionaryRangeFromHeader(pdf, headerPos);
          if (range == null) return false;
          byte[] dict = pdf.asSlice(range.start(), range.endExclusive() - range.start()).toArray(JAVA_BYTE);
          // Info dictionaries usually have specific keys like /Title, /Author, /CreationDate
          // but they ARE optional. However, they shouldn't be Catalogs or Pages.
          if (isCatalogDictionary(dict) || isPagesDictionary(dict)) return false;
          
          // Look for any common info key
          MemorySegment info = MemorySegment.ofArray(dict);
          return findTopLevelKey(info, 0, info.byteSize(), AUTHOR_KEY) >= 0 ||
                 findTopLevelKey(info, 0, info.byteSize(), TITLE_KEY) >= 0 ||
                 findTopLevelKey(info, 0, info.byteSize(), CREATION_DATE_KEY) >= 0;
      } catch (Exception _) {
          return false;
      }
  }

  private static DictionaryRange findObjectDictionaryRangeFromHeader(MemorySegment pdf, long headerPos) {
      long dictStart = indexOf(pdf, DICT_START, headerPos);
      if (dictStart < 0) return null;
      long dictEnd = findDictionaryEnd(pdf, dictStart);
      if (dictEnd <= dictStart) return null;
      return new DictionaryRange(dictStart, dictEnd);
  }

  private static void writeSource(SaveParams params, MemorySegment baseSegment, OutputStream out)
      throws IOException {
    WritableByteChannel target = Channels.newChannel(out);
    if (!params.structurallyModified() && params.originalSource() instanceof FileChannel fc) {
      transferAll(fc, target);
    } else if (!params.structurallyModified() && params.sourcePath() != null) {
      try (FileChannel fc = FileChannel.open(params.sourcePath(), StandardOpenOption.READ)) {
        transferAll(fc, target);
      }
    } else {
      writeSegment(baseSegment, out);
    }
  }

  private static BasePdf getBaseSegment(SaveParams params, Arena arena) throws IOException {
    if (params.structurallyModified()) {
      return new BasePdf(MemorySegment.ofArray(nativeSaveBytes(params.docHandle())), null);
    } else if (params.originalBytes() != null) {
      return new BasePdf(MemorySegment.ofArray(params.originalBytes()), null);
    } else if (params.sourcePath() != null) {
      try (FileChannel fc = FileChannel.open(params.sourcePath(), StandardOpenOption.READ)) {
        return new BasePdf(fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena), null);
      }
    }

    SeekableByteChannel source = params.originalSource();
    if (source instanceof FileChannel fc) {
      return new BasePdf(fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena), null);
    }
    if (source != null) {
      Path temp = IoUtils.createTempFile("pdfium4j-base-", ".pdf");
      try {
        source.position(0);
        try (FileChannel fc =
            FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ)) {
          copyAll(source, fc);
          return new BasePdf(fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena), temp);
        }
      } catch (IOException e) {
        deleteIfExists(temp);
        throw e;
      }
    }
    return new BasePdf(MemorySegment.ofArray(nativeSaveBytes(params.docHandle())), null);
  }

  private static byte[] nativeSaveBytes(MemorySegment docHandle) {
    ReusableByteArrayOutputStream baos = SAVE_CALLBACK_TARGET.get();
    baos.resetForReuse(SAVE_CALLBACK_MAX_RETAINED_CAPACITY);
    // Shared arena is required for native upcall stubs to ensure they remain valid during the
    // asynchronous-like save callback flow.
    try (Arena arena = Arena.ofShared()) {
      if (EditBindings.FPDF_SaveAsCopy == null) {
        throw new PdfiumException("FPDF_SaveAsCopy not available in this PDFium build");
      }
      MethodHandle writeBlockMH =
          MethodHandles.lookup()
              .findStatic(
                  PdfSaver.class,
                  "writeBlockCallback",
                  MethodType.methodType(
                      int.class, MemorySegment.class, MemorySegment.class, long.class));
      MemorySegment writeBlockStub =
          Linker.nativeLinker().upcallStub(writeBlockMH, EditBindings.WRITE_BLOCK_DESC, arena);
      MemorySegment fileWrite = arena.allocate(EditBindings.FPDF_FILEWRITE_LAYOUT);
      fileWrite.set(JAVA_INT, 0, 1);
      fileWrite.set(ADDRESS, 8, writeBlockStub);

      int ok = (int) EditBindings.FPDF_SaveAsCopy.invokeExact(docHandle, fileWrite, 0);
      if (ok == 0) throw new PdfiumException("FPDF_SaveAsCopy failed");
      return baos.toByteArray();
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to save document", t);
    } finally {
      baos.resetForReuse(SAVE_CALLBACK_MAX_RETAINED_CAPACITY);
    }
  }

  @SuppressWarnings({"PMD.UnusedFormalParameter", "unused"})
  private static int writeBlockCallback(MemorySegment pThis, MemorySegment pData, long size) {
    if (FfmHelper.isNull(pThis) || FfmHelper.isNull(pData)) return 0;
    ReusableByteArrayOutputStream baos = SAVE_CALLBACK_TARGET.get();
    if (baos == null) return 0;
    if (size <= 0) return 0;
    // Reinterpret pData to expose its full size before copying.
    MemorySegment data = pData.reinterpret(size);
    byte[] buf = SAVE_CALLBACK_BUF.get();
    long offset = 0;
    long remaining = size;
    while (remaining > 0) {
      int chunk = (int) Math.min(buf.length, remaining);
      MemorySegment.copy(data, JAVA_BYTE, offset, buf, 0, chunk);
      baos.write(buf, 0, chunk);
      offset += chunk;
      remaining -= chunk;
    }
    return 1;
  }

  private record TrailerInfo(ObjectRef rootRef, ObjectRef infoRef, int size) {}

  private record ParsedTail(TrailerInfo trailer, long prevXrefOffset) {}

  private record TrailerFields(
      @CheckForNull ObjectRef rootRef,
      @CheckForNull ObjectRef infoRef,
      int size,
      boolean hasSizeEntry,
      boolean hasEncrypt) {}

  private record DictionaryRange(long start, long endExclusive) {}

  private record XrefEntry(int type, long field2, int field3) {}

  private record TrailerSection(TrailerFields fields, long xrefOffset, long prevOffset) {}

  private record XrefStreamSection(
      TrailerFields trailerFields,
      int[] widths,
      int[] indexPairs,
      long prevOffset,
      byte[] decodedEntries) {}

  private static final class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
    private final int initialCapacity;

    ReusableByteArrayOutputStream(int initialCapacity) {
      super(initialCapacity);
      this.initialCapacity = initialCapacity;
    }

    void resetForReuse(int maxRetainedCapacity) {
      if (buf.length > maxRetainedCapacity) {
        buf = new byte[initialCapacity];
      }
      reset();
    }
  }

  private static void writeIncrementalUpdate(SaveParams params, Arena arena) throws IOException {
    BasePdf base = getBaseSegment(params, arena);
    MemorySegment pdf = base.segment();
    try {
      ParsedTail parsedTail = parseTail(pdf);
      performIncrementalUpdate(pdf, params, parsedTail.trailer(), parsedTail.prevXrefOffset());
    } catch (IOException e) {
      throw new PdfiumException("Incremental update failed", e);
    } finally {
      deleteIfExists(base.tempPath());
    }
  }

  private static void performIncrementalUpdate(
      MemorySegment pdf, SaveParams params, TrailerInfo trailer, long prevXrefOffset)
      throws IOException {
    int nextObj = determineNextObjectNumber(pdf, trailer.size());
    long baseOffset = pdf.byteSize();

    // Pre-build all update objects so we can determine exact byte offsets before writing anything
    // to the output stream. This avoids a heap-buffered ByteArrayOutputStream for the full delta.
    Map<Integer, Long> objOffsets = LinkedHashMap.newLinkedHashMap(8);
    List<byte[]> objectBufs = new ArrayList<>(4);
    long updateSize = 1; // leading newline

    int infoObjNum = 0;
    Map<MetadataTag, String> metadata = params.hasInfoUpdate() ? params.allMetadata() : null;
    if (metadata != null && !metadata.isEmpty()) {
      infoObjNum = nextObj++;
      objOffsets.put(infoObjNum, baseOffset + updateSize);
      byte[] infoBytes = buildInfoObject(infoObjNum, metadata);
      objectBufs.add(infoBytes);
      updateSize += infoBytes.length;
    }

    org.grimmory.pdfium4j.internal.XmpUpdate xmp = params.pendingXmp();
    if (hasXmpUpdate(xmp)) {
      int xmpObjNum = nextObj++;
      objOffsets.put(xmpObjNum, baseOffset + updateSize);
      byte[] xmpBytes = buildXmpObjectBytes(xmpObjNum, xmp);
      objectBufs.add(xmpBytes);
      updateSize += xmpBytes.length;

      ObjectRef catalogRef = trailer.rootRef();
      byte[] catalogDictBytes = resolveObjectDictionaryBytes(pdf, catalogRef, prevXrefOffset);
      if (catalogDictBytes == null) {
        throw new IOException("Failed to find Catalog object for XMP update");
      }
      objOffsets.put(catalogRef.num, baseOffset + updateSize);
      byte[] catalogBytes = buildModifiedCatalogBytes(catalogRef, catalogDictBytes, xmpObjNum);
      objectBufs.add(catalogBytes);
      updateSize += catalogBytes.length;
    }

    long xrefOffset = baseOffset + updateSize;

    // Write: base PDF → newline → pre-built objects → xref table → trailer.
    OutputStream out = params.out();
    writeSource(params, pdf, out);
    out.write('\n');
    for (byte[] buf : objectBufs) {
      out.write(buf);
    }
    writeXrefTable(out, objOffsets);
    writeTrailer(out, trailer, infoObjNum, nextObj, prevXrefOffset, xrefOffset);
  }

  private static void writeXrefTable(OutputStream update, Map<Integer, Long> objOffsets)
      throws IOException {
    update.write(XREF_HEADER);
    List<Integer> sortedNums = new ArrayList<>(objOffsets.keySet());
    Collections.sort(sortedNums);

    byte[] intBuf = new byte[11];
    byte[] entryBuf = new byte[20];
    int totalObjs = sortedNums.size();
    for (int i = 0; i < totalObjs; ) {
      int start = sortedNums.get(i);
      int count = 1;
      while (i + count < totalObjs && sortedNums.get(i + count) == start + count) {
        count++;
      }
      int len = formatInt(intBuf, start);
      update.write(intBuf, intBuf.length - len, len);
      update.write(' ');
      len = formatInt(intBuf, count);
      update.write(intBuf, intBuf.length - len, len);
      update.write('\n');

      for (int j = 0; j < count; j++) {
        long offset = objOffsets.get(start + j);
        if (offset > MAX_XREF_OFFSET) {
          throw new IOException(
              "Xref offset exceeds 10-digit table limit: " + offset + " for object " + (start + j));
        }
        // Manual fixed-width formatting: 10 digits zero-padded "0000000000 00000 n \n"
        // Total 20 bytes.
        System.arraycopy(XREF_ENTRY_TEMPLATE, 0, entryBuf, 0, 20);
        long tempOffset = offset;
        for (int k = 9; k >= 0; k--) {
          entryBuf[k] = (byte) ('0' + (tempOffset % 10));
          tempOffset /= 10;
        }
        update.write(entryBuf);
      }
      i += count;
    }
  }

  private static int formatInt(byte[] buf, int value) {
    int pos = buf.length;
    if (value == 0) {
      buf[--pos] = '0';
      return 1;
    }
    int temp = value;
    while (temp > 0) {
      buf[--pos] = (byte) ('0' + (temp % 10));
      temp /= 10;
    }
    return buf.length - pos;
  }

  private static void transferAll(FileChannel src, WritableByteChannel target) throws IOException {
    long size = src.size();
    long pos = 0;
    while (pos < size) {
      long moved = src.transferTo(pos, size - pos, target);
      if (moved <= 0) {
        break;
      }
      pos += moved;
    }
    if (pos < size) {
      src.position(pos);
      copyAll(src, target);
    }
  }

  private static void copyAll(SeekableByteChannel src, WritableByteChannel dst) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocateDirect(65536);
    try {
      while (src.read(buffer) != -1) {
        buffer.flip();
        while (buffer.hasRemaining()) {
          dst.write(buffer);
        }
        buffer.clear();
      }
    } finally {
      buffer.clear();
    }
  }

  private static void deleteIfExists(@CheckForNull Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      PdfiumLibrary.ignore(e);
    }
  }

  private static void writeTrailer(
      OutputStream update,
      TrailerInfo trailer,
      int infoObjNum,
      int nextObj,
      long prevXrefOffset,
      long xrefOffset)
      throws IOException {
    StringBuilder trailerSb = new StringBuilder(256);
    trailerSb.append("trailer\n<< /Size ").append(nextObj);
    trailerSb.append(" /Root ").append(trailer.rootRef());
    if (infoObjNum > 0) {
      trailerSb.append(" /Info ").append(infoObjNum).append(" 0 R");
    } else if (trailer.infoRef() != null) {
      trailerSb.append(" /Info ").append(trailer.infoRef());
    }
    if (prevXrefOffset > 0) {
      trailerSb.append(" /Prev ").append(prevXrefOffset);
    }
    trailerSb.append(" >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
    update.write(trailerSb.toString().getBytes(StandardCharsets.ISO_8859_1));
  }

  private static void writeRepairXrefTable(OutputStream update) throws IOException {
    update.write(REPAIR_XREF_SECTION);
  }

  private static byte[] buildInfoObject(int num, Map<MetadataTag, String> metadata) {
    StringBuilder sb = new StringBuilder((metadata.size() << 6) + 64);
    sb.append(num).append(" 0 obj\n<<\n");
    for (Map.Entry<MetadataTag, String> entry : metadata.entrySet()) {
      if (entry.getValue() != null && !entry.getValue().isBlank()) {
        sb.append('/').append(entry.getKey().pdfKey()).append(' ');
        sb.append(encodePdfString(entry.getValue())).append('\n');
      }
    }
    String explicitModDate = metadata.get(MetadataTag.MOD_DATE);
    boolean hasExplicitModDate =
        explicitModDate != null
            && !explicitModDate.isBlank()
            && isLikelyPdfDate(explicitModDate.trim());
    if (!hasExplicitModDate) {
      sb.append("/ModDate ").append(encodePdfString(formatPdfDate())).append('\n');
    }
    sb.append(">>\nendobj\n");
    return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /**
   * Pre-renders the full XMP stream object to a byte array so the caller knows its size before
   * writing the enclosing xref table entry.
   */
  private static byte[] buildXmpObjectBytes(int num, org.grimmory.pdfium4j.internal.XmpUpdate xmp)
      throws IOException {
    byte[] content =
        switch (xmp) {
          case org.grimmory.pdfium4j.internal.XmpUpdate.Raw raw ->
              raw.xmp().getBytes(StandardCharsets.UTF_8);
          case org.grimmory.pdfium4j.internal.XmpUpdate.Structured structured -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            XMP_WRITER.write(structured.metadata(), baos);
            yield baos.toByteArray();
          }
        };
    byte[] header =
        (num
                + " 0 obj\n<< /Type /Metadata /Subtype /XML /Length "
                + content.length
                + " >>\nstream\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    byte[] suffix = "\nendstream\nendobj\n".getBytes(StandardCharsets.ISO_8859_1);
    byte[] result = new byte[header.length + content.length + suffix.length];
    System.arraycopy(header, 0, result, 0, header.length);
    System.arraycopy(content, 0, result, header.length, content.length);
    System.arraycopy(suffix, 0, result, header.length + content.length, suffix.length);
    return result;
  }

  private static byte[] buildModifiedCatalogBytes(
      ObjectRef catalogRef, byte[] dictBytes, int xmpObjNum) {
    String oldDict = new String(dictBytes, StandardCharsets.ISO_8859_1);
    // Remove existing /Metadata reference using byte-level scan instead of regex.
    String dict = removeMetadataRef(oldDict);
    StringBuilder sb = new StringBuilder(dict.length() + 128);
    sb.append(catalogRef.num()).append(' ').append(catalogRef.gen()).append(" obj\n");
    int closeIdx = dict.lastIndexOf(">>");
    if (closeIdx >= 0) {
      sb.append(dict, 0, closeIdx)
          .append("/Metadata ")
          .append(xmpObjNum)
          .append(" 0 R ")
          .append(dict, closeIdx, dict.length());
    } else {
      sb.append(dict);
    }
    sb.append("\nendobj\n");
    return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /**
   * Removes the first occurrence of "/Metadata N N R" from a dictionary string.
   * Replaces the regex-based METADATA_REF_PATTERN with a zero-allocation scan.
   */
  private static String removeMetadataRef(String dict) {
    int idx = dict.indexOf("/Metadata");
    if (idx < 0) return dict;
    int end = idx + "/Metadata".length();
    int len = dict.length();
    // skip whitespace
    while (end < len && isWs(dict.charAt(end))) end++;
    // skip digits (obj num)
    while (end < len && dict.charAt(end) >= '0' && dict.charAt(end) <= '9') end++;
    // skip whitespace
    while (end < len && isWs(dict.charAt(end))) end++;
    // skip digits (gen num)
    while (end < len && dict.charAt(end) >= '0' && dict.charAt(end) <= '9') end++;
    // skip whitespace
    while (end < len && isWs(dict.charAt(end))) end++;
    // expect 'R'
    if (end < len && dict.charAt(end) == 'R') {
      end++;
      return dict.substring(0, idx) + dict.substring(end);
    }
    return dict;
  }

  private static boolean isWs(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0;
  }

  private static ParsedTail parseTail(MemorySegment pdf) throws IOException {
    for (long step : TAIL_SCAN_STEPS) {
      ParsedTail parsed = tryParseTail(pdf, Math.min(pdf.byteSize(), step));
      if (parsed != null) {
        return parsed;
      }
    }
    throw new IOException("Failed to parse PDF trailer from tail window");
  }

  @CheckForNull
  private static ParsedTail tryParseTail(MemorySegment pdf, long requestedTailBytes) {
    long tailLen = Math.min(pdf.byteSize(), requestedTailBytes);
    if (tailLen <= 0) {
      return null;
    }
    MemorySegment tail = pdf.asSlice(pdf.byteSize() - tailLen, tailLen);
    try {
      long prevXrefOffset = findLastStartxrefValue(tail);
      TrailerInfo trailer = parseTrailer(tail, pdf, prevXrefOffset);
      return new ParsedTail(trailer, prevXrefOffset);
    } catch (IOException _) {
      return null;
    }
  }

  private static TrailerInfo parseTrailer(
      MemorySegment tail, MemorySegment pdf, long prevXrefOffset) throws IOException {
    ObjectRef rootRef = null;
    ObjectRef infoRef = null;
    int size = 0;
    boolean hasSizeEntry = false;
    boolean hasEncrypt = false;

    long searchFrom = tail.byteSize();
    while ((rootRef == null || infoRef == null || size == 0) && searchFrom > 0) {
      long trailerIdx = lastIndexOf(tail, TRAILER_KEYWORD, searchFrom);
      if (trailerIdx < 0) {
        break;
      }

      long dictStart = indexOf(tail, DICT_START, trailerIdx + TRAILER_KEYWORD.length);
      if (dictStart >= 0) {
        long dictEnd = findDictionaryEnd(tail, dictStart);
        if (dictEnd > dictStart) {
          TrailerFields fields = parseTrailerDictionary(tail, dictStart, dictEnd);
          if (rootRef == null && fields.rootRef() != null) {
            rootRef = fields.rootRef();
          }
          if (infoRef == null && fields.infoRef() != null) {
            infoRef = fields.infoRef();
          }
          if (size == 0 && fields.size() > 0) {
            size = fields.size();
          }
          if (!hasSizeEntry && fields.hasSizeEntry()) {
            hasSizeEntry = true;
          }
          if (!hasEncrypt && fields.hasEncrypt()) {
            hasEncrypt = true;
          }
        }
      }

      searchFrom = trailerIdx - 1;
    }

    if ((rootRef == null || size == 0) && prevXrefOffset > 0) {
      TrailerFields fields = parseXrefStreamFields(pdf, prevXrefOffset);
      if (rootRef == null && fields.rootRef() != null) {
        rootRef = fields.rootRef();
      }
      if (infoRef == null && fields.infoRef() != null) {
        infoRef = fields.infoRef();
      }
      if (size == 0 && fields.size() > 0) {
        size = fields.size();
      }
      if (!hasSizeEntry && fields.hasSizeEntry()) {
        hasSizeEntry = true;
      }
      if (!hasEncrypt && fields.hasEncrypt()) {
        hasEncrypt = true;
      }
    }

    if (hasEncrypt) {
      // Throw unchecked so this propagates through tryParseTail's catch(IOException) and
      // surfaces directly to the caller rather than triggering the "try bigger window" fallback.
      throw new PdfiumException(
          "Incremental metadata save is not supported on encrypted documents;"
              + " use save(Path) without pending metadata changes on an encrypted PDF");
    }

    if (size == 0 && hasSizeEntry) {
      throw new IOException("Trailer /Size is zero or invalid");
    }

    if (size == 0) {
      size = findMaxObjectNumber(pdf) + 1;
    }
    if (size <= 0) {
      throw new IOException("Failed to determine next PDF object number");
    }
    if (rootRef == null) {
      throw new IOException("Failed to find PDF Root (Catalog) reference");
    }

    byte[] rootDict = null;
    try {
      rootDict = resolveObjectDictionaryBytes(pdf, rootRef, prevXrefOffset);
    } catch (IOException _) {
      // Some modern files store the catalog in compressed object streams. If resolution fails here,
      // defer the hard failure until an XMP update actually needs the catalog bytes.
    }
    if (rootDict != null && !isCatalogDictionary(rootDict)) {
      throw new IOException("Trailer Root does not reference a Catalog object");
    }

    return new TrailerInfo(rootRef, infoRef, size);
  }

  private static TrailerInfo recoverTrailerInfo(
      MemorySegment pdf, long currentXrefOffset, @CheckForNull TrailerInfo fallback)
      throws IOException {
    ObjectRef recoveredInfo = fallback != null ? fallback.infoRef() : null;
    int recoveredSize = fallback != null ? fallback.size() : 0;

    if (fallback != null && isValidCatalogRoot(pdf, fallback.rootRef(), currentXrefOffset)) {
      if (!isUsableInfoRef(pdf, recoveredInfo, currentXrefOffset)) {
        recoveredInfo = null;
      }
      if (recoveredSize <= 0) {
        recoveredSize = findMaxObjectNumber(pdf) + 1;
      }
      if (recoveredSize > 0) {
        return new TrailerInfo(fallback.rootRef(), recoveredInfo, recoveredSize);
      }
    }

    if (currentXrefOffset > 0) {
      Set<Long> visited = HashSet.newHashSet(16);
      long xrefOffset = currentXrefOffset;
      while (xrefOffset > 0 && visited.add(xrefOffset)) {
        TrailerSection section = parseTrailerSectionAtXref(pdf, xrefOffset);
        TrailerFields fields = section.fields();
        if (recoveredSize <= 0 && fields.size() > 0) {
          recoveredSize = fields.size();
        }
        if (recoveredInfo == null && fields.infoRef() != null) {
          recoveredInfo = fields.infoRef();
        }

        ObjectRef candidateRoot = fields.rootRef();
        if (candidateRoot != null && isValidCatalogRoot(pdf, candidateRoot, xrefOffset)) {
          if (!isUsableInfoRef(pdf, recoveredInfo, currentXrefOffset)) {
            recoveredInfo = null;
          }
          if (recoveredSize <= 0) {
            recoveredSize = findMaxObjectNumber(pdf) + 1;
          }
          if (recoveredSize <= 0) {
            throw new IOException("Failed to determine next PDF object number");
          }
          return new TrailerInfo(candidateRoot, recoveredInfo, recoveredSize);
        }

        xrefOffset = section.prevOffset();
      }
    }

    throw new IOException("Failed to recover a valid Catalog root from trailer chain");
  }

  private static TrailerFields parseTrailerDictionary(
      MemorySegment tail, long dictStart, long dictEndExclusive) {
    long rootPos = findTopLevelKey(tail, dictStart, dictEndExclusive, ROOT_KEY);
    ObjectRef rootRef =
        rootPos >= 0 ? parseObjectRef(tail, rootPos + ROOT_KEY.length, dictEndExclusive) : null;

    long infoPos = findTopLevelKey(tail, dictStart, dictEndExclusive, INFO_KEY);
    ObjectRef infoRef =
        infoPos >= 0 ? parseObjectRef(tail, infoPos + INFO_KEY.length, dictEndExclusive) : null;

    long sizePos = findTopLevelKey(tail, dictStart, dictEndExclusive, SIZE_KEY);
    int size =
        sizePos >= 0 ? parseTrailerSize(tail, sizePos + SIZE_KEY.length, dictEndExclusive) : 0;

    boolean hasEncrypt = findTopLevelKey(tail, dictStart, dictEndExclusive, ENCRYPT_KEY) >= 0;
    return new TrailerFields(rootRef, infoRef, size, sizePos >= 0, hasEncrypt);
  }

  private static TrailerFields parseXrefStreamFields(MemorySegment pdf, long xrefOffset)
      throws IOException {
    return parseXrefStreamSection(pdf, xrefOffset).trailerFields();
  }

  private static TrailerSection parseTrailerSectionAtXref(MemorySegment pdf, long xrefOffset)
      throws IOException {
    try {
      XrefStreamSection section = parseXrefStreamSection(pdf, xrefOffset);
      return new TrailerSection(section.trailerFields(), xrefOffset, section.prevOffset());
    } catch (IOException _) {
      TrailerFields fields = parseClassicXrefTrailerFields(pdf, xrefOffset);
      return new TrailerSection(fields, xrefOffset, parseClassicXrefPrevOffset(pdf, xrefOffset));
    }
  }

  private static TrailerFields parseClassicXrefTrailerFields(MemorySegment pdf, long xrefOffset)
      throws IOException {
    long resolvedOffset = locateClassicXrefOffset(pdf, xrefOffset);
    if (resolvedOffset < 0) {
      throw new IOException("startxref does not reference a classic xref table");
    }
    long limit = pdf.byteSize();
    long start = skipAsciiWhitespace(pdf, resolvedOffset, limit);
    long trailerIdx = indexOf(pdf, TRAILER_KEYWORD, start + XREF_KEYWORD.length);
    if (trailerIdx < 0) {
      throw new IOException("Classic xref table is missing trailer dictionary");
    }
    long dictStart = indexOf(pdf, DICT_START, trailerIdx + TRAILER_KEYWORD.length);
    if (dictStart < 0) {
      throw new IOException("Failed to locate classic xref trailer dictionary");
    }
    long dictEnd = findDictionaryEnd(pdf, dictStart);
    if (dictEnd <= dictStart) {
      throw new IOException("Classic xref trailer dictionary is malformed");
    }
    return parseTrailerDictionary(pdf, dictStart, dictEnd);
  }

  private static boolean isXrefStreamDictionary(
      MemorySegment seg, long dictStart, long dictEndExclusive) {
    long typePos = findTopLevelKey(seg, dictStart, dictEndExclusive, TYPE_KEY);
    if (typePos < 0) return false;
    long typeValPos = skipAsciiWhitespace(seg, typePos + TYPE_KEY.length, dictEndExclusive);
    return matchesNameTokenAt(seg, typeValPos, XREF_TYPE_NAME, dictEndExclusive);
  }

  private static boolean isCatalogDictionary(byte[] dictBytes) {
    if (dictBytes.length == 0) {
      return false;
    }
    MemorySegment dict = MemorySegment.ofArray(dictBytes);
    return dictionaryHasTopLevelNameValue(dict, 0, dict.byteSize(), TYPE_KEY, CATALOG_TYPE_NAME);
  }

  private static boolean isPagesDictionary(byte[] dictBytes) {
    if (dictBytes.length == 0) {
      return false;
    }
    MemorySegment dict = MemorySegment.ofArray(dictBytes);
    return dictionaryHasTopLevelNameValue(dict, 0, dict.byteSize(), TYPE_KEY, PAGES_TYPE_NAME);
  }

  private static boolean isValidCatalogRoot(MemorySegment pdf, ObjectRef rootRef, long xrefOffset)
      throws IOException {
    byte[] rootDict = resolveObjectDictionaryBytes(pdf, rootRef, xrefOffset);
    if (rootDict == null || !isCatalogDictionary(rootDict)) {
      return false;
    }

    MemorySegment root = MemorySegment.ofArray(rootDict);
    ObjectRef pagesRef = findTopLevelObjectRef(root, root.byteSize());
    if (pagesRef == null) {
      return false;
    }

    byte[] pagesDict = resolveObjectDictionaryBytes(pdf, pagesRef, xrefOffset);
    return pagesDict != null && isPagesDictionary(pagesDict);
  }

  private static boolean isUsableInfoRef(
      MemorySegment pdf, @CheckForNull ObjectRef infoRef, long xrefOffset) {
    if (infoRef == null) {
      return true;
    }
    try {
      byte[] infoDict = resolveObjectDictionaryBytes(pdf, infoRef, xrefOffset);
      return infoDict != null && !isCatalogDictionary(infoDict) && !isPagesDictionary(infoDict);
    } catch (IOException _) {
      return false;
    }
  }

  @CheckForNull
  private static byte[] resolveObjectDictionaryBytes(
      MemorySegment pdf, ObjectRef ref, long xrefOffset) throws IOException {
    DictionaryRange directRange = findObjectDictionaryRange(pdf, ref.num, ref.gen);
    if (directRange != null) {
      long len = directRange.endExclusive() - directRange.start();
      return pdf.asSlice(directRange.start(), len).toArray(JAVA_BYTE);
    }
    if (xrefOffset <= 0) {
      return null;
    }
    return resolveObjectDictionaryBytesFromXref(pdf, ref, xrefOffset, HashSet.newHashSet(16));
  }

  @CheckForNull
  private static byte[] resolveObjectDictionaryBytesFromXref(
      MemorySegment pdf, ObjectRef ref, long xrefOffset, Set<Long> visitedXrefs)
      throws IOException {
    if (xrefOffset <= 0 || !visitedXrefs.add(xrefOffset)) {
      return null;
    }

    XrefStreamSection section;
    try {
      section = parseXrefStreamSection(pdf, xrefOffset);
    } catch (IOException _) {
      long prevClassicOffset = parseClassicXrefPrevOffset(pdf, xrefOffset);
      return prevClassicOffset > 0
          ? resolveObjectDictionaryBytesFromXref(pdf, ref, prevClassicOffset, visitedXrefs)
          : null;
    }

    XrefEntry entry = findXrefEntry(section, ref.num);
    if (entry != null) {
      if (entry.type() == 1) {
        DictionaryRange directRange = findObjectDictionaryRange(pdf, ref.num, ref.gen);
        if (directRange != null) {
          long len = directRange.endExclusive() - directRange.start();
          return pdf.asSlice(directRange.start(), len).toArray(JAVA_BYTE);
        }
      } else if (entry.type() == 2) {
        byte[] dict =
            extractDictionaryFromObjectStream(pdf, ref.num, entry.field2(), entry.field3());
        if (dict != null) {
          return dict;
        }
      }
    }

    return section.prevOffset() > 0
        ? resolveObjectDictionaryBytesFromXref(pdf, ref, section.prevOffset(), visitedXrefs)
        : null;
  }

  private static long parseClassicXrefPrevOffset(MemorySegment pdf, long xrefOffset) {
    long resolvedOffset = locateClassicXrefOffset(pdf, xrefOffset);
    if (resolvedOffset < 0) {
      return 0;
    }
    long limit = pdf.byteSize();
    long start = skipAsciiWhitespace(pdf, resolvedOffset, limit);
    if (!matchesBytesAt(pdf, start, XREF_KEYWORD)) {
      return 0;
    }
    long trailerIdx = indexOf(pdf, TRAILER_KEYWORD, start + XREF_KEYWORD.length);
    if (trailerIdx < 0) {
      return 0;
    }
    long dictStart = indexOf(pdf, DICT_START, trailerIdx + TRAILER_KEYWORD.length);
    if (dictStart < 0) {
      return 0;
    }
    long dictEnd = findDictionaryEnd(pdf, dictStart);
    if (dictEnd <= dictStart) {
      return 0;
    }
    long prevPos = findTopLevelKey(pdf, dictStart, dictEnd, PREV_KEY);
    if (prevPos >= 0) {
      long numStart = skipAsciiWhitespace(pdf, prevPos + PREV_KEY.length, dictEnd);
      long numEnd = scanDigits(pdf, numStart, dictEnd);
      return numEnd > numStart ? parsePositiveLong(pdf, numStart, numEnd) : 0;
    }
    return 0;
  }

  private static long locateClassicXrefOffset(MemorySegment pdf, long hintedOffset) {
    long limit = pdf.byteSize();
    long exact = skipAsciiWhitespace(pdf, hintedOffset, limit);
    if (matchesBytesAt(pdf, exact, XREF_KEYWORD) && isAtLineBoundary(pdf, exact)) {
      return exact;
    }

    long windowStart = Math.max(0, hintedOffset - XREF_OFFSET_FUZZ_BYTES);
    long windowEnd = Math.min(limit, hintedOffset + XREF_OFFSET_FUZZ_BYTES);
    long best = -1;
    long bestDistance = Long.MAX_VALUE;
    long pos = indexOf(pdf, XREF_KEYWORD, windowStart);
    while (pos >= 0 && pos < windowEnd) {
      if (isAtLineBoundary(pdf, pos)) {
        long distance = Math.abs(pos - hintedOffset);
        if (distance < bestDistance) {
          best = pos;
          bestDistance = distance;
        }
      }
      pos = indexOf(pdf, XREF_KEYWORD, pos + 1);
    }
    return best;
  }

  private static XrefStreamSection parseXrefStreamSection(MemorySegment pdf, long xrefOffset)
      throws IOException {
    long limit = pdf.byteSize();
    long objStart = skipAsciiWhitespace(pdf, xrefOffset, limit);
    long numStart = objStart;
    long numEnd = scanDigits(pdf, numStart, limit);
    long genStart = skipAsciiWhitespace(pdf, numEnd, limit);
    long genEnd = scanDigits(pdf, genStart, limit);
    long objKeywordPos = skipAsciiWhitespace(pdf, genEnd, limit);
    if (numEnd <= numStart || genEnd <= genStart) {
      throw new IOException("startxref does not point to an indirect object");
    }
    if (!matchesNameTokenAt(pdf, objKeywordPos, OBJ_KEYWORD, limit)) {
      throw new IOException("startxref indirect object is missing obj keyword");
    }

    long dictStart = indexOf(pdf, DICT_START, objKeywordPos + OBJ_KEYWORD.length);
    if (dictStart < 0) {
      throw new IOException("Failed to locate xref stream dictionary");
    }
    long dictEnd = findDictionaryEnd(pdf, dictStart);
    if (dictEnd <= dictStart || !isXrefStreamDictionary(pdf, dictStart, dictEnd)) {
      throw new IOException("startxref does not reference an XRef stream dictionary");
    }

    TrailerFields trailerFields = parseTrailerDictionary(pdf, dictStart, dictEnd);
    if (trailerFields.size() <= 0) {
      throw new IOException("XRef stream dictionary is missing a valid /Size");
    }

    int[] widths = scanWArray(pdf, dictStart, dictEnd);
    int[] indexPairs = scanIndexPairs(pdf, dictStart, dictEnd, trailerFields.size());
    long prevOffset = scanIntAfterKey(pdf, dictStart, dictEnd, PREV_KEY);
    byte[] decodedEntries = decodeDirectStreamObject(pdf, dictStart, dictEnd);
    return new XrefStreamSection(trailerFields, widths, indexPairs, prevOffset, decodedEntries);
  }

  @CheckForNull
  private static XrefEntry findXrefEntry(XrefStreamSection section, int objNum) throws IOException {
    int[] widths = section.widths();
    int entryWidth = widths[0] + widths[1] + widths[2];
    if (entryWidth <= 0) {
      throw new IOException("XRef stream has invalid /W entry widths");
    }

    byte[] data = section.decodedEntries();
    int pos = 0;
    int[] indexPairs = section.indexPairs();
    for (int i = 0; i < indexPairs.length; i += 2) {
      int firstObj = indexPairs[i];
      int count = indexPairs[i + 1];
      for (int delta = 0; delta < count; delta++) {
        if (pos + entryWidth > data.length) {
          throw new IOException("XRef stream data is shorter than declared /Index coverage");
        }
        int currentObj = firstObj + delta;
        int type = widths[0] == 0 ? 1 : (int) readUnsigned(data, pos, widths[0]);
        long field2 = readUnsigned(data, pos + widths[0], widths[1]);
        int field3 = (int) readUnsigned(data, pos + widths[0] + widths[1], widths[2]);
        if (currentObj == objNum) {
          return new XrefEntry(type, field2, field3);
        }
        pos += entryWidth;
      }
    }
    return null;
  }

  @CheckForNull
  private static byte[] extractDictionaryFromObjectStream(
      MemorySegment pdf, int targetObjNum, long objStreamNum, int objectIndex) throws IOException {
    if (objStreamNum <= 0 || objStreamNum > Integer.MAX_VALUE || objectIndex < 0) {
      return null;
    }
    DictionaryRange objStreamRange = findObjectDictionaryRange(pdf, (int) objStreamNum, 0);
    if (objStreamRange == null) {
      return null;
    }

    long osDictStart = objStreamRange.start();
    long osDictEnd = objStreamRange.endExclusive();
    int objectCount = (int) scanRequiredIntAfterKey(pdf, osDictStart, osDictEnd, N_KEY, "/N");
    int firstOffset = (int) scanRequiredIntAfterKey(pdf, osDictStart, osDictEnd, FIRST_KEY, "/First");
    byte[] decodedStream = decodeDirectStreamObject(pdf, osDictStart, osDictEnd);
    if (firstOffset < 0 || firstOffset > decodedStream.length || objectIndex >= objectCount) {
      throw new IOException("Object stream header indexes are invalid");
    }

    MemorySegment decodedSeg = MemorySegment.ofArray(decodedStream);
    int[] objNumbers = new int[objectCount];
    int[] offsets = new int[objectCount];
    parseObjectStreamHeader(decodedSeg, firstOffset, objectCount, objNumbers, offsets);

    int resolvedIndex = objectIndex;
    if (resolvedIndex >= objNumbers.length || objNumbers[resolvedIndex] != targetObjNum) {
      resolvedIndex = -1;
      for (int i = 0; i < objNumbers.length; i++) {
        if (objNumbers[i] == targetObjNum) {
          resolvedIndex = i;
          break;
        }
      }
      if (resolvedIndex < 0) {
        return null;
      }
    }

    int bodyStart = firstOffset + offsets[resolvedIndex];
    int bodyEnd = decodedStream.length;
    if (resolvedIndex + 1 < offsets.length) {
      bodyEnd = firstOffset + offsets[resolvedIndex + 1];
    }
    if (bodyStart < 0 || bodyEnd <= bodyStart || bodyEnd > decodedStream.length) {
      throw new IOException("Object stream object offsets are invalid");
    }

    MemorySegment objectSeg = decodedSeg.asSlice(bodyStart, bodyEnd - bodyStart);
    long dictStart = indexOf(objectSeg, DICT_START, 0);
    if (dictStart < 0) {
      return null;
    }
    long dictEnd = findDictionaryEnd(objectSeg, dictStart);
    if (dictEnd <= dictStart) {
      return null;
    }
    return objectSeg.asSlice(dictStart, dictEnd - dictStart).toArray(JAVA_BYTE);
  }

  private static byte[] decodeDirectStreamObject(
      MemorySegment pdf, long dictStart, long dictEnd) throws IOException {
    long streamStart = findStreamDataStart(pdf, dictEnd);
    if (streamStart < 0) {
      throw new IOException("Failed to locate stream payload after dictionary");
    }

    long rawLength = resolveIndirectLength(pdf, dictStart, dictEnd);
    if (rawLength <= 0) {
      rawLength = scanIntAfterKey(pdf, dictStart, dictEnd, LENGTH_KEY);
    }
    if (rawLength <= 0 || streamStart + rawLength > pdf.byteSize()) {
      long endstream = indexOf(pdf, ENDSTREAM_KEYWORD, streamStart);
      if (endstream < 0) {
        throw new IOException("Failed to determine stream length");
      }
      rawLength = endstream - streamStart;
    }

    byte[] raw = pdf.asSlice(streamStart, rawLength).toArray(JAVA_BYTE);
    byte[] decoded = applyFilters(pdf, dictStart, dictEnd, raw);
    decoded = applyPredictor(decoded, pdf, dictStart, dictEnd);
    return decoded;
  }

  /**
   * Applies stream filters by scanning /Filter from the dictionary MemorySegment directly.
   * Zero-allocation: no String/Matcher created.
   */
  private static byte[] applyFilters(
      MemorySegment seg, long dictStart, long dictEnd, byte[] raw) throws IOException {
    long filterPos = findTopLevelKey(seg, dictStart, dictEnd, FILTER_KEY);
    if (filterPos < 0) return raw;

    long valPos = skipAsciiWhitespace(seg, filterPos + FILTER_KEY.length, dictEnd);
    if (valPos >= dictEnd) return raw;

    byte b = seg.get(JAVA_BYTE, valPos);
    if (b == '/') {
      // Single filter: /Filter /FlateDecode
      return inflateSingleFilter(seg, valPos, dictEnd, raw);
    }
    if (b == '[') {
      // Array: /Filter [/FlateDecode]
      long arrayEnd = indexOf(seg, new byte[]{']'}, valPos);
      if (arrayEnd < 0) arrayEnd = dictEnd;
      byte[] decoded = raw;
      long scanPos = valPos + 1;
      while (scanPos < arrayEnd) {
        scanPos = skipAsciiWhitespace(seg, scanPos, arrayEnd);
        if (scanPos >= arrayEnd) break;
        if (seg.get(JAVA_BYTE, scanPos) == '/') {
          decoded = inflateSingleFilter(seg, scanPos, arrayEnd, decoded);
          // advance past the name token
          scanPos++;
          while (scanPos < arrayEnd && !isPdfNameDelimiter(seg, scanPos)) scanPos++;
        } else {
          scanPos++;
        }
      }
      return decoded;
    }
    return raw;
  }

  private static byte[] inflateSingleFilter(
      MemorySegment seg, long namePos, long limit, byte[] data) throws IOException {
    // namePos points to '/'; check if the name is FlateDecode or Fl
    if (matchesNameTokenAt(seg, namePos, new byte[]{'/', 'F', 'l', 'a', 't', 'e', 'D', 'e', 'c', 'o', 'd', 'e'}, limit)
        || matchesNameTokenAt(seg, namePos, new byte[]{'/', 'F', 'l'}, limit)) {
      return inflate(data);
    }
    // Extract filter name for error message
    long end = namePos + 1;
    while (end < limit && !isPdfNameDelimiter(seg, end)) end++;
    byte[] nameBytes = seg.asSlice(namePos, end - namePos).toArray(JAVA_BYTE);
    throw new IOException("Unsupported stream filter: " + new String(nameBytes, StandardCharsets.ISO_8859_1));
  }

  private static byte[] applyPredictor(
      byte[] decoded, MemorySegment seg, long dictStart, long dictEnd) throws IOException {
    long predictor = scanIntAfterKey(seg, dictStart, dictEnd, PREDICTOR_KEY);
    if (predictor <= 1) {
      return decoded;
    }
    if (predictor == 2) {
      throw new IOException("TIFF predictor is not supported for PDF stream decoding");
    }
    if (predictor < 10 || predictor > 15) {
      throw new IOException("Unsupported predictor value: " + predictor);
    }
    long columns = scanIntAfterKey(seg, dictStart, dictEnd, COLUMNS_KEY);
    if (columns <= 0) {
      throw new IOException("PNG predictor requires a valid /Columns entry");
    }
    return undoPngPredictor(decoded, (int) columns);
  }

  private static long findStreamDataStart(MemorySegment pdf, long dictEndExclusive) {
    long pos = skipAsciiWhitespace(pdf, dictEndExclusive, pdf.byteSize());
    if (!matchesBytesAt(pdf, pos, STREAM_KEYWORD)) {
      return -1;
    }
    long dataStart = pos + STREAM_KEYWORD.length;
    if (dataStart < pdf.byteSize() && pdf.get(JAVA_BYTE, dataStart) == '\r') {
      dataStart++;
      if (dataStart < pdf.byteSize() && pdf.get(JAVA_BYTE, dataStart) == '\n') {
        dataStart++;
      }
    } else if (dataStart < pdf.byteSize() && pdf.get(JAVA_BYTE, dataStart) == '\n') {
      dataStart++;
    }
    return dataStart;
  }

  private static boolean matchesBytesAt(MemorySegment seg, long offset, byte[] token) {
    if (offset < 0 || offset + token.length > seg.byteSize()) {
      return false;
    }
    for (int i = 0; i < token.length; i++) {
      if (seg.get(JAVA_BYTE, offset + i) != token[i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] undoPngPredictor(byte[] data, int columns) throws IOException {
    int rowSpan = columns + 1;
    if (rowSpan <= 1 || (data.length % rowSpan) != 0) {
      throw new IOException("PNG predictor stream length does not align to row size");
    }

    byte[] out = new byte[(data.length / rowSpan) * columns];
    for (int row = 0; row < data.length / rowSpan; row++) {
      int src = row * rowSpan;
      int dst = row * columns;
      int filter = data[src++] & 0xFF;
      for (int i = 0; i < columns; i++) {
        int left = i == 0 ? 0 : out[dst + i - 1] & 0xFF;
        int up = row == 0 ? 0 : out[dst - columns + i] & 0xFF;
        int upLeft = (i == 0 || row == 0) ? 0 : out[dst - columns + i - 1] & 0xFF;

        int predicted = switch (filter) {
          case 0 -> 0;
          case 1 -> left;
          case 2 -> up;
          case 3 -> (left + up) >>> 1;
          case 4 -> paeth(left, up, upLeft);
          default -> throw new IOException("Unsupported PNG predictor filter: " + filter);
        };
        out[dst + i] = (byte) ((data[src + i] + predicted) & 0xFF);
      }
    }
    return out;
  }

  private static int paeth(int left, int up, int upLeft) {
    int p = left + up - upLeft;
    int leftDist = Math.abs(p - left);
    int upDist = Math.abs(p - up);
    int upLeftDist = Math.abs(p - upLeft);
    if (leftDist <= upDist && leftDist <= upLeftDist) {
      return left;
    }
    if (upDist <= upLeftDist) {
      return up;
    }
    return upLeft;
  }

  /**
   * Scans a dictionary MemorySegment for a key and returns the integer value after it.
   * Zero-allocation replacement for parseOptionalLong(String, Pattern).
   * Returns -1 if the key is not found or the value is not a valid integer.
   */
  private static long scanIntAfterKey(
      MemorySegment seg, long dictStart, long dictEnd, byte[] key) {
    long keyPos = findTopLevelKey(seg, dictStart, dictEnd, key);
    if (keyPos < 0) return -1;
    long valStart = skipAsciiWhitespace(seg, keyPos + key.length, dictEnd);
    long valEnd = scanDigits(seg, valStart, dictEnd);
    if (valEnd <= valStart) return -1;
    return parsePositiveLong(seg, valStart, valEnd);
  }

  /**
   * Like scanIntAfterKey but throws if the key is missing or the value is invalid.
   */
  private static long scanRequiredIntAfterKey(
      MemorySegment seg, long dictStart, long dictEnd, byte[] key, String keyName)
      throws IOException {
    long value = scanIntAfterKey(seg, dictStart, dictEnd, key);
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new IOException("Missing or invalid " + keyName + " entry");
    }
    return value;
  }

  /**
   * Scans /W array from a dictionary MemorySegment. Zero-allocation replacement for
   * parseRequiredTriple(String, W_ARRAY_PATTERN, "/W").
   */
  private static int[] scanWArray(MemorySegment seg, long dictStart, long dictEnd)
      throws IOException {
    long wPos = findTopLevelKey(seg, dictStart, dictEnd, W_KEY);
    if (wPos < 0) throw new IOException("Missing required /W entry");
    long pos = skipAsciiWhitespace(seg, wPos + W_KEY.length, dictEnd);
    if (pos >= dictEnd || seg.get(JAVA_BYTE, pos) != '[') {
      throw new IOException("Missing required /W entry");
    }
    pos++; // skip '['
    int[] result = new int[3];
    for (int i = 0; i < 3; i++) {
      pos = skipAsciiWhitespace(seg, pos, dictEnd);
      long numEnd = scanDigits(seg, pos, dictEnd);
      if (numEnd <= pos) throw new IOException("Invalid /W array");
      result[i] = parsePositiveInt(seg, pos, numEnd);
      pos = numEnd;
    }
    return result;
  }

  /**
   * Scans /Index array from a dictionary MemorySegment. Zero-allocation replacement for
   * parseIndexPairs(String, int).
   */
  private static int[] scanIndexPairs(
      MemorySegment seg, long dictStart, long dictEnd, int defaultSize) throws IOException {
    long idxPos = findTopLevelKey(seg, dictStart, dictEnd, INDEX_KEY);
    if (idxPos < 0) return new int[] {0, defaultSize};
    long pos = skipAsciiWhitespace(seg, idxPos + INDEX_KEY.length, dictEnd);
    if (pos >= dictEnd || seg.get(JAVA_BYTE, pos) != '[') {
      return new int[] {0, defaultSize};
    }
    pos++; // skip '['
    // Scan all integers until ']'
    List<Integer> values = new ArrayList<>(8);
    while (pos < dictEnd) {
      pos = skipAsciiWhitespace(seg, pos, dictEnd);
      if (pos >= dictEnd) break;
      byte b = seg.get(JAVA_BYTE, pos);
      if (b == ']') break;
      long numEnd = scanDigits(seg, pos, dictEnd);
      if (numEnd <= pos) break;
      values.add(parsePositiveInt(seg, pos, numEnd));
      pos = numEnd;
    }
    if ((values.size() & 1) != 0) {
      throw new IOException("/Index array must contain an even number of integers");
    }
    int[] result = new int[values.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = values.get(i);
    }
    return result;
  }

  /**
   * Resolves an indirect /Length reference from a dictionary MemorySegment.
   * Zero-allocation replacement for resolveIndirectLength(MemorySegment, String).
   */
  private static long resolveIndirectLength(
      MemorySegment pdf, long dictStart, long dictEnd) {
    long lengthPos = findTopLevelKey(pdf, dictStart, dictEnd, LENGTH_KEY);
    if (lengthPos < 0) return -1;
    long valStart = skipAsciiWhitespace(pdf, lengthPos + LENGTH_KEY.length, dictEnd);
    long n1End = scanDigits(pdf, valStart, dictEnd);
    if (n1End <= valStart) return -1;
    // Check if this is an indirect reference (num gen R)
    long n2Start = skipAsciiWhitespace(pdf, n1End, dictEnd);
    long n2End = scanDigits(pdf, n2Start, dictEnd);
    if (n2End <= n2Start) {
      // Direct integer value
      return parsePositiveLong(pdf, valStart, n1End);
    }
    long rPos = skipAsciiWhitespace(pdf, n2End, dictEnd);
    if (rPos < dictEnd && pdf.get(JAVA_BYTE, rPos) == 'R') {
      // Indirect reference: resolve via readDirectIntObject
      int objNum = parsePositiveInt(pdf, valStart, n1End);
      int genNum = parsePositiveInt(pdf, n2Start, n2End);
      if (objNum > 0 && genNum >= 0) {
        return readDirectIntObject(pdf, objNum, genNum);
      }
    }
    // Fall back to direct integer
    return parsePositiveLong(pdf, valStart, n1End);
  }

  private static long readDirectIntObject(MemorySegment pdf, int objNum, int genNum) {
    long idx = lastIndexOf(pdf, OBJ_KEYWORD, pdf.byteSize());
    while (idx >= 0) {
      ObjectRef headerRef = parseObjectHeaderRef(pdf, idx);
      if (headerRef != null && headerRef.num == objNum && headerRef.gen == genNum) {
        long start = skipAsciiWhitespace(pdf, idx + OBJ_KEYWORD.length, pdf.byteSize());
        long end = scanDigits(pdf, start, pdf.byteSize());
        if (end > start) {
          return parsePositiveInt(pdf, start, end);
        }
        return -1;
      }
      idx = lastIndexOf(pdf, OBJ_KEYWORD, idx - 1);
    }
    return -1;
  }

  private static int determineNextObjectNumber(MemorySegment pdf, int trailerSize) {
    int maxDirectObjectNumber = findMaxObjectNumber(pdf);
    int next = Math.max(trailerSize, maxDirectObjectNumber + 1);
    return next > 0 ? next : trailerSize;
  }

  @CheckForNull
  private static ObjectRef parseObjectHeaderRef(MemorySegment seg, long objKeywordPos) {
    long keywordEnd = objKeywordPos + OBJ_KEYWORD.length;
    if (!matchesBytesAt(seg, objKeywordPos, OBJ_KEYWORD)
        || !isObjectHeaderTerminator(seg, keywordEnd)) {
      return null;
    }

    long genEnd = objKeywordPos;
    while (genEnd > 0 && isAsciiWhitespace(seg, genEnd - 1)) {
      genEnd--;
    }
    long genStart = genEnd;
    while (genStart > 0 && isAsciiDigit(seg, genStart - 1)) {
      genStart--;
    }
    if (genStart == genEnd) {
      return null;
    }

    long separatorEnd = genStart;
    while (separatorEnd > 0 && isAsciiWhitespace(seg, separatorEnd - 1)) {
      separatorEnd--;
    }
    if (separatorEnd == genStart) {
      return null;
    }

    long objEnd = separatorEnd;
    long objStart = objEnd;
    while (objStart > 0 && isAsciiDigit(seg, objStart - 1)) {
      objStart--;
    }
    if (objStart == objEnd || !isAtLineBoundary(seg, objStart)) {
      return null;
    }

    int num = parsePositiveInt(seg, objStart, objEnd);
    int gen = parsePositiveInt(seg, genStart, genEnd);
    if (num <= 0 || gen < 0) {
      return null;
    }
    return new ObjectRef(num, gen);
  }

  private static long readUnsigned(byte[] data, int offset, int length) {
    long value = 0;
    for (int i = 0; i < length; i++) {
      value = (value << 8) | (data[offset + i] & 0xFFL);
    }
    return value;
  }

  private static byte[] inflate(byte[] raw) throws IOException {
    ReusableByteArrayOutputStream out = STREAM_DECODE_TARGET.get();
    byte[] chunk = STREAM_DECODE_BUF.get();
    out.resetForReuse(STREAM_DECODE_MAX_RETAINED_CAPACITY);
    try (Inflater inflater = new Inflater()) {
      inflater.setInput(raw);
      while (!inflater.finished()) {
        int read = inflater.inflate(chunk);
        if (read > 0) {
          out.write(chunk, 0, read);
          continue;
        }
        if (inflater.needsDictionary()) {
          throw new IOException("Flate stream requires an unsupported preset dictionary");
        }
        if (inflater.needsInput()) {
          break;
        }
        throw new IOException("Failed to inflate Flate stream");
      }
      if (!inflater.finished()) {
        throw new IOException("Flate stream ended before inflater reached stream end");
      }
      return out.toByteArray();
    } catch (DataFormatException e) {
      throw new IOException("Failed to inflate Flate stream", e);
    } finally {
      out.resetForReuse(STREAM_DECODE_MAX_RETAINED_CAPACITY);
    }
  }

  private static boolean dictionaryHasTopLevelNameValue(
      MemorySegment seg, long dictStart, long dictEndExclusive, byte[] key, byte[] value) {
    long keyPos = findTopLevelKey(seg, dictStart, dictEndExclusive, key);
    if (keyPos < 0) return false;
    long valPos = skipAsciiWhitespace(seg, keyPos + key.length, dictEndExclusive);
    return matchesNameTokenAt(seg, valPos, value, dictEndExclusive);
  }

  @CheckForNull
  private static ObjectRef findTopLevelObjectRef(
          MemorySegment seg, long dictEndExclusive) {
    long keyPos = findTopLevelKey(seg, 0, dictEndExclusive, PdfSaver.PAGES_KEY);
    if (keyPos < 0) return null;
    long valPos = skipAsciiWhitespace(seg, keyPos + PdfSaver.PAGES_KEY.length, dictEndExclusive);
    return parseObjectRef(seg, valPos, dictEndExclusive);
  }

  private static long findTopLevelKey(
      MemorySegment seg, long dictStart, long dictEndExclusive, byte[] key) {
    long pos = dictStart + DICT_START.length;
    int depth = 1;
    while (pos <= dictEndExclusive - key.length) {
      byte b = seg.get(JAVA_BYTE, pos);
      if (b == '(') {
        pos = findStringEnd(seg, pos, dictEndExclusive);
        continue;
      }
      if (b == '%') {
        pos = skipComment(seg, pos, dictEndExclusive);
        continue;
      }
      if (pos < dictEndExclusive - 1) {
        byte b2 = seg.get(JAVA_BYTE, pos + 1);
        if (b == '<' && b2 == '<') {
          depth++;
          pos += 2;
          continue;
        }
        if (b == '>' && b2 == '>') {
          depth--;
          if (depth == 0) break;
          pos += 2;
          continue;
        }
      }
      if (depth == 1 && b == '/' && matchesNameTokenAt(seg, pos, key, dictEndExclusive)) {
        return pos;
      }
      pos++;
    }
    return -1;
  }

  private static long findStringEnd(MemorySegment seg, long start, long limit) {
    int parenDepth = 1;
    long pos = start + 1;
    while (pos < limit && parenDepth > 0) {
      byte b = seg.get(JAVA_BYTE, pos);
      if (b == '\\') {
        pos += 2;
        continue;
      }
      if (b == '(') parenDepth++;
      else if (b == ')') parenDepth--;
      pos++;
    }
    return pos;
  }

  private static long skipComment(MemorySegment seg, long start, long limit) {
    long pos = start + 1;
    while (pos < limit) {
      byte b = seg.get(JAVA_BYTE, pos);
      if (b == '\r' || b == '\n') break;
      pos++;
    }
    return pos;
  }

  @CheckForNull
  private static ObjectRef parseObjectRef(MemorySegment seg, long from, long endExclusive) {
    long n1Start = skipAsciiWhitespace(seg, from, endExclusive);
    long n1End = scanDigits(seg, n1Start, endExclusive);
    long n2Start = skipAsciiWhitespace(seg, n1End, endExclusive);
    long n2End = scanDigits(seg, n2Start, endExclusive);
    long rPos = skipAsciiWhitespace(seg, n2End, endExclusive);
    if (n1End <= n1Start || n2End <= n2Start || rPos >= endExclusive) {
      return null;
    }
    if (seg.get(JAVA_BYTE, rPos) != 'R') {
      return null;
    }

    int num = parsePositiveInt(seg, n1Start, n1End);
    int gen = parsePositiveInt(seg, n2Start, n2End);
    if (num <= 0 || gen < 0) {
      return null;
    }
    return new ObjectRef(num, gen);
  }

  private static int parseTrailerSize(MemorySegment seg, long from, long endExclusive) {
    long start = skipAsciiWhitespace(seg, from, endExclusive);
    long end = scanDigits(seg, start, endExclusive);
    if (end <= start) {
      return 0;
    }
    return parsePositiveInt(seg, start, end);
  }

  private static boolean isLikelyPdfDate(String value) {
    if (value == null || !value.startsWith("D:")) {
      return false;
    }

    int pos = 2;
    int digits = 0;
    while (pos < value.length() && digits < 14) {
      char c = value.charAt(pos);
      if (c < '0' || c > '9') {
        break;
      }
      digits++;
      pos++;
    }
    if (digits < 4 || (digits & 1) != 0 || digits > 14) {
      return false;
    }
    if (pos == value.length()) {
      return true;
    }

    char tz = value.charAt(pos);
    if (tz == 'Z') {
      return pos + 1 == value.length();
    }
    if (tz != '+' && tz != '-') {
      return false;
    }

    pos++;
    int tzHourStart = pos;
    while (pos < value.length() && pos - tzHourStart < 2) {
      char c = value.charAt(pos);
      if (c < '0' || c > '9') {
        break;
      }
      pos++;
    }
    if (pos - tzHourStart != 2) {
      return false;
    }
    if (pos == value.length()) {
      return true;
    }
    if (value.charAt(pos) == '\'') {
      pos++;
      if (pos == value.length()) {
        return false;
      }
    }

    int tzMinuteStart = pos;
    while (pos < value.length() && pos - tzMinuteStart < 2) {
      char c = value.charAt(pos);
      if (c < '0' || c > '9') {
        break;
      }
      pos++;
    }
    if (pos - tzMinuteStart != 2) {
      return false;
    }
    if (pos < value.length() && value.charAt(pos) == '\'') {
      pos++;
    }
    return pos == value.length();
  }

  private static long findLastStartxrefValue(MemorySegment tail) {
    long idx = lastIndexOf(tail, STARTXREF_KEYWORD, tail.byteSize());
    if (idx < 0) {
      return 0;
    }
    long pos = skipAsciiWhitespace(tail, idx + STARTXREF_KEYWORD.length, tail.byteSize());
    long end = scanDigits(tail, pos, tail.byteSize());
    if (end <= pos) {
      return 0;
    }

    long value = 0;
    for (long i = pos; i < end; i++) {
      byte b = tail.get(JAVA_BYTE, i);
      value = (value * 10) + (b - '0');
      if (value < 0) {
        return 0;
      }
    }
    return value;
  }

  private static long findDictionaryEnd(MemorySegment seg, long dictStart) {
    int depth = 0;
    int parenDepth = 0; // tracks nesting inside PDF string literals ( )
    long pos = dictStart;
    long limit = seg.byteSize() - 1;
    while (pos < limit) {
      byte b = seg.get(JAVA_BYTE, pos);
      if (parenDepth > 0) {
        // Inside a PDF string literal: only track escape sequences and ( ) nesting.
        if (b == '\\') {
          pos += 2; // skip the escaped character
          continue;
        }
        if (b == '(') {
          parenDepth++;
        } else if (b == ')') {
          parenDepth--;
        }
        pos++;
        continue;
      }
      // Not inside a string literal.
      if (b == '(') {
        parenDepth++;
        pos++;
        continue;
      }
      byte b2 = seg.get(JAVA_BYTE, pos + 1);
      if (b == '<' && b2 == '<') {
        depth++;
        pos += 2;
        continue;
      }
      if (b == '>' && b2 == '>') {
        depth--;
        pos += 2;
        if (depth == 0) {
          return pos;
        }
        continue;
      }
      pos++;
    }
    return -1;
  }

  private static boolean matchesNameTokenAt(
      MemorySegment seg, long offset, byte[] token, long endExclusive) {
    long tokenEnd = offset + token.length;
    if (tokenEnd > endExclusive) {
      return false;
    }
    for (int i = 0; i < token.length; i++) {
      if (seg.get(JAVA_BYTE, offset + i) != token[i]) {
        return false;
      }
    }
    // The token must end at the boundary OR be followed by a PDF name-delimiter character.
    // PDF names end at whitespace OR any of: ( ) < > [ ] { } / %
    return tokenEnd >= endExclusive || isPdfNameDelimiter(seg, tokenEnd);
  }

  /** Returns true if the byte at {@code idx} cannot appear inside an unescaped PDF name token. */
  private static boolean isPdfNameDelimiter(MemorySegment seg, long idx) {
    byte b = seg.get(JAVA_BYTE, idx);
    return b == 0x00 || b == '\t' || b == '\n' || b == 0x0C || b == '\r' || b == ' ' || b == '('
        || b == ')' || b == '<' || b == '>' || b == '[' || b == ']' || b == '{' || b == '}'
        || b == '/' || b == '%';
  }

  private static long skipAsciiWhitespace(MemorySegment seg, long from, long endExclusive) {
    long i = Math.max(0, from);
    while (i < endExclusive && isAsciiWhitespace(seg, i)) {
      i++;
    }
    return i;
  }

  private static long scanDigits(MemorySegment seg, long from, long endExclusive) {
    long i = Math.max(0, from);
    while (i < endExclusive && isAsciiDigit(seg, i)) {
      i++;
    }
    return i;
  }

  private static boolean isAtLineBoundary(MemorySegment seg, long start) {
    long pos = start;
    while (pos > 0) {
      byte b = seg.get(JAVA_BYTE, pos - 1);
      if (b == ' ' || b == '\t') {
        pos--;
        continue;
      }
      return b == '\n' || b == '\r';
    }
    return true;
  }

  private static boolean isObjectHeaderTerminator(MemorySegment seg, long pos) {
    return pos >= seg.byteSize() || isPdfNameDelimiter(seg, pos);
  }

  private static int findMaxObjectNumber(MemorySegment pdf) {
    byte[] marker = OBJ_KEYWORD;
    int max = 0;
    long searchPos = 0;
    while (true) {
      long pos = indexOf(pdf, marker, searchPos);
      if (pos < 0) {
        break;
      }
      searchPos = pos + marker.length;
      ObjectRef ref = parseObjectHeaderRef(pdf, pos);
      if (ref == null) {
        continue;
      }
      int num = ref.num;
      if (num > max) {
        max = num;
      }
    }
    return max;
  }

  private static boolean hasXmpUpdate(@CheckForNull org.grimmory.pdfium4j.internal.XmpUpdate xmp) {
    if (xmp == null) return false;
    return switch (xmp) {
      case org.grimmory.pdfium4j.internal.XmpUpdate.Raw raw ->
          raw.xmp() != null && !raw.xmp().isBlank();
      case org.grimmory.pdfium4j.internal.XmpUpdate.Structured _ -> true;
    };
  }

  @CheckForNull
  private static DictionaryRange findObjectDictionaryRange(
      MemorySegment pdf, int objNum, int genNum) {
    byte[] marker = OBJ_KEYWORD;
    long searchFrom = pdf.byteSize();
    long idx;
    while (true) {
      idx = lastIndexOf(pdf, marker, searchFrom);
      if (idx < 0) return null;
      ObjectRef headerRef = parseObjectHeaderRef(pdf, idx);
      if (headerRef == null || headerRef.num != objNum || headerRef.gen != genNum) {
        searchFrom = idx - 1;
        continue;
      }
      break;
    }

    long dictStart = indexOf(pdf, DICT_START, idx + marker.length);
    if (dictStart < 0) return null;
    long dictEnd = findDictionaryEnd(pdf, dictStart);
    if (dictEnd <= dictStart) {
      return null;
    }
    return new DictionaryRange(dictStart, dictEnd);
  }

  private static int parsePositiveInt(MemorySegment seg, long start, long endExclusive) {
    if (start < 0 || endExclusive <= start || endExclusive > seg.byteSize()) {
      return -1;
    }
    long value = 0;
    for (long i = start; i < endExclusive; i++) {
      byte b = seg.get(JAVA_BYTE, i);
      if (b < '0' || b > '9') {
        return -1;
      }
      value = (value * 10) + (b - '0');
      if (value > Integer.MAX_VALUE) {
        return -1;
      }
    }
    return (int) value;
  }

  private static long parsePositiveLong(MemorySegment seg, long start, long endExclusive) {
    if (start < 0 || endExclusive <= start || endExclusive > seg.byteSize()) {
      return -1;
    }
    long value = 0;
    for (long i = start; i < endExclusive; i++) {
      byte b = seg.get(JAVA_BYTE, i);
      if (b < '0' || b > '9') {
        return -1;
      }
      value = (value * 10) + (b - '0');
      if (value < 0 || value > MAX_XREF_OFFSET) {
        return -1;
      }
    }
    return value;
  }

  private static void parseObjectStreamHeader(
      MemorySegment decodedSeg, int firstOffset, int objectCount, int[] objNumbers, int[] offsets)
      throws IOException {
    long pos = 0;
    for (int i = 0; i < objectCount; i++) {
      pos = skipAsciiWhitespace(decodedSeg, pos, firstOffset);
      long objEnd = scanDigits(decodedSeg, pos, firstOffset);
      if (objEnd <= pos) {
        throw new IOException("Object stream header is truncated");
      }
      int objNumber = parsePositiveInt(decodedSeg, pos, objEnd);
      pos = skipAsciiWhitespace(decodedSeg, objEnd, firstOffset);
      long offsetEnd = scanDigits(decodedSeg, pos, firstOffset);
      if (offsetEnd <= pos) {
        throw new IOException("Object stream header is truncated");
      }
      int offset = parsePositiveInt(decodedSeg, pos, offsetEnd);
      if (objNumber < 0 || offset < 0) {
        throw new IOException("Object stream header contains invalid object references");
      }
      objNumbers[i] = objNumber;
      offsets[i] = offset;
      pos = offsetEnd;
    }
  }

  private static boolean isAsciiDigit(MemorySegment seg, long idx) {
    byte b = seg.get(JAVA_BYTE, idx);
    return b >= '0' && b <= '9';
  }

  private static boolean isAsciiWhitespace(MemorySegment seg, long idx) {
    byte b = seg.get(JAVA_BYTE, idx);
    // PDF spec Table 1: NUL, HT, LF, FF, CR, SP are whitespace.
    return b == 0x00 || b == '\t' || b == '\n' || b == 0x0C || b == '\r' || b == ' ';
  }

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  static String encodePdfString(String value) {
    if (value == null || value.isEmpty()) return "()";
    boolean needsUnicode = false;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 127) {
        needsUnicode = true;
        break;
      }
    }
    if (!needsUnicode) {
      StringBuilder sb = new StringBuilder(value.length() + 2);
      sb.append('(');
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '(' -> sb.append("\\(");
          case ')' -> sb.append("\\)");
          case '\\' -> sb.append("\\\\");
          default -> sb.append(c);
        }
      }
      sb.append(')');
      return sb.toString();
    }
    StringBuilder sb = new StringBuilder(value.length() * 4 + 6);
    sb.append("<FEFF");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      sb.append(HEX[(c >> 12) & 0x0F]);
      sb.append(HEX[(c >> 8) & 0x0F]);
      sb.append(HEX[(c >> 4) & 0x0F]);
      sb.append(HEX[c & 0x0F]);
    }
    sb.append('>');
    return sb.toString();
  }

  private static String formatPdfDate() {
    return "D:" + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
  }

  private static void writeSegment(MemorySegment seg, OutputStream out) throws IOException {
    long size = seg.byteSize();
    long pos = 0;
    // Short-lived local allocation; generational GC reclaims it after this method returns.
    byte[] buf = new byte[65536];
    while (pos < size) {
      int len = (int) Math.min(buf.length, size - pos);
      MemorySegment.copy(seg, JAVA_BYTE, pos, buf, 0, len);
      out.write(buf, 0, len);
      pos += len;
    }
  }

  private static long indexOf(MemorySegment haystack, byte[] needle, long fromIndex) {
    long limit = haystack.byteSize() - needle.length;
    for (long i = fromIndex; i <= limit; i++) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (haystack.get(JAVA_BYTE, i + j) != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }

  private static long lastIndexOf(MemorySegment haystack, byte[] needle, long fromIndex) {
    long start = Math.min(fromIndex, haystack.byteSize() - needle.length);
    for (long i = start; i >= 0; i--) {
      boolean match = true;
      for (int j = 0; j < needle.length; j++) {
        if (haystack.get(JAVA_BYTE, i + j) != needle[j]) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }
}
