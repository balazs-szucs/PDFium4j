package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.Cleaner;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.grimmory.pdfium4j.exception.PdfCorruptException;
import org.grimmory.pdfium4j.exception.PdfPasswordException;
import org.grimmory.pdfium4j.exception.PdfUnsupportedSecurityException;
import org.grimmory.pdfium4j.exception.PdfiumException;
import org.grimmory.pdfium4j.internal.AttachmentBindings;
import org.grimmory.pdfium4j.internal.DocBindings;
import org.grimmory.pdfium4j.internal.EditBindings;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.IntObjectCache;
import org.grimmory.pdfium4j.internal.IoUtils;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.internal.SegmentOutputStream;
import org.grimmory.pdfium4j.internal.ShimBindings;
import org.grimmory.pdfium4j.internal.SignatureBindings;
import org.grimmory.pdfium4j.internal.ViewBindings;
import org.grimmory.pdfium4j.internal.XmpUpdate;
import org.grimmory.pdfium4j.model.Bookmark;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.grimmory.pdfium4j.model.PageSize;
import org.grimmory.pdfium4j.model.PdfAttachment;
import org.grimmory.pdfium4j.model.PdfDiagnostic;
import org.grimmory.pdfium4j.model.PdfErrorCode;
import org.grimmory.pdfium4j.model.PdfProbeResult;
import org.grimmory.pdfium4j.model.PdfProcessingPolicy;
import org.grimmory.pdfium4j.model.PdfSignature;
import org.grimmory.pdfium4j.model.RenderResult;
import org.grimmory.pdfium4j.model.XmpMetadata;
import org.grimmory.pdfium4j.util.PdfDateUtils;

