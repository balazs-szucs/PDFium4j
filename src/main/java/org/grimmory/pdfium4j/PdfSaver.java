package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.ByteArrayInputStream;
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
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;
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
  private static final ThreadLocal<ByteArrayOutputStream> SAVE_CALLBACK_TARGET =
      new ThreadLocal<>();

  /** Reused per-thread staging buffer for native save callbacks; bounded to 64 KiB. */
  private static final ThreadLocal<byte[]> SAVE_CALLBACK_BUF =
      ThreadLocal.withInitial(() -> new byte[65536]);

  private static final XmpMetadataWriter XMP_WRITER = new XmpMetadataWriter();
  private static final long INITIAL_TAIL_SCAN_BYTES = 64L * 1024L;
  private static final long SECONDARY_TAIL_SCAN_BYTES = 256L * 1024L;
  private static final long TAIL_SCAN_BYTES = 1024L * 1024L;
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

  private static final Pattern METADATA_REF_PATTERN =
      Pattern.compile("/Metadata\\s+\\d+\\s+\\d+\\s+R\\b");
  private static final Pattern FILTER_ARRAY_PATTERN = Pattern.compile("/Filter\\s*\\[([^\\]]+)\\]");
  private static final Pattern FILTER_NAME_PATTERN = Pattern.compile("/Filter\\s*/([A-Za-z0-9]+)");
  private static final Pattern LENGTH_PATTERN = Pattern.compile("/Length\\s+(\\d+)");
  private static final Pattern LENGTH_REF_PATTERN =
      Pattern.compile("/Length\\s+(\\d+)\\s+(\\d+)\\s+R");
  private static final Pattern FIRST_PATTERN = Pattern.compile("/First\\s+(\\d+)");
  private static final Pattern N_PATTERN = Pattern.compile("/N\\s+(\\d+)");
  private static final Pattern PREV_PATTERN = Pattern.compile("/Prev\\s+(\\d+)");
  private static final Pattern PREDICTOR_PATTERN = Pattern.compile("/Predictor\\s+(\\d+)");
  private static final Pattern COLUMNS_PATTERN = Pattern.compile("/Columns\\s+(\\d+)");
  private static final Pattern W_ARRAY_PATTERN =
      Pattern.compile("/W\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*\\]");
  private static final Pattern INDEX_ARRAY_PATTERN = Pattern.compile("/Index\\s*\\[([^\\]]+)\\]");
  private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("/([A-Za-z0-9]+)");

  private static final byte[] DICT_START = "<<".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] TRAILER_KEYWORD = "trailer".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] STARTXREF_KEYWORD = "startxref".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_KEYWORD = "xref".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] STREAM_KEYWORD = "stream".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ENDSTREAM_KEYWORD = "endstream".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] TYPE_KEY = "/Type".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_TYPE_NAME = "/XRef".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] CATALOG_TYPE_NAME = "/Catalog".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ROOT_KEY = "/Root".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] INFO_KEY = "/Info".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] SIZE_KEY = "/Size".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] OBJ_KEYWORD = "obj".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] ENCRYPT_KEY = "/Encrypt".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_HEADER = "xref\n".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XREF_ENTRY_TEMPLATE =
      "0000000000 00000 n \n".getBytes(StandardCharsets.ISO_8859_1);

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
        writeIncrementalUpdate(params, arena);
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
    ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
    SAVE_CALLBACK_TARGET.set(baos);
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
      SAVE_CALLBACK_TARGET.remove();
    }
  }

  @SuppressWarnings({"PMD.UnusedFormalParameter", "unused"})
  private static int writeBlockCallback(MemorySegment pThis, MemorySegment pData, long size) {
    if (FfmHelper.isNull(pThis) || FfmHelper.isNull(pData)) return 0;
    ByteArrayOutputStream baos = SAVE_CALLBACK_TARGET.get();
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

  private record XrefStreamSection(
      TrailerFields trailerFields,
      int[] widths,
      int[] indexPairs,
      long prevOffset,
      byte[] decodedEntries) {}

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
    while (src.read(buffer) != -1) {
      buffer.flip();
      while (buffer.hasRemaining()) {
        dst.write(buffer);
      }
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

  private static byte[] buildInfoObject(int num, Map<MetadataTag, String> metadata) {
    StringBuilder sb = new StringBuilder((metadata.size() * 64) + 64);
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
    byte[] content;
    if (xmp instanceof org.grimmory.pdfium4j.internal.XmpUpdate.Raw raw) {
      content = raw.xmp().getBytes(StandardCharsets.UTF_8);
    } else if (xmp instanceof org.grimmory.pdfium4j.internal.XmpUpdate.Structured structured) {
      // Must buffer to learn the serialized length before writing the PDF stream header.
      ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
      XMP_WRITER.write(structured.metadata(), baos);
      content = baos.toByteArray();
    } else {
      throw new IOException("Unknown XmpUpdate type: " + xmp.getClass().getSimpleName());
    }
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
    StringBuilder sb = new StringBuilder(oldDict.length() + 128);
    sb.append(catalogRef.num).append(" ").append(catalogRef.gen).append(" obj\n");
    String dict = METADATA_REF_PATTERN.matcher(oldDict).replaceFirst("");
    int closeIdx = dict.lastIndexOf(">>");
    if (closeIdx >= 0) {
      dict =
          dict.substring(0, closeIdx)
              + "/Metadata "
              + xmpObjNum
              + " 0 R "
              + dict.substring(closeIdx);
    }
    sb.append(dict).append("\nendobj\n");
    return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private static ParsedTail parseTail(MemorySegment pdf) throws IOException {
    ParsedTail parsed = tryParseTail(pdf, INITIAL_TAIL_SCAN_BYTES);
    if (parsed != null) {
      return parsed;
    }
    parsed = tryParseTail(pdf, SECONDARY_TAIL_SCAN_BYTES);
    if (parsed != null) {
      return parsed;
    }
    parsed = tryParseTail(pdf, TAIL_SCAN_BYTES);
    if (parsed != null) {
      return parsed;
    }
    parsed = tryParseTail(pdf, pdf.byteSize());
    if (parsed != null) {
      return parsed;
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
    } catch (IOException ignored) {
      // Some modern files store the catalog in compressed object streams. If resolution fails here,
      // defer the hard failure until an XMP update actually needs the catalog bytes.
    }
    if (rootDict != null && !isCatalogDictionary(rootDict)) {
      throw new IOException("Trailer Root does not reference a Catalog object");
    }

    return new TrailerInfo(rootRef, infoRef, size);
  }

  private static TrailerFields parseTrailerDictionary(
      MemorySegment tail, long dictStart, long dictEndExclusive) {
    ObjectRef rootRef = null;
    ObjectRef infoRef = null;
    int size = 0;
    boolean hasSizeEntry = false;
    boolean hasEncrypt = false;

    long pos = dictStart + DICT_START.length;
    int depth = 1;
    while (pos < dictEndExclusive - 1) {
      byte b1 = tail.get(JAVA_BYTE, pos);
      byte b2 = tail.get(JAVA_BYTE, pos + 1);
      if (b1 == '<' && b2 == '<') {
        depth++;
        pos += 2;
        continue;
      }
      if (b1 == '>' && b2 == '>') {
        depth--;
        if (depth == 0) {
          break;
        }
        pos += 2;
        continue;
      }
      if (depth == 1 && b1 == '/') {
        if (rootRef == null && matchesNameTokenAt(tail, pos, ROOT_KEY, dictEndExclusive)) {
          rootRef = parseObjectRef(tail, pos + ROOT_KEY.length, dictEndExclusive);
        } else if (infoRef == null && matchesNameTokenAt(tail, pos, INFO_KEY, dictEndExclusive)) {
          infoRef = parseObjectRef(tail, pos + INFO_KEY.length, dictEndExclusive);
        } else if (size == 0 && matchesNameTokenAt(tail, pos, SIZE_KEY, dictEndExclusive)) {
          hasSizeEntry = true;
          size = parseTrailerSize(tail, pos + SIZE_KEY.length, dictEndExclusive);
        } else if (!hasEncrypt && matchesNameTokenAt(tail, pos, ENCRYPT_KEY, dictEndExclusive)) {
          hasEncrypt = true;
        }
      }
      pos++;
    }
    return new TrailerFields(rootRef, infoRef, size, hasSizeEntry, hasEncrypt);
  }

  private static TrailerFields parseXrefStreamFields(MemorySegment pdf, long xrefOffset)
      throws IOException {
    return parseXrefStreamSection(pdf, xrefOffset).trailerFields();
  }

  private static boolean isXrefStreamDictionary(
      MemorySegment seg, long dictStart, long dictEndExclusive) {
    long pos = dictStart + DICT_START.length;
    int depth = 1;
    while (pos < dictEndExclusive - 1) {
      byte b1 = seg.get(JAVA_BYTE, pos);
      byte b2 = seg.get(JAVA_BYTE, pos + 1);
      if (b1 == '<' && b2 == '<') {
        depth++;
        pos += 2;
        continue;
      }
      if (b1 == '>' && b2 == '>') {
        depth--;
        if (depth == 0) {
          return false;
        }
        pos += 2;
        continue;
      }
      if (depth == 1 && b1 == '/' && matchesNameTokenAt(seg, pos, TYPE_KEY, dictEndExclusive)) {
        long typePos = skipAsciiWhitespace(seg, pos + TYPE_KEY.length, dictEndExclusive);
        if (matchesNameTokenAt(seg, typePos, XREF_TYPE_NAME, dictEndExclusive)) {
          return true;
        }
      }
      pos++;
    }
    return false;
  }

  private static boolean isCatalogDictionary(byte[] dictBytes) {
    if (dictBytes.length == 0) {
      return false;
    }
    MemorySegment dict = MemorySegment.ofArray(dictBytes);
    return dictionaryHasTopLevelNameValue(dict, 0, dict.byteSize(), TYPE_KEY, CATALOG_TYPE_NAME);
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
    return resolveObjectDictionaryBytesFromXref(pdf, ref, xrefOffset, new HashSet<>());
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
    } catch (IOException ignored) {
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
    long limit = pdf.byteSize();
    long start = skipAsciiWhitespace(pdf, xrefOffset, limit);
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
    String dict =
        new String(
            pdf.asSlice(dictStart, dictEnd - dictStart).toArray(JAVA_BYTE),
            StandardCharsets.ISO_8859_1);
    return parseOptionalLong(dict, PREV_PATTERN);
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

    byte[] dictBytes = pdf.asSlice(dictStart, dictEnd - dictStart).toArray(JAVA_BYTE);
    String dict = new String(dictBytes, StandardCharsets.ISO_8859_1);
    TrailerFields trailerFields = parseTrailerDictionary(pdf, dictStart, dictEnd);
    if (trailerFields.size() <= 0) {
      throw new IOException("XRef stream dictionary is missing a valid /Size");
    }

    int[] widths = parseRequiredTriple(dict, W_ARRAY_PATTERN, "/W");
    int[] indexPairs = parseIndexPairs(dict, trailerFields.size());
    long prevOffset = parseOptionalLong(dict, PREV_PATTERN);
    byte[] decodedEntries = decodeDirectStreamObject(pdf, dictEnd, dict);
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

    byte[] objStreamDictBytes =
        pdf.asSlice(objStreamRange.start(), objStreamRange.endExclusive() - objStreamRange.start())
            .toArray(JAVA_BYTE);
    String objStreamDict = new String(objStreamDictBytes, StandardCharsets.ISO_8859_1);
    int objectCount = parseRequiredInt(objStreamDict, N_PATTERN, "/N");
    int firstOffset = parseRequiredInt(objStreamDict, FIRST_PATTERN, "/First");
    byte[] decodedStream =
        decodeDirectStreamObject(pdf, objStreamRange.endExclusive(), objStreamDict);
    if (firstOffset < 0 || firstOffset > decodedStream.length || objectIndex >= objectCount) {
      throw new IOException("Object stream header indexes are invalid");
    }

    byte[] headerBytes = new byte[firstOffset];
    System.arraycopy(decodedStream, 0, headerBytes, 0, firstOffset);
    String header = new String(headerBytes, StandardCharsets.ISO_8859_1).trim();
    if (header.isEmpty()) {
      return null;
    }
    String[] parts = header.split("\\s+");
    if (parts.length < objectCount * 2) {
      throw new IOException("Object stream header is truncated");
    }

    int[] objNumbers = new int[objectCount];
    int[] offsets = new int[objectCount];
    for (int i = 0; i < objectCount; i++) {
      objNumbers[i] = Integer.parseInt(parts[i * 2]);
      offsets[i] = Integer.parseInt(parts[(i * 2) + 1]);
    }

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

    byte[] objectBytes = new byte[bodyEnd - bodyStart];
    System.arraycopy(decodedStream, bodyStart, objectBytes, 0, objectBytes.length);
    MemorySegment objectSeg = MemorySegment.ofArray(objectBytes);
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
      MemorySegment pdf, long dictEndExclusive, String dict) throws IOException {
    long streamStart = findStreamDataStart(pdf, dictEndExclusive);
    if (streamStart < 0) {
      throw new IOException("Failed to locate stream payload after dictionary");
    }

    long rawLength = resolveIndirectLength(pdf, dict);
    if (rawLength <= 0) {
      rawLength = parseOptionalLong(dict, LENGTH_PATTERN);
    }
    if (rawLength <= 0 || streamStart + rawLength > pdf.byteSize()) {
      long endstream = indexOf(pdf, ENDSTREAM_KEYWORD, streamStart);
      if (endstream < 0) {
        throw new IOException("Failed to determine stream length");
      }
      rawLength = endstream - streamStart;
    }

    byte[] raw = pdf.asSlice(streamStart, rawLength).toArray(JAVA_BYTE);
    List<String> filters = parseFilterNames(dict);
    byte[] decoded = raw;
    for (String filter : filters) {
      if (filter.equals("FlateDecode") || filter.equals("Fl")) {
        decoded = inflate(decoded);
      } else {
        throw new IOException("Unsupported stream filter: " + filter);
      }
    }
    decoded = applyPredictor(decoded, dict);
    return decoded;
  }

  private static byte[] applyPredictor(byte[] decoded, String dict) throws IOException {
    int predictor = (int) parseOptionalLong(dict, PREDICTOR_PATTERN);
    if (predictor <= 1) {
      return decoded;
    }

    if (predictor == 2) {
      throw new IOException("TIFF predictor is not supported for PDF stream decoding");
    }

    if (predictor < 10 || predictor > 15) {
      throw new IOException("Unsupported predictor value: " + predictor);
    }

    int columns = (int) parseOptionalLong(dict, COLUMNS_PATTERN);
    if (columns <= 0) {
      throw new IOException("PNG predictor requires a valid /Columns entry");
    }
    return undoPngPredictor(decoded, columns);
  }

  private static byte[] undoPngPredictor(byte[] data, int columns) throws IOException {
    int rowSpan = columns + 1;
    if (rowSpan <= 1 || (data.length % rowSpan) != 0) {
      throw new IOException("PNG predictor stream length does not align to row size");
    }

    byte[] out = new byte[(data.length / rowSpan) * columns];
    int src = 0;
    int dst = 0;
    while (src < data.length) {
      int filter = data[src++] & 0xFF;
      switch (filter) {
        case 0 -> {
          System.arraycopy(data, src, out, dst, columns);
        }
        case 1 -> {
          for (int i = 0; i < columns; i++) {
            int left = i == 0 ? 0 : out[dst + i - 1] & 0xFF;
            out[dst + i] = (byte) ((data[src + i] + left) & 0xFF);
          }
        }
        case 2 -> {
          for (int i = 0; i < columns; i++) {
            int up = dst < columns ? 0 : out[dst - columns + i] & 0xFF;
            out[dst + i] = (byte) ((data[src + i] + up) & 0xFF);
          }
        }
        case 3 -> {
          for (int i = 0; i < columns; i++) {
            int left = i == 0 ? 0 : out[dst + i - 1] & 0xFF;
            int up = dst < columns ? 0 : out[dst - columns + i] & 0xFF;
            out[dst + i] = (byte) ((data[src + i] + ((left + up) >>> 1)) & 0xFF);
          }
        }
        case 4 -> {
          for (int i = 0; i < columns; i++) {
            int left = i == 0 ? 0 : out[dst + i - 1] & 0xFF;
            int up = dst < columns ? 0 : out[dst - columns + i] & 0xFF;
            int upLeft = (i == 0 || dst < columns) ? 0 : out[dst - columns + i - 1] & 0xFF;
            out[dst + i] = (byte) ((data[src + i] + paeth(left, up, upLeft)) & 0xFF);
          }
        }
        default -> throw new IOException("Unsupported PNG predictor filter: " + filter);
      }
      src += columns;
      dst += columns;
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

  private static List<String> parseFilterNames(String dict) {
    var arrayMatcher = FILTER_ARRAY_PATTERN.matcher(dict);
    if (arrayMatcher.find()) {
      List<String> filters = new ArrayList<>(4);
      var nameMatcher = NAME_TOKEN_PATTERN.matcher(arrayMatcher.group(1));
      while (nameMatcher.find()) {
        filters.add(nameMatcher.group(1));
      }
      return filters;
    }

    var singleMatcher = FILTER_NAME_PATTERN.matcher(dict);
    if (singleMatcher.find()) {
      return List.of(singleMatcher.group(1));
    }
    return List.of();
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

  private static int[] parseRequiredTriple(String dict, Pattern pattern, String key)
      throws IOException {
    var matcher = pattern.matcher(dict);
    if (!matcher.find()) {
      throw new IOException("Missing required " + key + " entry");
    }
    return new int[] {
      Integer.parseInt(matcher.group(1)),
      Integer.parseInt(matcher.group(2)),
      Integer.parseInt(matcher.group(3))
    };
  }

  private static int[] parseIndexPairs(String dict, int defaultSize) throws IOException {
    var matcher = INDEX_ARRAY_PATTERN.matcher(dict);
    if (!matcher.find()) {
      return new int[] {0, defaultSize};
    }
    String[] parts = matcher.group(1).trim().split("\\s+");
    if ((parts.length & 1) != 0) {
      throw new IOException("/Index array must contain an even number of integers");
    }
    int[] values = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      values[i] = Integer.parseInt(parts[i]);
    }
    return values;
  }

  private static int parseRequiredInt(String dict, Pattern pattern, String key) throws IOException {
    long value = parseOptionalLong(dict, pattern);
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new IOException("Missing or invalid " + key + " entry");
    }
    return (int) value;
  }

  private static long parseOptionalLong(String dict, Pattern pattern) {
    var matcher = pattern.matcher(dict);
    if (!matcher.find()) {
      return -1;
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static long resolveIndirectLength(MemorySegment pdf, String dict) {
    var matcher = LENGTH_REF_PATTERN.matcher(dict);
    if (!matcher.find()) {
      return -1;
    }

    int objNum;
    int genNum;
    try {
      objNum = Integer.parseInt(matcher.group(1));
      genNum = Integer.parseInt(matcher.group(2));
    } catch (NumberFormatException e) {
      return -1;
    }

    return readDirectIntObject(pdf, objNum, genNum);
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
    try (ByteArrayInputStream in = new ByteArrayInputStream(raw);
        InflaterInputStream inflater = new InflaterInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 2)) {
      inflater.transferTo(out);
      return out.toByteArray();
    }
  }

  private static boolean dictionaryHasTopLevelNameValue(
      MemorySegment seg, long dictStart, long dictEndExclusive, byte[] key, byte[] value) {
    long pos = dictStart + DICT_START.length;
    int depth = 1;
    while (pos < dictEndExclusive - 1) {
      byte b1 = seg.get(JAVA_BYTE, pos);
      byte b2 = seg.get(JAVA_BYTE, pos + 1);
      if (b1 == '<' && b2 == '<') {
        depth++;
        pos += 2;
        continue;
      }
      if (b1 == '>' && b2 == '>') {
        depth--;
        if (depth == 0) {
          return false;
        }
        pos += 2;
        continue;
      }
      if (depth == 1 && b1 == '/' && matchesNameTokenAt(seg, pos, key, dictEndExclusive)) {
        long valuePos = skipAsciiWhitespace(seg, pos + key.length, dictEndExclusive);
        return matchesNameTokenAt(seg, valuePos, value, dictEndExclusive);
      }
      pos++;
    }
    return false;
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

  private static boolean hasValidObjectHeaderPrefix(MemorySegment seg, long markerPos) {
    long genEnd = markerPos;
    long genStart = genEnd;
    while (genStart > 0 && isAsciiDigit(seg, genStart - 1)) {
      genStart--;
    }
    if (genStart == genEnd) {
      return false;
    }
    long separatorEnd = genStart;
    long separatorStart = separatorEnd;
    while (separatorStart > 0 && isAsciiWhitespace(seg, separatorStart - 1)) {
      separatorStart--;
    }
    if (separatorStart == separatorEnd) {
      return false;
    }
    long objEnd = separatorStart;
    long objStart = objEnd;
    while (objStart > 0 && isAsciiDigit(seg, objStart - 1)) {
      objStart--;
    }
    return objStart < objEnd && isAtLineBoundary(seg, objStart);
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
    if (xmp instanceof org.grimmory.pdfium4j.internal.XmpUpdate.Raw raw) {
      return raw.xmp() != null && !raw.xmp().isBlank();
    }
    return true;
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