/** Represents an open PDF document backed by native PDFium. */
public final class PdfDocument implements AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(PdfDocument.class.getName());

  private static final Map<Long, SeekableByteChannel> CHANNELS = new ConcurrentHashMap<>(16);
  private static final Cleaner CLEANER = Cleaner.create();
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final int[] EMPTY_INT_ARRAY = new int[0];

  private static final ExecutorService PREFETCH_EXECUTOR =
      Executors.newFixedThreadPool(
          Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
          r -> {
            Thread t = new Thread(r, "pdfium4j-prefetch");
            t.setDaemon(true);
            return t;
          });

  private static final MetadataTag[] METADATA_TAGS = MetadataTag.values();

  private static final class MetadataCache {
    private static final Map<MetadataTag, MemorySegment> TAG_SEGMENTS;
    private static final MethodHandle[] META_HANDLES;

    static {
      PdfiumLibrary.ensureInitialized();
      Arena auto = Arena.ofAuto();
      TAG_SEGMENTS = new EnumMap<>(MetadataTag.class);
      META_HANDLES = new MethodHandle[METADATA_TAGS.length];
      for (MetadataTag tag : METADATA_TAGS) {
        MemorySegment keySeg = auto.allocateFrom(tag.pdfKey(), StandardCharsets.UTF_8);
        TAG_SEGMENTS.put(tag, keySeg);
        META_HANDLES[tag.ordinal()] =
            MethodHandles.insertArguments(DocBindings.FPDF_GetMetaText(), 1, keySeg);
      }
    }
  }

  private static final XmpMetadataWriter XMP_WRITER = new XmpMetadataWriter();

  // Tail window for fallback file scanning (Info/XMP are typically near trailer/xref).
  private static final long FALLBACK_TAIL_SCAN_BYTES = 256L << 10;

  private static final byte[] INFO_KEY = "/Info".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] DICT_START = "<<".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XMP_START = "<?xpacket begin".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XMP_END = "<?xpacket end".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] XMP_TERM = "?>".getBytes(StandardCharsets.ISO_8859_1);

  private final MemorySegment handle;
  private final Arena docArena;
  private SeekableByteChannel docSourceChannel;
  private final Path sourcePath;
  private final byte[] sourceBytes;
  private final long channelId;
  private final PdfProcessingPolicy policy;
  private final int sourceFileVersion;
  private final Thread ownerThread;
  private final List<PdfPage> openPages = new ArrayList<>(8);
  private volatile boolean closed = false;
  private volatile boolean structurallyModified = false;
  private MemorySegment sourceSegment = null;

  MemorySegment handle() {
    return handle;
  }

  private volatile int cachedPageCount = -1;

  /** Lazy-parsed fallback Info dict key→value map (populated at most once per document). */
  private Map<String, String> cachedFallbackMeta;

  /** Memoized XMP bytes from file-system fallback path. */
  private byte[] cachedFallbackXmp;

  private final Map<MetadataTag, String> pendingMetadata = new EnumMap<>(MetadataTag.class);
  private XmpUpdate pendingXmp = null;
  private final PdfSaver.MetadataProvider nativeMetadataProvider = this::metadataString;
  private final CleanupState state;
  private final Cleaner.Cleanable cleanable;

  private final IntObjectCache<PdfPage> pageCache;
  private final IntObjectCache<PageSize> pageSizeCache;
  private final IntObjectCache<Integer> rotationCache;
  private Map<String, int[]> textIndex = null;

  static final class NoAllocationPathProbe implements AutoCloseable {
    private final Arena arena;
    private final String pathLabel;
    private final MemorySegment pathSeg;
    private final MemorySegment passwordSeg;

    private NoAllocationPathProbe(Path path, String password) {
      this.arena = Arena.ofShared();
      this.pathLabel = path.toString();
      this.pathSeg = arena.allocateFrom(pathLabel);
      this.passwordSeg = password != null ? arena.allocateFrom(password) : MemorySegment.NULL;
    }

    void inspect(int[] output, MemorySegment trailerBuffer) {
      if (output == null || output.length < 3) {
        throw new IllegalArgumentException("output must have length >= 3");
      }
      PdfiumLibrary.ensureInitialized();
      MemorySegment doc = MemorySegment.NULL;
      try {
        doc = (MemorySegment) ViewBindings.FPDF_LoadDocument().invokeExact(pathSeg, passwordSeg);
        if (FfmHelper.isNull(doc)) {
          int err = (int) (long) ViewBindings.FPDF_GetLastError().invokeExact();
          throw mapOpenError("Failed to probe document: " + pathLabel, err);
        }
        output[0] = (int) ViewBindings.FPDF_GetPageCount().invokeExact(doc);
        output[1] =
            ViewBindings.FPDF_DocumentHasValidCrossReferenceTable() == null
                ? 1
                : (int) ViewBindings.FPDF_DocumentHasValidCrossReferenceTable().invokeExact(doc);
        output[2] = readTrailerEndsInto(doc, trailerBuffer);
      } catch (PdfiumException e) {
        throw e;
      } catch (Throwable t) {
        throw new PdfiumException("Failed to inspect document without allocations", t);
      } finally {
        if (!FfmHelper.isNull(doc)) {
          try {
            ViewBindings.FPDF_CloseDocument().invokeExact(doc);
          } catch (Throwable closeError) {
            PdfiumLibrary.ignore(closeError);
          }
        }
      }
    }

    @Override
    public void close() {
      arena.close();
    }
  }

  private PdfDocument(
      MemorySegment handle,
      Arena docArena,
      Path sourcePath,
      Path tempFile,
      byte[] sourceBytes,
      PdfProcessingPolicy policy,
      int sourceFileVersion,
      Thread ownerThread) {
    this.handle = handle.reinterpret(ValueLayout.ADDRESS.byteSize());
    this.docArena = docArena;
    this.docSourceChannel = null;
    this.channelId = 0L;
    this.sourcePath = sourcePath;
    this.sourceBytes = sourceBytes;
    this.sourceSegment = sourceBytes != null ? MemorySegment.ofArray(sourceBytes) : null;
    this.policy = policy;
    this.sourceFileVersion = sourceFileVersion;
    this.ownerThread = ownerThread;
    this.state = new CleanupState(handle, 0L, null, tempFile, docArena);
    this.cleanable = CLEANER.register(this, state);
    this.pageCache =
        new IntObjectCache<>(policy.maxPageCacheBytes()) {
          @Override
          protected void onEvict(PdfPage page) {
            page.release();
          }
        };
    // PageSize and rotation caches are small, so we use a 1MB budget which is plenty
    this.pageSizeCache = new IntObjectCache<>(1024 * 1024);
    this.rotationCache = new IntObjectCache<>(1024 * 1024);
    PdfiumLibrary.incrementDocumentCount();
  }

  private static final class CleanupState implements Runnable {
    private final MemorySegment handle;
    private final long channelId;
    private final AtomicReference<SeekableByteChannel> sourceChannelRef = new AtomicReference<>();
    private final Path tempFile;
    private final Arena docArena;

    private CleanupState(
        MemorySegment handle,
        long channelId,
        SeekableByteChannel chan,
        Path tempFile,
        Arena docArena) {
      this.handle = handle;
      this.channelId = channelId;
      this.sourceChannelRef.set(chan);
      this.tempFile = tempFile;
      this.docArena = docArena;
    }

    void updateSourceChannel(SeekableByteChannel newChannel) {
      this.sourceChannelRef.set(newChannel);
    }

    @Override
    public void run() {
      try {
        if (channelId > 0) {
          SeekableByteChannel removed = CHANNELS.remove(channelId);
          SeekableByteChannel current = sourceChannelRef.get();
          if (removed != null && removed != current) {
            try {
              removed.close();
            } catch (IOException e) {
              PdfiumLibrary.ignore(e);
            }
          }
        }
        SeekableByteChannel chan = sourceChannelRef.getAndSet(null);
        if (chan != null) {
          try {
            chan.close();
          } catch (IOException e) {
            PdfiumLibrary.ignore(e);
          }
        }
        if (tempFile != null) {
          try {
            Files.deleteIfExists(tempFile);
          } catch (IOException e) {
            PdfiumLibrary.ignore(e);
          }
        }
        if (docArena != null) {
          docArena.close();
        }
        if (!FfmHelper.isNull(handle)) {
          try {
            ViewBindings.FPDF_CloseDocument().invokeExact(handle);
          } catch (Throwable t) {
            PdfiumLibrary.ignore(t);
          }
        }
      } finally {
        PdfiumLibrary.decrementDocumentCount();
      }
    }
  }

  public static PdfDocument open(Path path) {
    return open(path, null, resolvePolicy(null));
  }

  @SuppressWarnings("resource")
  public static PdfDocument open(Path path, String password, PdfProcessingPolicy policy) {
    PdfProcessingPolicy resolvedPolicy = resolvePolicy(policy);
    PdfiumLibrary.ensureInitialized();
    try {
      long size = Files.size(path);
      if (size > resolvedPolicy.maxDocumentBytes()) {
        throw new PdfiumException(
            "Document size ("
                + size
                + " bytes) exceeds policy limit ("
                + resolvedPolicy.maxDocumentBytes()
                + ")",
            null);
      }
      try {
        return openFromNativePath(path, password, resolvedPolicy, null);
      } catch (PdfCorruptException e) {
        if (resolvedPolicy.mode() == PdfProcessingPolicy.Mode.RECOVER) {
          return openWithRepair(path, password, resolvedPolicy);
        }
        throw e;
      }
    } catch (IOException e) {
      throw new PdfiumException("Failed to open file: " + path, e);
    }
  }

  /**
   * Opens a PDF document from an {@link InputStream}.
   *
   * <p>Since PDFium requires seekable access to the document, the entire stream is buffered to a
   * temporary file. The temporary file is automatically deleted when the document is closed.
   *
   * @param in the input stream containing the PDF data
   * @return a new PdfDocument instance
   * @throws PdfiumException if the document is corrupt, password protected, or if buffering fails
   */
  public static PdfDocument open(InputStream in) {
    return open(in, null, resolvePolicy(null));
  }

  /**
   * Opens a PDF document from an {@link InputStream} with the given password and policy.
   *
   * @param in the input stream containing the PDF data
   * @param password optional document password
   * @param policy processing policy
   * @return a new PdfDocument instance
   */
  public static PdfDocument open(InputStream in, String password, PdfProcessingPolicy policy) {
    PdfProcessingPolicy resolvedPolicy = resolvePolicy(policy);
    if (in == null) {
      throw new IllegalArgumentException("InputStream must not be null");
    }
    PdfiumLibrary.ensureInitialized();
    Path temp = null;
    try {
      temp = IoUtils.createTempFile("pdfium4j-stream-", ".pdf");
      try (InputStream input = in) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      return openFromNativePath(temp, password, resolvedPolicy, temp);
    } catch (PdfiumException e) {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ex) {
          PdfiumLibrary.ignore(ex);
        }
      }
      throw e;
    } catch (IOException e) {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ex) {
          PdfiumLibrary.ignore(ex);
        }
      }
      throw new PdfiumException("Failed to buffer InputStream to temporary file", e);
    }
  }

  private static PdfDocument openFromNativePath(
      Path path, String password, PdfProcessingPolicy policy, Path tempFile) {
    Arena docArena = Arena.ofShared();
    try {
      MemorySegment pathSeg = docArena.allocateFrom(path.toString());
      MemorySegment pwdSeg =
          (password != null) ? docArena.allocateFrom(password) : MemorySegment.NULL;
      MemorySegment doc =
          (MemorySegment) ViewBindings.FPDF_LoadDocument().invokeExact(pathSeg, pwdSeg);
      if (FfmHelper.isNull(doc)) {
        int err = (int) (long) ViewBindings.FPDF_GetLastError().invokeExact();
        throw mapOpenError("Failed to open document: " + path, err);
      }
      PdfDocument pdfDoc =
          new PdfDocument(
              doc,
              docArena,
                  path,
              tempFile,
              null,
              policy,
              readFileVersion(doc),
              Thread.currentThread());

      try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
        pdfDoc.sourceSegment = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), pdfDoc.docArena);
      } catch (IOException e) {
        PdfiumLibrary.ignore(e);
      }
      
      if (policy.mode() == PdfProcessingPolicy.Mode.RECOVER
          && !pdfDoc.hasValidCrossReferenceTable()) {
        pdfDoc.close();
        return openWithRepair(path, password, policy);
      }
      return pdfDoc;
    } catch (PdfiumException e) {
      docArena.close();
      throw e;
    } catch (Throwable t) {
      docArena.close();
      throw new PdfiumException("Failed to open file: " + path, t);
    }
  }

  private static PdfDocument openWithRepair(
      Path path, String password, PdfProcessingPolicy resolvedPolicy) {
    if (LOGGER.isLoggable(Level.WARNING)) {
      LOGGER.log(
          Level.WARNING,
          "Document corruption detected for {0}. Attempting automatic repair...",
          path);
    }
    Path temp;
    try {
      temp = IoUtils.createTempFile("pdfium4j-autorepair-", ".pdf");
      try (OutputStream out = Files.newOutputStream(temp)) {
        PdfSaver.repair(path, out);
      }
      // Reopen the repaired file. We use STRICT mode to avoid infinite loops if repair still fails.
      return open(temp, password, resolvedPolicy.withMode(PdfProcessingPolicy.Mode.STRICT));
    } catch (Exception e) {
      if (LOGGER.isLoggable(Level.SEVERE)) {
        LOGGER.log(Level.SEVERE, "Automatic repair failed for {0}", path);
      }
      throw new PdfCorruptException(
          "Automatic repair failed for " + path, PdfErrorCode.FORMAT, "open", path.toString(), e);
    }
  }

  public static PdfDocument open(byte[] data) {
    return open(data, null, resolvePolicy(null));
  }

  public static PdfDocument open(byte[] data, String password, PdfProcessingPolicy policy) {
    PdfProcessingPolicy resolvedPolicy = resolvePolicy(policy);
    if (data == null || data.length == 0)
      throw new IllegalArgumentException("data is null or empty");
    if (data.length > resolvedPolicy.maxDocumentBytes()) {
      throw new PdfiumException(
          "Document size ("
              + data.length
              + " bytes) exceeds policy limit ("
              + resolvedPolicy.maxDocumentBytes()
              + ")",
          null);
    }
    PdfiumLibrary.ensureInitialized();
    Arena arena = Arena.ofShared();
    try {
      MemorySegment memSeg = arena.allocate(data.length);
      memSeg.copyFrom(MemorySegment.ofArray(data));

      PdfDocument pdfDoc = open(memSeg, password, resolvedPolicy, arena, data);

      if (resolvedPolicy.mode() == PdfProcessingPolicy.Mode.RECOVER
          && !pdfDoc.hasValidCrossReferenceTable()) {
        pdfDoc.close();
        byte[] repaired = PdfSaver.repair(data);
        return open(repaired, password, resolvedPolicy.withMode(PdfProcessingPolicy.Mode.STRICT));
      }
      return pdfDoc;
    } catch (PdfiumException e) {
      arena.close();
      throw e;
    } catch (Throwable t) {
      arena.close();
      throw new PdfiumException("Unexpected error opening document from bytes", t);
    }
  }

  static PdfDocument open(MemorySegment segment, PdfProcessingPolicy policy) {
    return open(segment, null, policy, Arena.ofShared(), null);
  }

  private static PdfDocument open(
      MemorySegment segment,
      String password,
      PdfProcessingPolicy policy,
      Arena arena,
      byte[] sourceBytes) {
    PdfProcessingPolicy resolvedPolicy =
        policy != null ? policy : PdfProcessingPolicy.defaultPolicy();
    try {
      MemorySegment pwdSeg = password == null ? MemorySegment.NULL : arena.allocateFrom(password);

      MemorySegment docHandle =
          (MemorySegment)
              ViewBindings.FPDF_LoadMemDocument64().invokeExact(segment, segment.byteSize(), pwdSeg);

      if (FfmHelper.isNull(docHandle)) {
        int err = (int) (long) ViewBindings.FPDF_GetLastError().invokeExact();
        throw mapOpenError("Failed to open document from segment", err);
      }

      return new PdfDocument(
          docHandle,
          arena,
              null,
          null,
          sourceBytes,
          resolvedPolicy,
          readFileVersion(docHandle),
          Thread.currentThread());
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Unexpected error opening document from segment", t);
    }
  }

  public synchronized int pageCount() {
    ensureOpen();
    if (cachedPageCount >= 0) return cachedPageCount;
    try {
      cachedPageCount = (int) ShimBindings.pdfium4j_page_count().invokeExact(handle);
      return cachedPageCount;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to get page count", t);
    }
  }

  /** Marks the document as structurally modified and invalidates the cached page count. */
  private void markStructurallyModified() {
    cachedPageCount = -1;
    pageCache.clear();
    pageSizeCache.clear();
    rotationCache.clear();
    structurallyModified = true;
  }

  public synchronized PdfPage page(int index) {
    ensureOpen();
    if (index < 0 || index >= pageCount())
      throw new IllegalArgumentException("Index out of bounds: " + index);

    PdfPage cached = pageCache.get(index);
    if (cached != null && !cached.isClosed()) {
      triggerPrefetch(index);
      return cached;
    }

    try {
      MemorySegment pageHandle =
          (MemorySegment) ViewBindings.FPDF_LoadPage().invokeExact(handle, index);
      if (FfmHelper.isNull(pageHandle)) throwLastError("Failed to load page " + index);
      PdfPage page =
          new PdfPage(
              pageHandle,
              ownerThread,
              policy.maxRenderPixels(),
              p -> {
                unregisterPage(p);
                pageCache.remove(index);
              },
              this::markStructurallyModified);

      registerPage(page);
      page.acquire(); // Cache takes a reference
      pageCache.put(index, page, page.estimatedSizeBytes());

      triggerPrefetch(index);
      return page;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to load page " + index, t);
    }
  }

  private void triggerPrefetch(int index) {
    int radius = policy.prefetchRadius();
    if (radius <= 0) return;

    int count = pageCount();
    for (int i = 1; i <= radius; i++) {
      int next = index + i;
      int prev = index - i;
      if (next < count) warmPage(next);
      if (prev >= 0) warmPage(prev);
    }
  }

  private void warmPage(int index) {
    if (pageCache.get(index) != null) return;

    PREFETCH_EXECUTOR.submit(
        () -> {
          if (closed) return;
          try {
            synchronized (this) {
              if (closed || pageCache.get(index) != null) return;

              MemorySegment pageHandle =
                  (MemorySegment) ViewBindings.FPDF_LoadPage().invokeExact(handle, index);
              if (FfmHelper.isNull(pageHandle)) return;

              PdfPage page =
                  new PdfPage(
                      pageHandle,
                      ownerThread,
                      policy.maxRenderPixels(),
                      p -> {
                        unregisterPage(p);
                        pageCache.remove(index);
                      },
                      this::markStructurallyModified);

              registerPage(page);
              page.acquire();
              pageCache.put(index, page, page.estimatedSizeBytes());
            }
          } catch (Throwable t) {
            PdfiumLibrary.ignore(t);
          }
        });
  }

  public synchronized PageSize pageSize(int index) {
    ensureOpen();
    PageSize cached = pageSizeCache.get(index);
    if (cached != null) return cached;

    try (var _ = ScratchBuffer.acquireScope()) {
      float width = (float) ShimBindings.pdfium4j_page_width().invokeExact(handle, index);
      float height = (float) ShimBindings.pdfium4j_page_height().invokeExact(handle, index);
      PageSize size = new PageSize(width, height);
      pageSizeCache.put(index, size, 32);
      return size;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to get page size " + index, t);
    }
  }

  public synchronized List<Bookmark> bookmarks() {
    ensureOpen();
    try (var _ = ScratchBuffer.acquireScope()) {
      return BookmarkReader.readBookmarks(handle);
    }
  }

  public synchronized Optional<String> pageLabel(int index) {
    ensureOpen();
    try (var _ = ScratchBuffer.acquireScope()) {
      int needed =
          (int) ShimBindings.pdfium4j_page_label().invokeExact(handle, index, MemorySegment.NULL, 0);
      if (needed <= 1) return Optional.empty();
      MemorySegment buf = ScratchBuffer.get(needed);
      int copied = (int) ShimBindings.pdfium4j_page_label().invokeExact(handle, index, buf, needed);
      if (copied <= 1) return Optional.empty();
      return Optional.of(buf.reinterpret(needed).getString(0));
    } catch (Throwable _) {
      return Optional.empty();
    }
  }

  public synchronized List<PageSize> allPageSizes() {
    ensureOpen();
    int count = pageCount();
    if (count <= 0) return List.of();
    List<PageSize> sizes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      sizes.add(pageSize(i));
    }
    return sizes;
  }

  public synchronized int fileVersion() {
    ensureOpen();
    return sourceFileVersion;
  }

  static NoAllocationPathProbe noAllocationPathProbe(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    return new NoAllocationPathProbe(path.toAbsolutePath().normalize(), null);
  }

  public synchronized boolean hasValidCrossReferenceTable() {
    ensureOpen();
    if (ViewBindings.FPDF_DocumentHasValidCrossReferenceTable() == null) {
      return true;
    }
    try {
      return (int) ViewBindings.FPDF_DocumentHasValidCrossReferenceTable().invokeExact(handle) != 0;
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to inspect cross reference table", t);
    }
  }

  public synchronized int[] trailerEnds() {
    ensureOpen();
    if (ViewBindings.FPDF_GetTrailerEnds() == null) {
      return EMPTY_INT_ARRAY;
    }
    try (var _ = ScratchBuffer.acquireScope()) {
      int capacity = 8;
      while (true) {
        long byteSize = Math.multiplyExact((long) capacity, JAVA_INT.byteSize());
        MemorySegment buffer = ScratchBuffer.get(byteSize);
        long written =
            (long) ViewBindings.FPDF_GetTrailerEnds().invokeExact(handle, buffer, (long) capacity);
        if (written <= 0) {
          return EMPTY_INT_ARRAY;
        }
        if (written <= capacity) {
          long usedBytes = Math.multiplyExact(written, JAVA_INT.byteSize());
          return buffer.asSlice(0, usedBytes).toArray(JAVA_INT);
        }
        capacity = Math.toIntExact(written);
      }
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to inspect trailer ends", t);
    }
  }

  public synchronized boolean isImageOnly() {
    int count = pageCount();
    int sampleCount = Math.min(count, 10);
    for (int i = 0; i < sampleCount; i++) {
      try (PdfPage p = page(i)) {
        if (p.hasText()) return false;
      }
    }
    return true;
  }

  public synchronized Optional<String> metadata(MetadataTag tag) {
    String val = metadataString(tag);
    return (val == null || val.isEmpty()) ? Optional.empty() : Optional.of(val);
  }

  public synchronized String metadataString(MetadataTag tag) {
    ensureOpen();
    if (pendingMetadata.containsKey(tag)) {
      return pendingMetadata.get(tag);
    }
    try (var _ = ScratchBuffer.acquireScope()) {
      MethodHandle m = MetadataCache.META_HANDLES[tag.ordinal()];
      long needed = (long) m.invokeExact(handle, MemorySegment.NULL, 0L);
      if (needed <= 2) return metadataFallback(tag).orElse(null);

      MemorySegment buf =
          (needed <= 8192) ? ScratchBuffer.getMetadataBuffer() : ScratchBuffer.get(needed);
      long copied = (long) m.invokeExact(handle, buf, needed);
      if (copied <= 2) return metadataFallback(tag).orElse(null);

      return FfmHelper.fromWideString(buf, copied);
    } catch (Throwable t) {
      return metadataFallback(tag).orElse(null);
    }
  }

  /**
   * Builds a trigram-based full-text index of the entire document. This enables fast search()
   * operations but may take time for large documents.
   */
  public synchronized void indexText() {
    ensureOpen();
    Map<String, List<Integer>> tempIndex = new HashMap<>();
    int count = pageCount();

    for (int i = 0; i < count; i++) {
      try (PdfPage p = page(i)) {
        String text = p.extractText().toLowerCase();
        if (text.isEmpty()) continue;

        Set<String> trigrams = generateTrigrams(text);
        for (String trigram : trigrams) {
          tempIndex.computeIfAbsent(trigram, _ -> new ArrayList<>()).add(i);
        }
      }
    }

    // Convert to compact int arrays
    this.textIndex = new HashMap<>(tempIndex.size());
    for (Map.Entry<String, List<Integer>> entry : tempIndex.entrySet()) {
      textIndex.put(
          entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
    }
  }

  /**
   * Searches the document for the given query using the trigram index if available, otherwise
   * performs a linear scan.
   *
   * @param query the text to search for
   * @return a list of page indices containing the query
   */
  public synchronized List<Integer> search(String query) {
    ensureOpen();
    if (query == null || query.isEmpty()) return List.of();

    String normalized = query.toLowerCase();
    if (textIndex == null) {
      // Fallback to linear scan if not indexed
      List<Integer> results = new ArrayList<>();
      int count = pageCount();
      for (int i = 0; i < count; i++) {
        try (PdfPage p = page(i)) {
          if (p.extractText().toLowerCase().contains(normalized)) {
            results.add(i);
          }
        }
      }
      return results;
    }

    // Use trigram index to filter candidates
    Set<String> queryTrigrams = generateTrigrams(normalized);
    if (queryTrigrams.isEmpty()) {
      // Query too short for trigrams, fallback to linear scan
      return searchFallback(normalized);
    }

    List<int[]> candidates = new ArrayList<>();
    for (String tri : queryTrigrams) {
      int[] pages = textIndex.get(tri);
      if (pages == null) return List.of(); // No page has this trigram
      candidates.add(pages);
    }

    // Intersection of candidate page lists
    List<Integer> intersected = intersect(candidates);

    // Final verification (exact match)
    List<Integer> results = new ArrayList<>();
    for (int pageIdx : intersected) {
      try (PdfPage p = page(pageIdx)) {
        if (p.extractText().toLowerCase().contains(normalized)) {
          results.add(pageIdx);
        }
      }
    }
    return results;
  }

  private List<Integer> searchFallback(String normalized) {
    List<Integer> results = new ArrayList<>();
    int count = pageCount();
    for (int i = 0; i < count; i++) {
      try (PdfPage p = page(i)) {
        if (p.extractText().toLowerCase().contains(normalized)) {
          results.add(i);
        }
      }
    }
    return results;
  }

  private static Set<String> generateTrigrams(String text) {
    if (text.length() < 3) return Collections.emptySet();
    Set<String> trigrams = new HashSet<>();
    for (int i = 0; i <= text.length() - 3; i++) {
      trigrams.add(text.substring(i, i + 3));
    }
    return trigrams;
  }

  private static List<Integer> intersect(List<int[]> lists) {
    if (lists.isEmpty()) return List.of();

    // Sort lists by size to optimize intersection
    lists.sort(java.util.Comparator.comparingInt(a -> a.length));

    List<Integer> result = new ArrayList<>();
    int[] first = lists.getFirst();
    for (int val : first) {
      boolean presentInAll = true;
      for (int i = 1; i < lists.size(); i++) {
        if (java.util.Arrays.binarySearch(lists.get(i), val) < 0) {
          presentInAll = false;
          break;
        }
      }
      if (presentInAll) result.add(val);
    }
    return result;
  }

  int probeMetadataUtf16ByteLength() {
    ensureOpen();
    if (pendingMetadata.containsKey(MetadataTag.TITLE)) {
      return wideStringByteLength(pendingMetadata.get(MetadataTag.TITLE));
    }
    try {
      long needed =
          (long)
              DocBindings.FPDF_GetMetaText().invokeExact(
                  handle, metadataKeySegment(MetadataTag.TITLE), MemorySegment.NULL, 0L);
      return needed <= 0 ? 0 : Math.toIntExact(needed);
    } catch (Throwable t) {
      throw new PdfiumException("Failed to inspect metadata length for " + MetadataTag.TITLE, t);
    }
  }

  int readMetadataUtf16(MemorySegment buffer) {
    ensureOpen();
    if (buffer == null || FfmHelper.isNull(buffer)) {
      throw new IllegalArgumentException("buffer must not be null");
    }
    if (pendingMetadata.containsKey(MetadataTag.TITLE)) {
      return writeWideString(buffer, pendingMetadata.get(MetadataTag.TITLE));
    }
    long capacity = buffer.byteSize();
    if (capacity <= 0) {
      return 0;
    }
    try {
      long copied =
          (long)
              DocBindings.FPDF_GetMetaText().invokeExact(
                  handle, metadataKeySegment(MetadataTag.TITLE), buffer, capacity);
      if (copied <= 0) {
        return 0;
      }
      if (copied > capacity) {
        return Math.toIntExact(copied);
      }
      long byteLen = FfmHelper.normalizeWideByteLength(buffer, copied, capacity);
      return Math.toIntExact(byteLen);
    } catch (Throwable t) {
      throw new PdfiumException("Failed to read metadata for " + MetadataTag.TITLE, t);
    }
  }

  /**
   * Functional interface for consuming a memory segment with a specific length without allocating a
   * slice object.
   */
  @FunctionalInterface
  public interface MemorySegmentConsumer {
    /**
     * Consumes the given segment.
     *
     * @param segment the memory segment (may be larger than the actual data)
     * @param length the actual length of the valid data in the segment
     */
    void accept(MemorySegment segment, long length);
  }

  /**
   * Accesses metadata in a zero-allocation manner by yielding a memory segment and its actual
   * length to the consumer. The segment is valid only during the callback execution.
   *
   * @param tag the metadata tag to read
   * @param consumer a consumer that will receive the memory segment and the length of the UTF-16LE
   *     data
   */
  public void withMetadataUtf16(MetadataTag tag, MemorySegmentConsumer consumer) {
    ensureOpen();
    if (consumer == null) {
      throw new IllegalArgumentException("consumer must not be null");
    }

    // Check pending metadata first
    if (pendingMetadata.containsKey(tag)) {
      String pending = pendingMetadata.get(tag);
      if (pending == null || pending.isEmpty()) return;
      try (var _ = ScratchBuffer.acquireScope()) {
        int needed = wideStringByteLength(pending);
        MemorySegment buf = ScratchBuffer.get(needed);
        writeWideString(buf, pending);
        consumer.accept(buf, needed);
      }
      return;
    }

    try (var _ = ScratchBuffer.acquireScope()) {
      long needed =
          (long)
              DocBindings.FPDF_GetMetaText().invokeExact(
                  handle, metadataKeySegment(tag), MemorySegment.NULL, 0L);
      if (needed <= 2) return;

      MemorySegment buf = ScratchBuffer.get(needed);
      long copied =
          (long)
              DocBindings.FPDF_GetMetaText().invokeExact(
                  handle, metadataKeySegment(tag), buf, needed);
      long byteLen = FfmHelper.normalizeWideByteLength(buf, copied, needed);
      if (byteLen > 0) {
        consumer.accept(buf, byteLen);
      }
    } catch (Throwable t) {
      throw new PdfiumException("Failed to read metadata for " + tag, t);
    }
  }

  /**
   * Returns a zero-allocation InputStream for the given metadata tag (UTF-16LE). The stream MUST be
   * closed to release resources.
   */
  public InputStream metadataStream(MetadataTag tag) {
    ensureOpen();
    /*
        if (pendingMetadata.containsKey(tag)) {
          String pending = pendingMetadata.get(tag);
          if (pending == null || pending.isEmpty()) return InputStream.nullInputStream();
          int needed = wideStringByteLength(pending);
          ScratchBuffer.acquire();
          try {
            MemorySegment buf = ScratchBuffer.get(needed);
            writeWideString(buf, pending);
            return ScratchBuffer.wrap(buf, needed);
          } finally {
            ScratchBuffer.release();
          }
        }
    */

    try {
      long needed =
          (long)
              DocBindings.FPDF_GetMetaText().invokeExact(
                  handle, metadataKeySegment(tag), MemorySegment.NULL, 0L);
      if (needed <= 2) return InputStream.nullInputStream();

      ScratchBuffer.acquire();
      try {
        MemorySegment buf = ScratchBuffer.get(needed);
        long copied =
            (long)
                DocBindings.FPDF_GetMetaText().invokeExact(
                    handle, metadataKeySegment(tag), buf, needed);
        long byteLen = FfmHelper.normalizeWideByteLength(buf, copied, needed);
        if (byteLen <= 0) return InputStream.nullInputStream();
        return ScratchBuffer.wrap(buf, byteLen);
      } finally {
        ScratchBuffer.release();
      }
    } catch (Throwable t) {
      PdfiumLibrary.ignore(t);
      return InputStream.nullInputStream();
    }
  }

  private Optional<String> metadataFallback(MetadataTag tag) {
    Map<String, String> cache = getOrBuildFallbackMeta();
    String val = cache.get(tag.pdfKey());
    return (val == null || val.isEmpty()) ? Optional.empty() : Optional.of(val);
  }

  /**
   * Returns (and lazily builds) the per-document Info-dict cache. The file is mapped at most once
   * regardless of how many metadata tags are requested.
   */
  private Map<String, String> getOrBuildFallbackMeta() {
    if (cachedFallbackMeta != null) return cachedFallbackMeta;
    Map<String, String> result;
    if (sourceBytes != null) {
      result = parseAllInfoMetadata(MemorySegment.ofArray(sourceBytes));
    } else if (sourcePath != null) {
      try (FileChannel fc = FileChannel.open(sourcePath, StandardOpenOption.READ);
          Arena arena = Arena.ofConfined()) {
        long fileSize = fc.size();
        if (fileSize <= 0) {
          result = Map.of();
        } else {
          long tailSize = Math.min(fileSize, FALLBACK_TAIL_SCAN_BYTES);
          long tailStart = fileSize - tailSize;
          MemorySegment tail = fc.map(FileChannel.MapMode.READ_ONLY, tailStart, tailSize, arena);
          result = parseAllInfoMetadata(tail);
          // Keep behavior robust: if tail miss occurs, retry with full-file map once.
          if (result.isEmpty() && tailStart > 0) {
            MemorySegment full = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
            result = parseAllInfoMetadata(full);
          }
        }
      } catch (IOException e) {
        PdfiumLibrary.ignore(e);
        result = Map.of();
      }
    } else {
      result = Map.of();
    }
    cachedFallbackMeta = result;
    return result;
  }

  /**
   * Parses ALL key-value entries from the PDF Info dictionary in one pass. Avoids per-tag
   * re-mapping and repeated Pattern.compile() calls.
   */
  private static Map<String, String> parseAllInfoMetadata(MemorySegment pdf) {
    long size = pdf.byteSize();
    long tailLen = Math.min(size, 4096);
    if (tailLen == 0) return Map.of();

    long infoPos = findLastInfoKey(pdf);
    if (infoPos < 0) return Map.of();

    long valStart = skipAsciiWhitespace(pdf, infoPos + INFO_KEY.length, size);
    long n1End = scanDigits(pdf, valStart, size);
    if (n1End <= valStart) return Map.of();
    long n2Start = skipAsciiWhitespace(pdf, n1End, size);
    long n2End = scanDigits(pdf, n2Start, size);
    if (n2End <= n2Start) return Map.of();

    int objNum = parsePositiveInt(pdf, valStart, n1End);
    int genNum = parsePositiveInt(pdf, n2Start, n2End);

    return extractDictAndParseInfo(pdf, objNum, genNum);
  }

  private static long findLastInfoKey(MemorySegment pdf) {
    // Search in the last 1KB for the /Info key in a dictionary.
    // The pattern is usually /Info \d+ \d+ R
    long size = pdf.byteSize();
    long start = Math.max(0, size - 4096);
    byte[] infoMarker = "/Info".getBytes(StandardCharsets.ISO_8859_1);
    long pos = size;
    while (pos > start) {
      pos = lastIndexOf(pdf, infoMarker, pos);
      if (pos < 0 || pos < start) break;

      // Verify it's followed by a reference
      long valStart = skipAsciiWhitespace(pdf, pos + infoMarker.length, size);
      long n1End = scanDigits(pdf, valStart, size);
      if (n1End > valStart) {
        long n2Start = skipAsciiWhitespace(pdf, n1End, size);
        long n2End = scanDigits(pdf, n2Start, size);
        if (n2End > n2Start) {
          long rPos = skipAsciiWhitespace(pdf, n2End, size);
          if (rPos < size && pdf.get(JAVA_BYTE, rPos) == 'R') {
            return pos;
          }
        }
      }
      pos--;
    }
    return -1;
  }

  private static Map<String, String> extractDictAndParseInfo(
      MemorySegment pdf, int objNum, int genNum) {
    long pos = findObjectHeader(pdf, objNum, genNum);
    if (pos < 0) return Map.of();

    long dictStart = indexOf(pdf, DICT_START, pos);
    if (dictStart < 0) return Map.of();

    long dictEnd = findDictionaryEnd(pdf, dictStart);
    if (dictEnd < 0) return Map.of();

    Map<String, String> result = LinkedHashMap.newLinkedHashMap(16);
    long scanPos = dictStart + DICT_START.length;
    while (scanPos < dictEnd - 1) {
      scanPos = skipAsciiWhitespace(pdf, scanPos, dictEnd);
      if (scanPos >= dictEnd - 1) break;
      if (pdf.get(JAVA_BYTE, scanPos) != '/') {
        scanPos++;
        continue;
      }
      long keyStart = scanPos + 1;
      long keyEnd = keyStart;
      while (keyEnd < dictEnd && !isPdfDelimiter(pdf.get(JAVA_BYTE, keyEnd))) keyEnd++;
      if (keyEnd == keyStart) {
        scanPos++;
        continue;
      }
      String key =
          new String(
              pdf.asSlice(keyStart, keyEnd - keyStart).toArray(JAVA_BYTE),
              StandardCharsets.ISO_8859_1);
      scanPos = skipAsciiWhitespace(pdf, keyEnd, dictEnd);
      if (scanPos >= dictEnd) break;

      byte valType = pdf.get(JAVA_BYTE, scanPos);
      if (valType == '(') {
        long valStart = scanPos + 1;
        long valEnd = findClosingParen(pdf, valStart, dictEnd);
        if (valEnd >= 0) {
          result.put(
              key,
              new String(
                  pdf.asSlice(valStart, valEnd - valStart).toArray(JAVA_BYTE),
                  StandardCharsets.ISO_8859_1));
          scanPos = valEnd + 1;
        } else {
          scanPos++;
        }
      } else if (valType == '<') {
        long valStart = scanPos + 1;
        long valEnd = indexOf(pdf, new byte[] {'>'}, valStart);
        if (valEnd >= 0) {
          String hex =
              new String(
                  pdf.asSlice(valStart, valEnd - valStart).toArray(JAVA_BYTE),
                  StandardCharsets.ISO_8859_1);
          result.put(key, decodeHexPdfString(hex));
          scanPos = valEnd + 1;
        } else {
          scanPos++;
        }
      } else {
        scanPos++;
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private static long findObjectHeader(MemorySegment pdf, int objNum, int genNum) {
    byte[] marker = (objNum + " " + genNum + " obj").getBytes(StandardCharsets.ISO_8859_1);
    return lastIndexOf(pdf, marker);
  }

  private static long findDictionaryEnd(MemorySegment pdf, long start) {
    int depth = 0;
    long curr = start;
    long size = pdf.byteSize();
    while (curr < size - 1) {
      byte b1 = pdf.get(JAVA_BYTE, curr);
      byte b2 = pdf.get(JAVA_BYTE, curr + 1);
      if (b1 == '<' && b2 == '<') {
        depth++;
        curr += 2;
      } else if (b1 == '>' && b2 == '>') {
        depth--;
        if (depth == 0) return curr + 2;
        curr += 2;
      } else {
        curr++;
      }
    }
    return -1;
  }

  private static long findClosingParen(MemorySegment pdf, long start, long limit) {
    int depth = 1;
    for (long i = start; i < limit; i++) {
      byte b = pdf.get(JAVA_BYTE, i);
      if (b == '(') depth++;
      else if (b == ')') {
        depth--;
        if (depth == 0) return i;
      } else if (b == '\\') {
        i++; // skip escaped char
      }
    }
    return -1;
  }

  private static boolean isPdfDelimiter(byte b) {
    return b == '(' || b == ')' || b == '<' || b == '>' || b == '[' || b == ']' || b == '{'
        || b == '}' || b == '/' || b == '%' || isWs(b);
  }

  private static boolean isWs(byte b) {
    return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0;
  }

  private static long skipAsciiWhitespace(MemorySegment seg, long offset, long limit) {
    while (offset < limit && isWs(seg.get(JAVA_BYTE, offset))) offset++;
    return offset;
  }

  private static long scanDigits(MemorySegment seg, long offset, long limit) {
    while (offset < limit) {
      byte b = seg.get(JAVA_BYTE, offset);
      if (b < '0' || b > '9') break;
      offset++;
    }
    return offset;
  }

  private static int parsePositiveInt(MemorySegment seg, long start, long end) {
    int res = 0;
    for (long i = start; i < end; i++) {
      res = res * 10 + (seg.get(JAVA_BYTE, i) - '0');
    }
    return res;
  }

  private static long lastIndexOf(MemorySegment segment, byte[] needle) {
    return lastIndexOf(segment, needle, segment.byteSize());
  }

  private static long lastIndexOf(MemorySegment segment, byte[] needle, long from) {
    long size = segment.byteSize();
    long start = Math.min(from, size - needle.length);
    outer:
    for (long i = start; i >= 0; i--) {
      for (int j = 0; j < needle.length; j++) {
        if (segment.get(JAVA_BYTE, i + j) != needle[j]) continue outer;
      }
      return i;
    }
    return -1;
  }

  private static long indexOf(MemorySegment segment, byte[] needle, long fromIndex) {
    long size = segment.byteSize();
    outer:
    for (long i = fromIndex; i <= size - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (segment.get(JAVA_BYTE, i + j) != needle[j]) continue outer;
      }
      return i;
    }
    return -1;
  }

  private static String decodeHexPdfString(String hex) {
    if (hex.startsWith("FEFF")) {
      // UTF-16BE
      try {
        int len = (hex.length() - 4) / 2;
        if (len <= 0) return "";
        byte[] bytes = new byte[len];
        for (int i = 0; i < bytes.length; i++) {
          bytes[i] = (byte) Integer.parseInt(hex.substring(4 + i * 2, 6 + i * 2), 16);
        }
        return new String(bytes, StandardCharsets.UTF_16BE);
      } catch (Exception e) {
        return hex;
      }
    }
    return hex; // Raw hex fallback
  }

  public Optional<String> metadata(String customKey) {
    // Check known enum-backed tags first
    for (MetadataTag tag : METADATA_TAGS) {
      if (tag.pdfKey().equalsIgnoreCase(customKey)) return metadata(tag);
    }
    // Support arbitrary /Info keys (e.g. /Language) via FPDF_GetMetaText
    ensureOpen();
    Optional<String> val = tryGetMetaText(customKey);
    if (val.isPresent()) {
      return val;
    }
    return findMetadataInFallback(customKey);
  }

  private Optional<String> tryGetMetaText(String customKey) {
    try (var _ = ScratchBuffer.acquireScope()) {
      MemorySegment keySeg = ScratchBuffer.getUtf8(customKey);
      int needed =
          (int)
              ShimBindings.pdfium4j_get_meta_utf8().invokeExact(
                  handle, keySeg, MemorySegment.NULL, 0);
      if (needed <= 1) return Optional.empty();

      MemorySegment valSeg = ScratchBuffer.get(needed);
      int copied =
          (int) ShimBindings.pdfium4j_get_meta_utf8().invokeExact(handle, keySeg, valSeg, needed);
      if (copied <= 1) return Optional.empty();

      String val = valSeg.reinterpret(needed).getString(0);
      return (val == null || val.isEmpty()) ? Optional.empty() : Optional.of(val);
    } catch (Throwable _) {
      return Optional.empty();
    }
  }

  private Optional<String> findMetadataInFallback(String customKey) {
    Map<String, String> fallback = getOrBuildFallbackMeta();
    for (Map.Entry<String, String> entry : fallback.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(customKey)) {
        String v = entry.getValue();
        return (v == null || v.isEmpty()) ? Optional.empty() : Optional.of(v);
      }
    }
    return Optional.empty();
  }

  public Map<String, String> metadata() {
    Map<String, String> meta = LinkedHashMap.newLinkedHashMap(METADATA_TAGS.length);
    for (MetadataTag tag : METADATA_TAGS) metadata(tag).ifPresent(v -> meta.put(tag.name(), v));
    return meta;
  }

  public String xmpMetadataString() {
    return new String(xmpMetadata(), StandardCharsets.UTF_8);
  }

  /**
   * Returns a zero-allocation InputStream for the document's XMP metadata (UTF-8). The stream MUST
   * be closed to release resources.
   */
  public InputStream xmpMetadataStream() {
    ensureOpen();
    if (pendingXmp != null) {
      if (pendingXmp instanceof XmpUpdate.Raw(String xmp)) {
        long len = FfmHelper.utf8ByteLength(xmp);
        MemorySegment buf = ScratchBuffer.get(len);
        FfmHelper.writeUtf8StringNoNull(buf, xmp);
        return ScratchBuffer.wrap(buf, len);
      }
      if (pendingXmp instanceof XmpUpdate.Structured(XmpMetadata metadata)) {
        ScratchBuffer.acquire();
        try {
          MemorySegment buf = ScratchBuffer.get(64 * 1024);
          SegmentOutputStream sos = new SegmentOutputStream(buf);
          XMP_WRITER.write(metadata, sos);
          return ScratchBuffer.wrap(sos.segment(), sos.size());
        } catch (Throwable t) {
          ScratchBuffer.release();
          throw (t instanceof PdfiumException pe)
              ? pe
              : new PdfiumException("Failed to serialize pending XMP", t);
        }
      }
    }
    try {
      int needed =
          (int) ShimBindings.pdfium4j_get_xmp_metadata().invokeExact(handle, MemorySegment.NULL, 0);
      if (needed <= 0) return InputStream.nullInputStream();

      MemorySegment buf = ScratchBuffer.get(needed);
      int copied = (int) ShimBindings.pdfium4j_get_xmp_metadata().invokeExact(handle, buf, needed);
      if (copied <= 0) return InputStream.nullInputStream();

      return ScratchBuffer.wrap(buf, Math.min(copied, needed));
    } catch (Throwable t) {
      PdfiumLibrary.ignore(t);
      return InputStream.nullInputStream();
    }
  }

  public byte[] xmpMetadata() {
    ensureOpen();
    if (pendingXmp != null) {
      if (pendingXmp instanceof XmpUpdate.Raw(String xmp)) {
        return xmp.getBytes(StandardCharsets.UTF_8);
      }
      if (pendingXmp instanceof XmpUpdate.Structured(XmpMetadata metadata)) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        XMP_WRITER.write(metadata, baos);
        return baos.toByteArray();
      }
    }
    try (var _ = ScratchBuffer.acquireScope()) {
      int needed =
          (int) ShimBindings.pdfium4j_get_xmp_metadata().invokeExact(handle, MemorySegment.NULL, 0);
      if (needed > 0) {
        MemorySegment buf = ScratchBuffer.get(needed);
        int copied = (int) ShimBindings.pdfium4j_get_xmp_metadata().invokeExact(handle, buf, needed);
        if (copied > 0) return buf.asSlice(0, Math.min(copied, needed)).toArray(JAVA_BYTE);
      }
    } catch (Throwable e) {
      PdfiumLibrary.ignore(e);
    }
    // Fallback path: memoize so repeated calls never re-map the file.
    if (cachedFallbackXmp != null) return cachedFallbackXmp.clone();
    byte[] result = computeFallbackXmp();
    cachedFallbackXmp = result;
    return result == null ? null : result.clone();
  }

  private byte[] computeFallbackXmp() {
    if (sourceBytes != null) {
      return extractXmpFromSegment(MemorySegment.ofArray(sourceBytes));
    }
    if (sourcePath != null) {
      try (FileChannel fc = FileChannel.open(sourcePath, StandardOpenOption.READ);
          Arena arena = Arena.ofConfined()) {
        long fileSize = fc.size();
        if (fileSize <= 0) return EMPTY_BYTE_ARRAY;
        long tailSize = Math.min(fileSize, FALLBACK_TAIL_SCAN_BYTES);
        long tailStart = fileSize - tailSize;
        MemorySegment tail = fc.map(FileChannel.MapMode.READ_ONLY, tailStart, tailSize, arena);
        byte[] xmp = extractXmpFromSegment(tail);
        if (xmp.length > 0 || tailStart == 0) return xmp;
        // Fallback for uncommon PDFs where the packet is not in the tail window.
        MemorySegment full = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
        return extractXmpFromSegment(full);
      } catch (IOException e) {
        PdfiumLibrary.ignore(e);
      }
    }
    return EMPTY_BYTE_ARRAY;
  }

  private static byte[] extractXmpFromSegment(MemorySegment pdf) {
    long start = lastIndexOf(pdf, XMP_START);
    if (start < 0) return EMPTY_BYTE_ARRAY;

    long end = indexOf(pdf, XMP_END, start);
    if (end < 0) return EMPTY_BYTE_ARRAY;

    long term = indexOf(pdf, XMP_TERM, end);
    if (term < 0) return EMPTY_BYTE_ARRAY;

    return pdf.asSlice(start, term + 2 - start).toArray(JAVA_BYTE);
  }

  public void setMetadata(MetadataTag tag, String value) {
    ensureOpen();
    pendingMetadata.put(tag, value);
    try (var scope = ScratchBuffer.acquireScope()) {
      PdfiumLibrary.ignore(scope);
      MemorySegment keySeg = ScratchBuffer.getUtf8(tag.pdfKey());
      MemorySegment valSeg = ScratchBuffer.getUtf8(value);
      ShimBindings.pdfium4j_set_meta_utf8().invokeExact(handle, keySeg, valSeg);
    } catch (Throwable e) {
      PdfiumLibrary.ignore(e);
    }
  }

  public void setMetadata(Map<MetadataTag, String> metadata) {
    metadata.forEach(this::setMetadata);
  }

  public void setXmpMetadata(String xmp) {
    ensureOpen();
    pendingXmp = (xmp == null || xmp.isBlank()) ? null : new XmpUpdate.Raw(xmp);
  }

  public void setXmpMetadata(XmpMetadata metadata) {
    ensureOpen();
    pendingXmp = new XmpUpdate.Structured(metadata);
  }

  public void insertBlankPage(int index, PageSize size) {
    ensureOpen();
    try {
      MemorySegment p =
          (MemorySegment)
              EditBindings.FPDFPage_New().invokeExact(
                  handle, index, (double) size.width(), (double) size.height());
      if (FfmHelper.isNull(p)) throwLastError("Failed to insert page");
      ViewBindings.FPDF_ClosePage().invokeExact(p);
      markStructurallyModified();
    } catch (Throwable t) {
      throw new PdfiumException("Failed to insert page", t);
    }
  }

  public void deletePage(int index) {
    ensureOpen();
    if (index < 0 || index >= pageCount())
      throw new IllegalArgumentException("Index out of bounds");
    try {
      DocBindings.FPDFPage_Delete().invokeExact(handle, index);
      markStructurallyModified();
    } catch (Throwable t) {
      throw new PdfiumException("Failed to delete page", t);
    }
  }

  public void importPages(PdfDocument src, String range, int index) {
    ensureOpen();
    try {
      MemorySegment r = (range != null) ? docArena.allocateFrom(range) : MemorySegment.NULL;
      int ok = (int) EditBindings.FPDF_ImportPages().invokeExact(handle, src.handle, r, index);
      if (ok == 0) throwLastError("Failed to import pages");
      markStructurallyModified();
    } catch (Throwable t) {
      throw new PdfiumException("Failed to import pages", t);
    }
  }

  public void importAllPages(PdfDocument src) {
    importPages(src, null, pageCount());
  }

  public byte[] renderPageToBytes(int index, int dpi, String format) {
    try (PdfPage p = page(index)) {
      RenderResult res = p.render(dpi);
      if ("png".equalsIgnoreCase(format)) return res.toPngBytes();
      if ("jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format))
        return res.toJpegBytes();
      throw new IllegalArgumentException("Unsupported format: " + format);
    }
  }

  public void save(Path path) {
    ensureOpen();
    if (path.equals(sourcePath)) {
      saveToSourcePath(path);
    } else {
      saveToNewPath(path);
    }
  }

  private void saveToSourcePath(Path path) {
    Path temp = null;
    boolean detachedSource = false;
    try {
      temp = IoUtils.createTempFile("pdfium4j-save-", ".pdf");
      try (OutputStream out = Files.newOutputStream(temp)) {
        save(out, true);
      }

      if (docSourceChannel != null) {
        docSourceChannel.close();
        docSourceChannel = null;
        CHANNELS.remove(channelId);
        state.updateSourceChannel(null);
        detachedSource = true;
      }
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      temp = null;
      if (channelId > 0) {
        docSourceChannel = Files.newByteChannel(path, StandardOpenOption.READ);
        CHANNELS.put(channelId, docSourceChannel);
        state.updateSourceChannel(docSourceChannel);
      }
    } catch (IOException e) {
      handleSaveError(path, e, detachedSource);
    } finally {
      cleanupTempFile(temp);
    }
  }

  private void handleSaveError(Path path, IOException e, boolean detachedSource) {
    if (detachedSource && channelId > 0 && docSourceChannel == null) {
      try {
        docSourceChannel = Files.newByteChannel(path, StandardOpenOption.READ);
        CHANNELS.put(channelId, docSourceChannel);
        state.updateSourceChannel(docSourceChannel);
      } catch (IOException restoreEx) {
        PdfiumLibrary.ignore(restoreEx);
      }
    }
    throw new PdfiumException("Failed to save to source path: " + path, e);
  }

  private static void cleanupTempFile(Path temp) {
    if (temp == null) {
      return;
    }
    try {
      Files.deleteIfExists(temp);
    } catch (IOException e) {
      PdfiumLibrary.ignore(e);
    }
  }

  private void saveToNewPath(Path path) {
    try (OutputStream out = Files.newOutputStream(path)) {
      save(out, true);
    } catch (IOException e) {
      throw new PdfiumException("Failed to save to " + path, e);
    }
  }

  public void save(OutputStream out) {
    save(out, true);
  }

  private void save(OutputStream out, boolean allowIncrementalOutput) {
    ensureOpen();
    try {
      if (pendingXmp == null && pendingMetadata.isEmpty() && !structurallyModified) {
        PdfSaver.saveNativeFullRewrite(handle, out, sourceFileVersion);
      } else {
        PdfSaver.SaveParams params =
            new PdfSaver.SaveParams(
                handle,
                pendingMetadata,
                nativeMetadataProvider,
                !pendingMetadata.isEmpty(),
                pendingXmp,
                docSourceChannel,
                sourcePath,
                sourceBytes,
                sourceSegment,
                structurallyModified,
                out,
                allowIncrementalOutput,
                false,
                EditBindings.FPDF_NO_INCREMENTAL,
                sourceFileVersion);
        PdfSaver.save(params);
      }
    } catch (IOException e) {
      throw new PdfiumException("Failed to save document", e);
    }
  }

  public byte[] saveToBytes() {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    save(bos, true);
    return bos.toByteArray();
  }

  public List<PdfAttachment> attachments() {
    ensureOpen();
    if (AttachmentBindings.FPDFDoc_GetAttachmentCount() == null) {
      return Collections.emptyList();
    }
    try {
      int count = (int) AttachmentBindings.FPDFDoc_GetAttachmentCount().invokeExact(handle);
      if (count <= 0) return Collections.emptyList();

      List<PdfAttachment> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        MemorySegment attachment =
            (MemorySegment) AttachmentBindings.FPDFDoc_GetAttachment().invokeExact(handle, i);
        if (attachment == null || attachment.equals(MemorySegment.NULL)) continue;

        String name =
            readAttachmentString(attachment, AttachmentBindings.FPDFAttachment_GetName())
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
      throw new PdfiumException("Failed to read attachments", t);
    }
  }

  public List<PdfSignature> signatures() {
    ensureOpen();
    if (SignatureBindings.FPDF_GetSignatureCount() == null) {
      return Collections.emptyList();
    }
    try {
      int count = (int) SignatureBindings.FPDF_GetSignatureCount().invokeExact(handle);
      if (count <= 0) return Collections.emptyList();

      List<PdfSignature> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        MemorySegment sig =
            (MemorySegment) SignatureBindings.FPDF_GetSignatureObject().invokeExact(handle, i);
        if (FfmHelper.isNull(sig)) continue;

        result.add(
            new PdfSignature(
                i,
                getSignatureString(sig, SignatureBindings.FPDFSignatureObj_GetReason()),
                getSignatureTime(sig),
                getSignatureString(sig, SignatureBindings.FPDFSignatureObj_GetSubFilter()),
                getSignatureLong(sig, SignatureBindings.FPDFSignatureObj_GetContents()),
                getSignatureLong(sig, SignatureBindings.FPDFSignatureObj_GetByteRange())));
      }
      return Collections.unmodifiableList(result);
    } catch (Throwable t) {
      throw new PdfiumException("Failed to read signatures", t);
    }
  }

  private static Optional<String> getSignatureString(MemorySegment sig, MethodHandle getter) {
    try (var scope = ScratchBuffer.acquireScope()) {
      PdfiumLibrary.ignore(scope);
      long needed = (long) getter.invokeExact(sig, MemorySegment.NULL, 0L);
      if (needed <= 2) return Optional.empty();
      MemorySegment buf = ScratchBuffer.get(needed);
      long copied = (long) getter.invokeExact(sig, buf, needed);
      return Optional.of(FfmHelper.fromWideString(buf, copied));
    } catch (Throwable t) {
      return Optional.empty();
    }
  }

  private static long getSignatureLong(MemorySegment sig, MethodHandle getter) {
    try {
      return (long) getter.invokeExact(sig, MemorySegment.NULL, 0L);
    } catch (Throwable t) {
      return 0;
    }
  }

  private static Optional<Instant> getSignatureTime(MemorySegment sig) {
    if (SignatureBindings.FPDFSignatureObj_GetTime() == null) return Optional.empty();
    try (var scope = ScratchBuffer.acquireScope()) {
      PdfiumLibrary.ignore(scope);
      try {
        long needed =
            (long)
                SignatureBindings.FPDFSignatureObj_GetTime().invokeExact(sig, MemorySegment.NULL, 0L);
        if (needed <= 0) return Optional.empty();
        MemorySegment buf = ScratchBuffer.get(needed);
        SignatureBindings.FPDFSignatureObj_GetTime().invokeExact(sig, buf, needed);
        String timeStr = FfmHelper.fromWideString(buf, needed);
        return PdfDateUtils.parse(timeStr).map(OffsetDateTime::toInstant);
      } catch (Throwable t) {
        return Optional.empty();
      }
    }
  }

  private static Optional<String> getAttachmentMetadata(MemorySegment attachment, String key) {
    return readAttachmentString(attachment, key);
  }

  private static long getAttachmentFileSize(MemorySegment attachment) throws Throwable {
    return (long)
        AttachmentBindings.FPDFAttachment_GetFile().invokeExact(
            attachment, MemorySegment.NULL, 0L, MemorySegment.NULL);
  }

  private static Optional<String> readAttachmentString(MemorySegment handle, MethodHandle getter) {
    try (var _ = ScratchBuffer.acquireScope()) {
      long needed = (long) getter.invokeExact(handle, MemorySegment.NULL, 0L);
      if (needed <= 2) return Optional.empty();
      MemorySegment buf = ScratchBuffer.get(needed);
      long copied = (long) getter.invokeExact(handle, buf, needed);
      return Optional.of(FfmHelper.fromWideString(buf, (long) copied));
    } catch (Throwable t) {
      return Optional.empty();
    }
  }

  private static Optional<String> readAttachmentString(MemorySegment handle, String key) {
    try (var scope = ScratchBuffer.acquireScope()) {
      PdfiumLibrary.ignore(scope);
      MemorySegment keySeg = ScratchBuffer.getUtf8(key);
      try {
        long needed =
            (long)
                AttachmentBindings.FPDFAttachment_GetStringValue().invokeExact(
                    handle, keySeg, MemorySegment.NULL, 0L);
        if (needed <= 2) return Optional.empty();
        MemorySegment buf = ScratchBuffer.get(needed);
        long copied =
            (long)
                AttachmentBindings.FPDFAttachment_GetStringValue().invokeExact(
                    handle, keySeg, buf, needed);
        return Optional.of(FfmHelper.fromWideString(buf, (long) copied));
      } catch (Throwable t) {
        return Optional.empty();
      }
    }
  }

  @Override
  public synchronized void close() {
    ensureThreadConfinement();
    if (closed) return;
    closed = true;
    try {
      List<PdfPage> snapshot;
      synchronized (openPages) {
        snapshot = new ArrayList<>(openPages);
        openPages.clear();
      }
      for (PdfPage pdfPage : snapshot) {
        pdfPage.closeFromDocument();
      }
    } catch (Throwable e) {
      PdfiumLibrary.ignore(e);
    } finally {
      cleanable.clean();
    }
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("PdfDocument is already closed");
    ensureThreadConfinement();
  }

  private void ensureThreadConfinement() {
    if (Thread.currentThread() != ownerThread)
      throw new IllegalStateException("Thread confinement violation");
  }

  private void registerPage(PdfPage page) {
    synchronized (openPages) {
      openPages.add(page);
    }
  }

  private void unregisterPage(PdfPage page) {
    synchronized (openPages) {
      openPages.remove(page);
    }
  }

  private static PdfProcessingPolicy resolvePolicy(PdfProcessingPolicy policy) {
    return policy != null ? policy : PdfProcessingPolicy.defaultPolicy();
  }

  private static void throwLastError(String message) {
    int err;
    try {
      err = (int) (long) ViewBindings.FPDF_GetLastError().invokeExact();
    } catch (Throwable t) {
      err = 0;
    }
    throw mapOpenError(message, err);
  }

  public static PdfDiagnostic diagnose(Path path) {
    if (path == null)
      return new PdfDiagnostic(null, false, -1, false, false, 0, List.of("Null path"));
    PdfProbeResult pr = probe(path);
    return new PdfDiagnostic(
        path.toString(), pr.isValid(), pr.pageCount(), pr.needsPassword(), false, 0, List.of());
  }

  public static void repair(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    repairInPlace(path);
  }

  private static void repairInPlace(Path path) {
    Path temp = null;
    try {
      temp = IoUtils.createTempFile("pdfium4j-repair-", ".pdf");
      try (OutputStream out = Files.newOutputStream(temp)) {
        PdfSaver.repair(path, out);
      }
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      temp = null;
    } catch (IOException e) {
      throw new PdfiumException("Failed to repair document: " + path, e);
    } finally {
      cleanupTempFile(temp);
    }
  }

  private Map<MetadataTag, String> buildMergedMetadata() {
    Map<MetadataTag, String> merged = LinkedHashMap.newLinkedHashMap(METADATA_TAGS.length);
    for (MetadataTag tag : METADATA_TAGS) {
      if (!pendingMetadata.containsKey(tag)) metadata(tag).ifPresent(v -> merged.put(tag, v));
    }
    merged.putAll(pendingMetadata);
    return merged;
  }

  private static int readFileVersion(MemorySegment doc) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment version = arena.allocate(JAVA_INT.byteSize(), JAVA_INT.byteAlignment());
      int ok = (int) DocBindings.FPDF_GetFileVersion().invokeExact(doc, version);
      return ok != 0 ? version.get(JAVA_INT, 0) : 0;
    } catch (Throwable _) {
      return 0;
    }
  }

  public static PdfProbeResult probe(Path path) {
    return probe(path, resolvePolicy(null));
  }

  public static PdfProbeResult probe(Path path, PdfProcessingPolicy policy) {
    if (path == null)
      return PdfProbeResult.error(PdfProbeResult.Status.UNREADABLE, PdfErrorCode.FILE, "Null path");
    PdfiumLibrary.ensureInitialized();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment pathSeg = arena.allocateFrom(path.toString());
      MemorySegment doc =
          (MemorySegment) ViewBindings.FPDF_LoadDocument().invokeExact(pathSeg, MemorySegment.NULL);
      if (FfmHelper.isNull(doc)) {
        int err = (int) (long) ViewBindings.FPDF_GetLastError().invokeExact();
        if (err == ViewBindings.FPDF_ERR_PASSWORD) return PdfProbeResult.ok(-1, true);
        return PdfProbeResult.error(
            PdfProbeResult.Status.CORRUPT, PdfErrorCode.fromCode(err), "Failed to probe document");
      }
      int count = (int) ViewBindings.FPDF_GetPageCount().invokeExact(doc);
      ViewBindings.FPDF_CloseDocument().invokeExact(doc);
      return PdfProbeResult.ok(count, false);
    } catch (Throwable t) {
      return PdfProbeResult.error(
          PdfProbeResult.Status.CORRUPT, PdfErrorCode.FORMAT, t.getMessage());
    }
  }

  public static PdfProbeResult probe(byte[] data) {
    return probe(data, resolvePolicy(null));
  }

  public static PdfProbeResult probe(byte[] data, PdfProcessingPolicy policy) {
    if (data == null || data.length == 0)
      return PdfProbeResult.error(
          PdfProbeResult.Status.UNREADABLE, PdfErrorCode.FILE, "Empty data");
    PdfiumLibrary.ensureInitialized();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment seg = arena.allocateFrom(JAVA_BYTE, data);
      MemorySegment doc =
          (MemorySegment)
              ViewBindings.FPDF_LoadMemDocument().invokeExact(seg, data.length, MemorySegment.NULL);
      if (FfmHelper.isNull(doc)) {
        int err = (int) (long) ViewBindings.FPDF_GetLastError().invokeExact();
        if (err == ViewBindings.FPDF_ERR_PASSWORD) return PdfProbeResult.ok(-1, true);
        return PdfProbeResult.error(
            PdfProbeResult.Status.CORRUPT, PdfErrorCode.fromCode(err), "Failed to probe document");
      }
      int count = (int) ViewBindings.FPDF_GetPageCount().invokeExact(doc);
      ViewBindings.FPDF_CloseDocument().invokeExact(doc);
      return PdfProbeResult.ok(count, false);
    } catch (Throwable t) {
      return PdfProbeResult.error(
          PdfProbeResult.Status.CORRUPT, PdfErrorCode.FORMAT, t.getMessage());
    }
  }

  public static Optional<String> koReaderPartialMd5(byte[] data) {
    return KoReaderChecksum.calculate(data);
  }

  public static Optional<String> koReaderPartialMd5(Path path) {
    return KoReaderChecksum.calculate(path);
  }

  public static PdfiumException mapOpenError(String ctx, int code) {
    PdfErrorCode ec = PdfErrorCode.fromCode(code);
    return switch (ec) {
      case PASSWORD -> new PdfPasswordException(ctx, ec, "open", null);
      case FORMAT -> new PdfCorruptException(ctx, ec, "open", null);
      case SECURITY -> new PdfUnsupportedSecurityException(ctx, ec, "open", null);
      default -> new PdfiumException(ctx, ec, "open", null);
    };
  }

  private static MemorySegment metadataKeySegment(MetadataTag tag) {
    MemorySegment pre = MetadataCache.TAG_SEGMENTS.get(tag);
    if (pre != null) return pre;
    return ScratchBuffer.getUtf8(tag.pdfKey());
  }

  private static int wideStringByteLength(String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    return Math.toIntExact(Math.addExact(Math.multiplyExact((long) value.length(), 2L), 2L));
  }

  private static int writeWideString(MemorySegment buffer, String value) {
    int needed = wideStringByteLength(value);
    if (needed == 0) {
      return 0;
    }
    if (buffer.byteSize() < needed) {
      return needed;
    }
    long offset = 0;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      buffer.set(JAVA_BYTE, offset, (byte) (ch & 0xff));
      buffer.set(JAVA_BYTE, offset + 1, (byte) ((ch >>> 8) & 0xff));
      offset += 2;
    }
    buffer.set(JAVA_BYTE, offset, (byte) 0);
    buffer.set(JAVA_BYTE, offset + 1, (byte) 0);
    return needed;
  }

  private static int readTrailerEndsInto(MemorySegment doc, MemorySegment trailerBuffer)
      throws Throwable {
    if (ViewBindings.FPDF_GetTrailerEnds() == null
        || trailerBuffer == null
        || FfmHelper.isNull(trailerBuffer)) {
      return 0;
    }
    long capacity = trailerBuffer.byteSize() / JAVA_INT.byteSize();
    if (capacity <= 0) {
      return 0;
    }
    long written =
        (long) ViewBindings.FPDF_GetTrailerEnds().invokeExact(doc, trailerBuffer, capacity);
    if (written <= 0) {
      return 0;
    }
    if (written > capacity) {
      throw new IllegalArgumentException("trailerBuffer is too small for reported trailer count");
    }
    return Math.toIntExact(written);
  }
}
