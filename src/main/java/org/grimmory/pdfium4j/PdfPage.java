package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.grimmory.pdfium4j.exception.PdfiumException;
import org.grimmory.pdfium4j.exception.PdfiumRenderException;
import org.grimmory.pdfium4j.internal.AnnotBindings;
import org.grimmory.pdfium4j.internal.BitmapBindings;
import org.grimmory.pdfium4j.internal.EditBindings;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.internal.ShimBindings;
import org.grimmory.pdfium4j.internal.TextBindings;
import org.grimmory.pdfium4j.internal.ThumbnailBindings;
import org.grimmory.pdfium4j.internal.ViewBindings;
import org.grimmory.pdfium4j.model.AnnotationType;
import org.grimmory.pdfium4j.model.BitmapSlab;
import org.grimmory.pdfium4j.model.EmbeddedImage;
import org.grimmory.pdfium4j.model.NativeBitmap;
import org.grimmory.pdfium4j.model.PageBox;
import org.grimmory.pdfium4j.model.PageSize;
import org.grimmory.pdfium4j.model.PdfAnnotation;
import org.grimmory.pdfium4j.model.PdfLink;
import org.grimmory.pdfium4j.model.PdfStructureElement;
import org.grimmory.pdfium4j.model.RenderFlags;
import org.grimmory.pdfium4j.model.RenderProfile;
import org.grimmory.pdfium4j.model.RenderResult;
import org.grimmory.pdfium4j.model.TextCharInfo;

/** Represents an open page within a {@link PdfDocument}. */
public final class PdfPage implements AutoCloseable {

  private static final int OPAQUE_WHITE = 0xFFFFFFFF;
  private static final int BYTES_PER_PIXEL = 4; // RGBA
  private static final int DEFAULT_PROGRESSIVE_SLICE_MICROS = 3800;

  private static final VarHandle REF_COUNT;
  private static final MemorySegment PAUSE_CALLBACK_STUB;

  private static final StructLayout PAUSE_STATE_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.JAVA_LONG.withName("deadlineNanos"),
          ValueLayout.JAVA_INT.withName("cancelled"),
          MemoryLayout.paddingLayout(4));

  private static final VarHandle VH_DEADLINE =
      PAUSE_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("deadlineNanos"));
  private static final VarHandle VH_CANCELLED =
      PAUSE_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("cancelled"));

  private static final MethodHandle MH_BITMAP_CREATE_EX = BitmapBindings.fpdfBitmapCreateEx();
  private static final MethodHandle MH_BITMAP_FILL_RECT = BitmapBindings.fpdfBitmapFillRect();
  private static final MethodHandle MH_RENDER_PAGE_BITMAP = ViewBindings.fpdfRenderPageBitmap();
  private static final MethodHandle MH_RENDER_PAGE_WITH_MATRIX =
      ViewBindings.fpdfRenderPageBitmapWithMatrix();
  private static final MethodHandle MH_BITMAP_DESTROY = BitmapBindings.fpdfBitmapDestroy();
  private static final MethodHandle MH_RENDER_PAGE_START = ViewBindings.fpdfRenderPageBitmapStart();
  private static final MethodHandle MH_RENDER_PAGE_CONTINUE = ViewBindings.fpdfRenderPageContinue();
  private static final MethodHandle MH_RENDER_PAGE_CLOSE = ViewBindings.fpdfRenderPageClose();

  static {
    try {
      REF_COUNT = MethodHandles.lookup().findVarHandle(PdfPage.class, "refCount", int.class);
      MethodHandle pauseMh =
          MethodHandles.lookup()
              .findStatic(
                  PdfPage.class,
                  "needToPauseNow",
                  MethodType.methodType(int.class, MemorySegment.class));
      PAUSE_CALLBACK_STUB =
          FfmHelper.LINKER.upcallStub(
              pauseMh, FunctionDescriptor.of(FfmHelper.C_INT, FfmHelper.C_POINTER), Arena.global());
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static int needToPauseNow(MemorySegment pauseStruct) {
    MemorySegment userPtr = (MemorySegment) ViewBindings.IFSDK_PAUSE_USER.get(pauseStruct, 0L);
    if (FfmHelper.isNull(userPtr)) return 0;
    MemorySegment state = userPtr.reinterpret(PAUSE_STATE_LAYOUT.byteSize());
    if ((int) VH_CANCELLED.get(state, 0L) != 0) {
      return 1;
    }
    return System.nanoTime() >= (long) VH_DEADLINE.get(state, 0L) ? 1 : 0;
  }

  private final MemorySegment handle;
  private final MemorySegment docHandle;
  private final Thread ownerThread;
  private final long maxRenderPixels;
  private final Consumer<PdfPage> onClose;
  private final Runnable onModified;

  @SuppressWarnings("PMD.UnusedPrivateField") // accessed via VarHandle REF_COUNT
  private volatile int refCount = 1;

  private volatile boolean closed = false;

  private volatile MemorySegment cachedTextPage = MemorySegment.NULL;
  private volatile PageSize cachedSize = null;
  private volatile int cachedRotation = -1;

  public boolean isClosed() {
    return closed;
  }

  public long estimatedSizeBytes() {
    long size = 32_000; // Base estimate for FPDF_PAGE handle
    if (!FfmHelper.isNull(cachedTextPage)) {
      size += 64_000; // Base estimate for FPDF_TEXTPAGE handle
      try {
        size += (long) charCount() * 4; // Estimate based on text content
      } catch (Exception e) {
        // Ignore if we can't count chars
        PdfiumLibrary.ignore(e);
      }
    }
    return size;
  }

  PdfPage(
      MemorySegment handle,
      MemorySegment docHandle,
      Thread ownerThread,
      long maxRenderPixels,
      Consumer<PdfPage> onClose,
      Runnable onModified) {
    this.handle = handle.reinterpret(ValueLayout.ADDRESS.byteSize());
    this.docHandle = docHandle;
    this.ownerThread = ownerThread;
    this.maxRenderPixels = maxRenderPixels;
    this.onClose = onClose;
    this.onModified = onModified;
  }

  /** Get the page dimensions in points (1 pt = 1/72 inch). */
  public PageSize size() {
    ensureOpen();
    if (cachedSize != null) return cachedSize;
    try {
      float w = (float) ViewBindings.fpdfGetPageWidthF().invokeExact(handle);
      float h = (float) ViewBindings.fpdfGetPageHeightF().invokeExact(handle);
      cachedSize = new PageSize(w, h);
      return cachedSize;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to get page size", t);
    }
  }

  public RenderResult render(int dpi) {
    return render(dpi, RenderFlags.DEFAULT);
  }

  public RenderResult render(int dpi, RenderFlags flags) {
    return render(dpi, flags, OPAQUE_WHITE);
  }

  public RenderResult render(int dpi, RenderProfile profile) {
    return render(dpi, RenderFlags.forProfile(profile), OPAQUE_WHITE);
  }

  public RenderResult render(int dpi, RenderProfile profile, int background) {
    return render(dpi, RenderFlags.forProfile(profile), background);
  }

  public CompletableFuture<RenderResult> renderAsync(int dpi, RenderFlags flags) {
    try {
      return CompletableFuture.completedFuture(render(dpi, flags));
    } catch (Throwable t) {
      return CompletableFuture.failedFuture(t);
    }
  }

  public CompletableFuture<RenderResult> renderAsync(
      int dpi, RenderFlags flags, Executor executor) {
    return CompletableFuture.supplyAsync(() -> render(dpi, flags), executor);
  }

  public CompletableFuture<RenderResult> renderAsync(int dpi, RenderProfile profile) {
    return renderAsync(dpi, RenderFlags.forProfile(profile));
  }

  public CompletableFuture<RenderResult> renderAsync(
      int dpi, RenderProfile profile, Executor executor) {
    return renderAsync(dpi, RenderFlags.forProfile(profile), executor);
  }

  public RenderResult render(int dpi, RenderFlags flags, int background) {
    ensureOpen();
    PageSize size = size();
    int w = size.widthPixels(dpi);
    int h = size.heightPixels(dpi);
    if (w <= 0 || h <= 0) {
      return new RenderResult(1, 1, new byte[4]);
    }
    return renderAtSize(w, h, flags, background);
  }

  public NativeBitmap renderNative(int dpi, RenderFlags flags) {
    ensureOpen();
    PageSize size = size();
    int w = size.widthPixels(dpi);
    int h = size.heightPixels(dpi);
    if (w <= 0 || h <= 0) {
      throw new IllegalArgumentException("Invalid dimensions: " + w + "x" + h);
    }

    ensureRenderBudget(w, h);
    int stride = Math.multiplyExact(w, BYTES_PER_PIXEL);
    // NativeBitmap owns its own arena as it may be passed across threads or have a longer lifecycle
    Arena arena = Arena.ofShared();
    try {
      long sizeBytes = Math.multiplyExact(stride, (long) h);
      MemorySegment dest = arena.allocate(sizeBytes);
      renderTo(dest, w, h, stride, flags.value(), OPAQUE_WHITE);
      return new NativeBitmap(w, h, stride, dest, arena);
    } catch (Exception t) {
      arena.close();
      throw new PdfiumRenderException("Failed to render native bitmap", t);
    }
  }

  public NativeBitmap renderNative(int dpi, RenderProfile profile) {
    return renderNative(dpi, RenderFlags.forProfile(profile));
  }

  public CompletableFuture<NativeBitmap> renderNativeAsync(int dpi, RenderFlags flags) {
    try {
      return CompletableFuture.completedFuture(renderNative(dpi, flags));
    } catch (Throwable t) {
      return CompletableFuture.failedFuture(t);
    }
  }

  public CompletableFuture<NativeBitmap> renderNativeAsync(
      int dpi, RenderFlags flags, Executor executor) {
    return CompletableFuture.supplyAsync(() -> renderNative(dpi, flags), executor);
  }

  public void renderToSlab(BitmapSlab slab, int dpi, RenderFlags flags, int background) {
    ensureOpen();
    if (slab == null) {
      throw new IllegalArgumentException("slab must not be null");
    }
    PageSize pageSize = size();
    int w = pageSize.widthPixels(dpi);
    int h = pageSize.heightPixels(dpi);
    if (w <= 0 || h <= 0) {
      return;
    }
    ensureRenderBudget(w, h);
    MemorySegment dest = slab.resizeRgba(w, h);
    renderTo(dest, w, h, slab.stride(), flags.value(), background);
  }

  public void renderTo(MemorySegment dest, int w, int h, int stride, int flags, int background) {
    ensureOpen();
    if (w <= 0 || h <= 0) return;

    int minStride = Math.multiplyExact(w, BYTES_PER_PIXEL);
    if (stride < minStride) {
      throw new IllegalArgumentException(
          "stride must be >= width * " + BYTES_PER_PIXEL + ", got: " + stride);
    }

    long requiredSize = (long) stride * h;
    if (dest.byteSize() < requiredSize) {
      throw new IllegalArgumentException(
          "Destination segment too small: expected %d bytes, got %d"
              .formatted(requiredSize, dest.byteSize()));
    }

    MemorySegment bitmap = MemorySegment.NULL;
    try {
      // BGRA format = 4
      bitmap = (MemorySegment) MH_BITMAP_CREATE_EX.invokeExact(w, h, 4, dest, stride);
      if (FfmHelper.isNull(bitmap)) {
        throw new PdfiumRenderException("fpdfbitmapCreateEx failed");
      }

      MH_BITMAP_FILL_RECT.invokeExact(bitmap, 0, 0, w, h, (long) background);

      MH_RENDER_PAGE_BITMAP.invokeExact(bitmap, handle, 0, 0, w, h, 0, flags);
    } catch (Throwable t) {
      throw new PdfiumRenderException("Failed to render page to segment", t);
    } finally {
      if (!FfmHelper.isNull(bitmap)) {
        try {
          MH_BITMAP_DESTROY.invokeExact(bitmap);
        } catch (Throwable e) {
          PdfiumLibrary.ignore(e);
        }
      }
    }
  }

  /**
   * Renders the page directly into a {@link ByteBuffer} using the specified DPI.
   *
   * @param target the target ByteBuffer (must be direct or backed by an array)
   * @param dpi target rendering resolution (DPI)
   */
  public void renderToBuffer(ByteBuffer target, int dpi) {
    renderToBuffer(target, dpi, RenderFlags.DEFAULT);
  }

  /**
   * Renders the page directly into a {@link ByteBuffer} using the specified DPI and flags.
   *
   * @param target the target ByteBuffer
   * @param dpi target rendering resolution
   * @param flags rendering flags
   */
  public void renderToBuffer(ByteBuffer target, int dpi, RenderFlags flags) {
    renderToBuffer(target, dpi, flags, OPAQUE_WHITE);
  }

  /**
   * Renders the page directly into a {@link ByteBuffer} using the specified DPI, flags, and
   * background color.
   *
   * @param target the target ByteBuffer
   * @param dpi target rendering resolution
   * @param flags rendering flags
   * @param background background color ARGB
   */
  public void renderToBuffer(ByteBuffer target, int dpi, RenderFlags flags, int background) {
    ensureOpen();
    PageSize pageSize = size();
    int w = pageSize.widthPixels(dpi);
    int h = pageSize.heightPixels(dpi);
    if (w <= 0 || h <= 0) return;
    ensureRenderBudget(w, h);
    int stride = w * BYTES_PER_PIXEL;
    renderToBuffer(target, w, h, stride, flags, background);
  }

  /**
   * Renders the page directly into a {@link ByteBuffer} with explicit dimensions.
   *
   * @param target the target ByteBuffer
   * @param w target width in pixels
   * @param h target height in pixels
   * @param stride stride in bytes
   * @param flags rendering flags
   * @param background background color ARGB
   */
  public void renderToBuffer(
      ByteBuffer target, int w, int h, int stride, RenderFlags flags, int background) {
    ensureOpen();
    if (target == null) {
      throw new IllegalArgumentException("target buffer must not be null");
    }
    MemorySegment segment = MemorySegment.ofBuffer(target);
    renderTo(segment, w, h, stride, flags.value(), background);
  }

  public RenderResult renderViewport(
      float viewX,
      float viewY,
      float viewWidth,
      float viewHeight,
      int dpi,
      RenderFlags flags,
      int background) {
    ensureOpen();
    if (viewWidth <= 0 || viewHeight <= 0) {
      throw new IllegalArgumentException("Viewport width and height must be positive");
    }
    float scale = dpi / 72.0f;
    int w = Math.max(1, Math.round(viewWidth * scale));
    int h = Math.max(1, Math.round(viewHeight * scale));
    ensureRenderBudget(w, h);

    int stride = Math.multiplyExact(w, BYTES_PER_PIXEL);
    try (var _ = ScratchBuffer.acquireScope()) {
      MemorySegment dest = ScratchBuffer.get(Math.multiplyExact((long) stride, (long) h));
      renderViewportTo(
          dest, w, h, stride, viewX, viewY, viewWidth, viewHeight, dpi, flags, background);
      return new RenderResult(w, h, dest.toArray(JAVA_BYTE));
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumRenderException("Failed to render viewport", t);
    }
  }

  public RenderResult renderViewport(
      float viewX, float viewY, float viewWidth, float viewHeight, int dpi, RenderFlags flags) {
    return renderViewport(viewX, viewY, viewWidth, viewHeight, dpi, flags, OPAQUE_WHITE);
  }

  public void renderViewportTo(
      MemorySegment dest,
      int bitmapWidth,
      int bitmapHeight,
      int stride,
      float viewX,
      float viewY,
      float viewWidth,
      float viewHeight,
      int dpi,
      RenderFlags flags,
      int background) {
    ensureOpen();
    if (bitmapWidth <= 0 || bitmapHeight <= 0) {
      throw new IllegalArgumentException("bitmapWidth and bitmapHeight must be positive");
    }
    if (viewWidth <= 0 || viewHeight <= 0) {
      throw new IllegalArgumentException("Viewport width and height must be positive");
    }
    if (ViewBindings.fpdfRenderPageBitmapWithMatrix() == null) {
      throw new UnsupportedOperationException("FPDF_RenderPageBitmapWithMatrix is unavailable");
    }

    int minStride = Math.multiplyExact(bitmapWidth, BYTES_PER_PIXEL);
    if (stride < minStride) {
      throw new IllegalArgumentException(
          "stride must be >= width * " + BYTES_PER_PIXEL + ", got: " + stride);
    }
    long requiredSize = (long) stride * bitmapHeight;
    if (dest.byteSize() < requiredSize) {
      throw new IllegalArgumentException(
          "Destination segment too small: expected %d bytes, got %d"
              .formatted(requiredSize, dest.byteSize()));
    }

    MemorySegment bitmap = MemorySegment.NULL;
    try (var _ = ScratchBuffer.acquireScope()) {
      bitmap =
          (MemorySegment)
              MH_BITMAP_CREATE_EX.invokeExact(bitmapWidth, bitmapHeight, 4, dest, stride);
      if (FfmHelper.isNull(bitmap)) {
        throw new PdfiumRenderException("fpdfbitmapCreateEx failed");
      }
      MH_BITMAP_FILL_RECT.invokeExact(bitmap, 0, 0, bitmapWidth, bitmapHeight, (long) background);

      float scale = dpi / 72.0f;
      MemorySegment matrix = ScratchBuffer.get(ViewBindings.FS_MATRIX_LAYOUT.byteSize());
      matrix.set(JAVA_FLOAT, 0, scale);
      matrix.set(JAVA_FLOAT, 4, 0.0f);
      matrix.set(JAVA_FLOAT, 8, 0.0f);
      matrix.set(JAVA_FLOAT, 12, -scale);
      matrix.set(JAVA_FLOAT, 16, -viewX * scale);
      matrix.set(JAVA_FLOAT, 20, (viewY + viewHeight) * scale);

      MemorySegment clip = ScratchBuffer.get(ViewBindings.FS_RECTF_LAYOUT.byteSize());
      clip.set(JAVA_FLOAT, 0, 0.0f);
      clip.set(JAVA_FLOAT, 4, 0.0f);
      clip.set(JAVA_FLOAT, 8, (float) bitmapWidth);
      clip.set(JAVA_FLOAT, 12, (float) bitmapHeight);

      MH_RENDER_PAGE_WITH_MATRIX.invokeExact(bitmap, handle, matrix, clip, flags.value());
    } catch (Throwable t) {
      throw new PdfiumRenderException("Failed to render viewport to segment", t);
    } finally {
      if (!FfmHelper.isNull(bitmap)) {
        try {
          MH_BITMAP_DESTROY.invokeExact(bitmap);
        } catch (Throwable e) {
          PdfiumLibrary.ignore(e);
        }
      }
    }
  }

  public void renderProgressiveTo(
      MemorySegment dest, int w, int h, int stride, int flags, int background, int sliceMicros) {
    ensureOpen();
    if (w <= 0 || h <= 0) {
      return;
    }
    if (ViewBindings.fpdfRenderPageBitmapStart() == null
        || ViewBindings.fpdfRenderPageContinue() == null
        || ViewBindings.fpdfRenderPageClose() == null) {
      throw new UnsupportedOperationException("Progressive PDFium render API is unavailable");
    }

    int minStride = Math.multiplyExact(w, BYTES_PER_PIXEL);
    if (stride < minStride) {
      throw new IllegalArgumentException(
          "stride must be >= width * " + BYTES_PER_PIXEL + ", got: " + stride);
    }
    long requiredSize = (long) stride * h;
    if (dest.byteSize() < requiredSize) {
      throw new IllegalArgumentException(
          "Destination segment too small: expected %d bytes, got %d"
              .formatted(requiredSize, dest.byteSize()));
    }

    int effectiveSliceMicros = sliceMicros > 0 ? sliceMicros : DEFAULT_PROGRESSIVE_SLICE_MICROS;

    MemorySegment bitmap = MemorySegment.NULL;
    try (Arena stateArena = Arena.ofConfined();
        var _ = ScratchBuffer.acquireScope()) {
      MemorySegment state = stateArena.allocate(PAUSE_STATE_LAYOUT);
      MemorySegment pause = ScratchBuffer.get(ViewBindings.IFSDK_PAUSE_LAYOUT.byteSize());

      ViewBindings.IFSDK_PAUSE_VERSION.set(pause, 0L, 1);
      ViewBindings.IFSDK_PAUSE_CALLBACK.set(pause, 0L, PAUSE_CALLBACK_STUB);
      ViewBindings.IFSDK_PAUSE_USER.set(pause, 0L, state);

      bitmap = (MemorySegment) MH_BITMAP_CREATE_EX.invokeExact(w, h, 4, dest, stride);
      if (FfmHelper.isNull(bitmap)) {
        throw new PdfiumRenderException("fpdfbitmapCreateEx failed");
      }
      MH_BITMAP_FILL_RECT.invokeExact(bitmap, 0, 0, w, h, (long) background);

      VH_CANCELLED.set(state, 0L, 0);
      VH_DEADLINE.set(state, 0L, System.nanoTime() + (long) effectiveSliceMicros * 1000L);

      int status =
          (int) MH_RENDER_PAGE_START.invokeExact(bitmap, handle, 0, 0, w, h, 0, flags, pause);

      while (status == ViewBindings.FPDF_RENDER_TOBECONTINUED) {
        VH_DEADLINE.set(state, 0L, System.nanoTime() + (long) effectiveSliceMicros * 1000L);
        status = (int) MH_RENDER_PAGE_CONTINUE.invokeExact(handle, pause);
      }

      if (status == ViewBindings.FPDF_RENDER_FAILED) {
        throw new PdfiumRenderException("Progressive render failed");
      }
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumRenderException("Failed progressive render", t);
    } finally {
      try {
        if (MH_RENDER_PAGE_CLOSE != null) {
          MH_RENDER_PAGE_CLOSE.invokeExact(handle);
        }
      } catch (Throwable e) {
        PdfiumLibrary.ignore(e);
      }
      if (!FfmHelper.isNull(bitmap)) {
        try {
          MH_BITMAP_DESTROY.invokeExact(bitmap);
        } catch (Throwable e) {
          PdfiumLibrary.ignore(e);
        }
      }
    }
  }

  public void renderProgressiveTo(
      MemorySegment dest, int w, int h, int stride, RenderFlags flags, int background) {
    renderProgressiveTo(
        dest, w, h, stride, flags.value(), background, DEFAULT_PROGRESSIVE_SLICE_MICROS);
  }

  public CompletableFuture<Void> renderProgressiveToAsync(
      MemorySegment dest,
      int w,
      int h,
      int stride,
      int flags,
      int background,
      int sliceMicros,
      Executor executor) {
    return CompletableFuture.runAsync(
        () -> renderProgressiveTo(dest, w, h, stride, flags, background, sliceMicros), executor);
  }

  public String extractText() {
    try (var _ = ScratchBuffer.acquireScope()) {
      return withTextPage(
          "Failed to extract text",
          textPage -> {
            int charCount = (int) TextBindings.fpdfTextCountChars().invokeExact(textPage);
            if (charCount <= 0) {
              return "";
            }

            long bufSize = ((long) charCount + 1) * 2;
            MemorySegment buf = ScratchBuffer.get(bufSize);

            int written =
                (int) TextBindings.fpdfTextGetText().invokeExact(textPage, 0, charCount, buf);
            if (written <= 0) {
              return "";
            }

            return FfmHelper.fromWideString(buf, (long) written * 2);
          });
    }
  }

  /**
   * Accesses the page text content in a zero-allocation manner by yielding a memory segment and its
   * actual length to the consumer. The segment contains UTF-16LE data and is valid only during the
   * callback.
   *
   * @param consumer a consumer that will receive the memory segment and the length of the text
   */
  public void withText(PdfDocument.MemorySegmentConsumer consumer) {
    if (consumer == null) {
      throw new IllegalArgumentException("consumer must not be null");
    }
    try (var _ = ScratchBuffer.acquireScope()) {
      withTextPage(
          "Failed to extract text",
          textPage -> {
            int charCount = (int) TextBindings.fpdfTextCountChars().invokeExact(textPage);
            if (charCount <= 0) {
              return null;
            }

            long bufSize = ((long) charCount + 1) * 2;
            MemorySegment buf = ScratchBuffer.get(bufSize);

            int written =
                (int) TextBindings.fpdfTextGetText().invokeExact(textPage, 0, charCount, buf);
            if (written > 0) {
              consumer.accept(buf, (long) written * 2);
            }
            return null;
          });
    }
  }

  public int charCount() {
    return withTextPage(
        "Failed to count characters",
        textPage -> {
          int count = (int) TextBindings.fpdfTextCountChars().invokeExact(textPage);
          return Math.max(0, count);
        });
  }

  public int rotation() {
    ensureOpen();
    if (cachedRotation != -1) return cachedRotation;
    try {
      int rot = (int) EditBindings.fpdfPageGetRotation().invokeExact(handle);
      cachedRotation =
          switch (rot) {
            case 1 -> 90;
            case 2 -> 180;
            case 3 -> 270;
            default -> 0;
          };
      return cachedRotation;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to get page rotation", t);
    }
  }

  public void setRotation(int degrees) {
    ensureOpen();
    int rot =
        switch (degrees) {
          case 0 -> 0;
          case 90 -> 1;
          case 180 -> 2;
          case 270 -> 3;
          default ->
              throw new IllegalArgumentException(
                  "Rotation must be 0, 90, 180, or 270 degrees, got: " + degrees);
        };
    try {
      EditBindings.fpdfPageSetRotation().invokeExact(handle, rot);
      cachedRotation = degrees;
      onModified.run();
    } catch (Throwable t) {
      throw new PdfiumException("Failed to set page rotation", t);
    }
  }

  public RenderResult renderBounded(int dpi, int maxWidth, int maxHeight) {
    return renderBounded(dpi, maxWidth, maxHeight, RenderFlags.DEFAULT);
  }

  public RenderResult renderBounded(int dpi, int maxWidth, int maxHeight, RenderProfile profile) {
    return renderBounded(dpi, maxWidth, maxHeight, RenderFlags.forProfile(profile));
  }

  public RenderResult renderBounded(int dpi, int maxWidth, int maxHeight, RenderFlags flags) {
    ensureOpen();
    if (maxWidth <= 0 || maxHeight <= 0) {
      throw new IllegalArgumentException("maxWidth and maxHeight must be positive");
    }

    PageSize size = size();
    int naturalW = size.widthPixels(dpi);
    int naturalH = size.heightPixels(dpi);

    if (naturalW <= 0 || naturalH <= 0) {
      return new RenderResult(1, 1, new byte[4]);
    }

    int w = naturalW;
    int h = naturalH;
    if (w > maxWidth || h > maxHeight) {
      double scaleW = (double) maxWidth / w;
      double scaleH = (double) maxHeight / h;
      double scale = Math.min(scaleW, scaleH);
      w = Math.max(1, (int) Math.round(w * scale));
      h = Math.max(1, (int) Math.round(h * scale));
    }

    return renderAtSize(w, h, flags, OPAQUE_WHITE);
  }

  public RenderResult renderSafe(int dpi, long maxMemoryBytes) {
    return renderSafe(dpi, maxMemoryBytes, RenderFlags.DEFAULT);
  }

  public RenderResult renderSafe(int dpi, long maxMemoryBytes, RenderProfile profile) {
    return renderSafe(dpi, maxMemoryBytes, RenderFlags.forProfile(profile));
  }

  public RenderResult renderSafe(int dpi, long maxMemoryBytes, RenderFlags flags) {
    ensureOpen();
    PageSize size = size();
    int w = size.widthPixels(dpi);
    int h = size.heightPixels(dpi);

    if (w <= 0 || h <= 0) {
      return new RenderResult(1, 1, new byte[4]);
    }

    long requiredBytes;
    try {
      requiredBytes = Math.multiplyExact(Math.multiplyExact((long) w, (long) h), BYTES_PER_PIXEL);
    } catch (ArithmeticException e) {
      throw new PdfiumRenderException(
          "Rendering %dx%d at %d DPI exceeds addressable memory size.".formatted(w, h, dpi), e);
    }
    if (requiredBytes > maxMemoryBytes) {
      throw new PdfiumRenderException(
          "Rendering %dx%d at %d DPI requires %d bytes, which exceeds the limit of %d bytes."
              .formatted(w, h, dpi, requiredBytes, maxMemoryBytes));
    }

    return renderAtSize(w, h, flags, OPAQUE_WHITE);
  }

  public RenderResult renderThumbnail(int maxDimension) {
    if (maxDimension <= 0) {
      throw new IllegalArgumentException("maxDimension must be positive");
    }

    if (ThumbnailBindings.fpdfPageGetThumbnailAsBitmap() != null) {
      try {
        RenderResult nativeThumb = renderThumbnailNative();
        if (nativeThumb != null) {
          return nativeThumb;
        }
      } catch (Throwable t) {
        PdfiumLibrary.ignore(t);
      }
    }

    RenderFlags thumbnailFlags = RenderFlags.forProfile(RenderProfile.THUMBNAIL);
    return renderBounded(150, maxDimension, maxDimension, thumbnailFlags);
  }

  public void renderThumbnailTo(MemorySegment dest, int maxDimension) {
    ensureOpen();
    PageSize size = size();
    // Default thumbnail DPI is 150 for decent quality vs performance balance
    int naturalW = size.widthPixels(150);
    int naturalH = size.heightPixels(150);

    int w = naturalW;
    int h = naturalH;
    if (w > maxDimension || h > maxDimension) {
      double scale = Math.min((double) maxDimension / w, (double) maxDimension / h);
      w = Math.max(1, (int) Math.round(w * scale));
      h = Math.max(1, (int) Math.round(h * scale));
    }

    int flags = RenderFlags.forProfile(RenderProfile.THUMBNAIL).value();
    renderTo(dest, w, h, w * 4, flags, OPAQUE_WHITE);
  }

  private RenderResult renderThumbnailNative() throws Throwable {
    MemorySegment bitmap =
        (MemorySegment) ThumbnailBindings.fpdfPageGetThumbnailAsBitmap().invokeExact(handle);
    if (FfmHelper.isNull(bitmap)) {
      return null;
    }

    try {
      int w = (int) BitmapBindings.fpdfBitmapGetWidth().invokeExact(bitmap);
      int h = (int) BitmapBindings.fpdfBitmapGetHeight().invokeExact(bitmap);

      MemorySegment buffer =
          (MemorySegment) BitmapBindings.fpdfBitmapGetBuffer().invokeExact(bitmap);
      int stride = (int) BitmapBindings.fpdfBitmapGetStride().invokeExact(bitmap);
      byte[] rgba;
      if (stride == w * 4) {
        rgba = buffer.reinterpret((long) stride * h).toArray(JAVA_BYTE);
      } else {
        rgba = new byte[w * h * 4];
        MemorySegment dest = MemorySegment.ofArray(rgba);
        for (int y = 0; y < h; y++) {
          MemorySegment.copy(
              buffer,
              JAVA_BYTE,
              (long) y * stride,
              dest,
              JAVA_BYTE,
              (long) y * w * 4,
              (long) w * 4);
        }
      }

      return new RenderResult(w, h, rgba);
    } finally {
      try {
        BitmapBindings.fpdfBitmapDestroy().invokeExact(bitmap);
      } catch (Throwable e) {
        PdfiumLibrary.ignore(e);
      }
    }
  }

  private RenderResult renderAtSize(int w, int h, RenderFlags flags, int background) {
    ensureOpen();
    ensureRenderBudget(w, h);

    int stride = Math.multiplyExact(w, BYTES_PER_PIXEL);
    try (var _ = ScratchBuffer.acquireScope()) {
      MemorySegment dest = ScratchBuffer.get(Math.multiplyExact((long) stride, (long) h));
      renderTo(dest, w, h, stride, flags.value(), background);

      byte[] rgba = dest.toArray(JAVA_BYTE);
      return new RenderResult(w, h, rgba);
    } catch (PdfiumException e) {
      throw e;
    } catch (Exception t) {
      throw new PdfiumRenderException("Failed to render page at %dx%d".formatted(w, h), t);
    }
  }

  @FunctionalInterface
  public interface CharInfoConsumer {
    void accept(int charCode, float left, float bottom, float right, float top, float fontSize);
  }

  public void withTextWithBounds(CharInfoConsumer consumer) {
    withTextPage(
        "Failed to extract text with bounds",
        textPage -> {
          int charCount = (int) TextBindings.fpdfTextCountChars().invokeExact(textPage);
          if (charCount <= 0) return null;

          try (var _ = ScratchBuffer.acquireScope()) {
            MemorySegment buffer = ScratchBuffer.get(24L * charCount);
            int actual =
                (int)
                    ShimBindings.pdfium4jTextGetCharsWithBounds()
                        .invokeExact(textPage, 0, charCount, buffer);

            for (int i = 0; i < actual; i++) {
              long offset = i * 24L;
              int charCode = buffer.get(JAVA_INT, offset);
              float left = buffer.get(JAVA_FLOAT, offset + 4);
              float bottom = buffer.get(JAVA_FLOAT, offset + 8);
              float right = buffer.get(JAVA_FLOAT, offset + 12);
              float top = buffer.get(JAVA_FLOAT, offset + 16);
              float fontSize = buffer.get(JAVA_FLOAT, offset + 20);
              consumer.accept(charCode, left, bottom, right, top, fontSize);
            }
          }
          return null;
        });
  }

  public List<TextCharInfo> extractTextWithBounds() {
    int count = charCount();
    if (count <= 0) return List.of();
    List<TextCharInfo> result = new ArrayList<>(count);
    withTextWithBounds(
        (charCode, left, bottom, right, top, fontSize) ->
            result.add(new TextCharInfo(charCode, left, bottom, right, top, fontSize)));
    return List.copyOf(result);
  }

  public boolean hasText() {
    return charCount() > 0;
  }

  public boolean isBlank() {
    return charCount() == 0 && imageCount() == 0;
  }

  public List<PdfAnnotation> annotations() {
    ensureOpen();
    try (var _ = ScratchBuffer.acquireScope()) {
      int count = (int) AnnotBindings.fpdfPageGetAnnotCount().invokeExact(handle);
      if (count <= 0) {
        return List.of();
      }

      List<PdfAnnotation> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        MemorySegment annot = MemorySegment.NULL;
        try {
          annot = (MemorySegment) AnnotBindings.fpdfPageGetAnnot().invokeExact(handle, i);
          if (FfmHelper.isNull(annot)) continue;

          int subtypeCode = (int) AnnotBindings.fpdfAnnotGetSubtype().invokeExact(annot);
          AnnotationType type = AnnotationType.fromCode(subtypeCode);

          PdfAnnotation.Rect rect = getAnnotRect(annot);
          Optional<String> contents = getAnnotStringValue(annot, "Contents");
          Optional<String> author = getAnnotStringValue(annot, "T");
          Optional<String> subject = getAnnotStringValue(annot, "Subj");

          result.add(new PdfAnnotation(type, rect, contents, author, subject));
        } finally {
          if (!FfmHelper.isNull(annot)) {
            try {
              AnnotBindings.fpdfPageCloseAnnot().invokeExact(annot);
            } catch (Throwable e) {
              PdfiumLibrary.ignore(e);
            }
          }
        }
      }
      return List.copyOf(result);
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to read annotations", t);
    }
  }

  private static PdfAnnotation.Rect getAnnotRect(MemorySegment annot) {
    try (var _ = ScratchBuffer.acquireScope()) {
      MemorySegment rectSeg = ScratchBuffer.get(AnnotBindings.FS_RECTF_LAYOUT.byteSize());
      int ok = (int) AnnotBindings.fpdfAnnotGetRect().invokeExact(annot, rectSeg);
      if (ok != 0) {
        float left = rectSeg.get(JAVA_FLOAT, 0);
        float bottom = rectSeg.get(JAVA_FLOAT, 4);
        float right = rectSeg.get(JAVA_FLOAT, 8);
        float top = rectSeg.get(JAVA_FLOAT, 12);
        return new PdfAnnotation.Rect(left, bottom, right, top);
      }
    } catch (Throwable e) {
      PdfiumLibrary.ignore(e);
    }
    return new PdfAnnotation.Rect(0, 0, 0, 0);
  }

  private static Optional<String> getAnnotStringValue(MemorySegment annot, String key) {
    try (var _ = ScratchBuffer.acquireScope()) {
      try {
        MemorySegment keyProbe = ScratchBuffer.getUtf8(key);
        long needed =
            (long)
                AnnotBindings.fpdfAnnotGetStringValue()
                    .invokeExact(annot, keyProbe, MemorySegment.NULL, 0L);
        if (needed <= 2) return Optional.empty();

        var slots = ScratchBuffer.utf8KeyAndWideValue(key, needed);
        AnnotBindings.fpdfAnnotGetStringValue()
            .invokeExact(annot, slots.keySeg(), slots.valueSeg(), needed);
        String value = FfmHelper.fromWideString(slots.valueSeg(), needed);
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
      } catch (Throwable _) {
        return Optional.empty();
      }
    }
  }

  public List<PdfLink> webLinks() {
    try (var _ = ScratchBuffer.acquireScope()) {
      return withTextPage(
          "Failed to extract web links",
          textPage -> {
            MemorySegment pageLink = MemorySegment.NULL;
            try {
              pageLink = (MemorySegment) TextBindings.fpdfLinkLoadWebLinks().invokeExact(textPage);
              if (FfmHelper.isNull(pageLink)) return List.of();

              int count = (int) TextBindings.fpdfLinkCountWebLinks().invokeExact(pageLink);
              if (count <= 0) return List.of();

              List<PdfLink> result = new ArrayList<>(count);
              for (int i = 0; i < count; i++) {
                String url = getWebLinkUrl(pageLink, i);
                PdfAnnotation.Rect rect = getWebLinkRect(pageLink, i);
                result.add(
                    new PdfLink(url.isEmpty() ? Optional.empty() : Optional.of(url), rect, -1));
              }
              return List.copyOf(result);
            } finally {
              if (!FfmHelper.isNull(pageLink)) {
                try {
                  TextBindings.fpdfLinkCloseWebLinks().invokeExact(pageLink);
                } catch (Throwable e) {
                  PdfiumLibrary.ignore(e);
                }
              }
            }
          });
    }
  }

  public int imageCount() {
    ensureOpen();
    try {
      int total = (int) EditBindings.fpdfPageCountObjects().invokeExact(handle);
      int count = 0;
      for (int i = 0; i < total; i++) {
        MemorySegment obj = (MemorySegment) EditBindings.fpdfPageGetObject().invokeExact(handle, i);
        if (!FfmHelper.isNull(obj)) {
          int type = (int) EditBindings.fpdfPageObjGetType().invokeExact(obj);
          if (type == EditBindings.FPDF_PAGEOBJ_IMAGE) {
            count++;
          }
        }
      }
      return count;
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to count images", t);
    }
  }

  public List<EmbeddedImage> embeddedImages() {
    ensureOpen();
    try {
      int total = (int) EditBindings.fpdfPageCountObjects().invokeExact(handle);
      if (total <= 0) return Collections.emptyList();
      List<EmbeddedImage> images = new ArrayList<>(8);
      int imageIndex = 0;

      for (int i = 0; i < total; i++) {
        MemorySegment obj = (MemorySegment) EditBindings.fpdfPageGetObject().invokeExact(handle, i);
        if (FfmHelper.isNull(obj)) continue;

        int type = (int) EditBindings.fpdfPageObjGetType().invokeExact(obj);
        if (type != EditBindings.FPDF_PAGEOBJ_IMAGE) continue;

        try (var _ = ScratchBuffer.acquireScope()) {
          MemorySegment meta = ScratchBuffer.get(EditBindings.IMAGE_METADATA_LAYOUT.byteSize());
          int ok = (int) EditBindings.fpdfImageObjGetImageMetadata().invokeExact(obj, handle, meta);
          if (ok != 0) {
            int w = meta.get(JAVA_INT, 0);
            int h = meta.get(JAVA_INT, 4);
            float hdpi = meta.get(JAVA_FLOAT, 8);
            float vdpi = meta.get(JAVA_FLOAT, 12);
            int bpp = meta.get(JAVA_INT, 16);
            images.add(new EmbeddedImage(imageIndex, w, h, bpp, hdpi, vdpi));
          } else {
            images.add(new EmbeddedImage(imageIndex, 0, 0, 0, 0f, 0f));
          }
        }
        imageIndex++;
      }
      return List.copyOf(images);
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to get embedded images", t);
    }
  }

  private static String getWebLinkUrl(MemorySegment pageLink, int linkIndex) {
    try (var _ = ScratchBuffer.acquireScope()) {
      int charCount =
          (int)
              TextBindings.fpdfLinkGetURL().invokeExact(pageLink, linkIndex, MemorySegment.NULL, 0);
      if (charCount <= 1) return "";

      MemorySegment buf = ScratchBuffer.get((long) charCount * 2);
      TextBindings.fpdfLinkGetURL().invokeExact(pageLink, linkIndex, buf, charCount);
      return FfmHelper.fromWideString(buf, (long) charCount * 2);
    } catch (Throwable _) {
      return "";
    }
  }

  private static PdfAnnotation.Rect getWebLinkRect(MemorySegment pageLink, int linkIndex) {
    try (var _ = ScratchBuffer.acquireScope()) {
      int rectCount = (int) TextBindings.fpdfLinkCountRects().invokeExact(pageLink, linkIndex);
      if (rectCount <= 0) return new PdfAnnotation.Rect(0, 0, 0, 0);

      MemorySegment left = ScratchBuffer.get(JAVA_DOUBLE.byteSize());
      MemorySegment top = ScratchBuffer.get(JAVA_DOUBLE.byteSize());
      MemorySegment right = ScratchBuffer.get(JAVA_DOUBLE.byteSize());
      MemorySegment bottom = ScratchBuffer.get(JAVA_DOUBLE.byteSize());

      int ok =
          (int)
              TextBindings.fpdfLinkGetRect()
                  .invokeExact(pageLink, linkIndex, 0, left, top, right, bottom);
      if (ok != 0) {
        return new PdfAnnotation.Rect(
            (float) left.get(JAVA_DOUBLE, 0),
            (float) bottom.get(JAVA_DOUBLE, 0),
            (float) right.get(JAVA_DOUBLE, 0),
            (float) top.get(JAVA_DOUBLE, 0));
      }
    } catch (Throwable e) {
      PdfiumLibrary.ignore(e);
    }
    return new PdfAnnotation.Rect(0, 0, 0, 0);
  }

  @FunctionalInterface
  private interface TextPageFunction<T> {
    T apply(MemorySegment textPage) throws Throwable;
  }

  private <T> T withTextPage(String errorContext, TextPageFunction<T> action) {
    ensureOpen();
    if (!FfmHelper.isNull(cachedTextPage)) {
      try {
        return action.apply(cachedTextPage);
      } catch (Throwable t) {
        throw new PdfiumException(errorContext, t);
      }
    }

    MemorySegment textPage;
    try {
      textPage = (MemorySegment) TextBindings.fpdfTextLoadPage().invokeExact(handle);
      if (FfmHelper.isNull(textPage)) {
        throw new PdfiumException(errorContext + ": fpdftextLoadPage returned NULL");
      }
      cachedTextPage = textPage.reinterpret(ValueLayout.ADDRESS.byteSize());
      return action.apply(cachedTextPage);
    } catch (PdfiumException e) {
      throw e;
    } catch (Throwable t) {
      throw new PdfiumException(errorContext, t);
    }
  }

  private void ensureThreadConfinement() {
    Thread current = Thread.currentThread();
    if (current != ownerThread) {
      throw new IllegalStateException(
          "PdfPage must be accessed from its owner thread. owner="
              + ownerThread.getName()
              + ", current="
              + current.getName());
    }
  }

  private void ensureRenderBudget(int width, int height) {
    long pixels = (long) width * height;
    if (pixels > maxRenderPixels) {
      throw new PdfiumRenderException(
          "Render exceeds policy pixel budget: %d > %d. Use renderBounded() or lower DPI."
              .formatted(pixels, maxRenderPixels));
    }
  }

  public List<PdfStructureElement> structureTree() {
    ensureOpen();
    return StructureTreeReader.read(handle);
  }

  public Optional<RenderResult> getThumbnail() {
    ensureOpen();
    if (ThumbnailBindings.fpdfPageGetThumbnailAsBitmap() == null) {
      return Optional.empty();
    }

    MemorySegment bitmap = MemorySegment.NULL;
    try {
      bitmap = (MemorySegment) ThumbnailBindings.fpdfPageGetThumbnailAsBitmap().invokeExact(handle);
      if (FfmHelper.isNull(bitmap)) {
        return Optional.empty();
      }

      int w = (int) BitmapBindings.fpdfBitmapGetWidth().invokeExact(bitmap);
      int h = (int) BitmapBindings.fpdfBitmapGetHeight().invokeExact(bitmap);
      MemorySegment buffer =
          (MemorySegment) BitmapBindings.fpdfBitmapGetBuffer().invokeExact(bitmap);
      int stride = (int) BitmapBindings.fpdfBitmapGetStride().invokeExact(bitmap);

      byte[] rgba = buffer.reinterpret((long) stride * h).toArray(JAVA_BYTE);

      if (stride != w * BYTES_PER_PIXEL) {
        int rowLen = w * BYTES_PER_PIXEL;
        byte[] packed = new byte[h * rowLen];
        for (int row = 0; row < h; row++) {
          System.arraycopy(rgba, row * stride, packed, row * rowLen, rowLen);
        }
        rgba = packed;
      }

      return Optional.of(new RenderResult(w, h, rgba));
    } catch (Throwable t) {
      PdfiumLibrary.ignore(t);
      return Optional.empty();
    } finally {
      if (!FfmHelper.isNull(bitmap)) {
        try {
          BitmapBindings.fpdfBitmapDestroy().invokeExact(bitmap);
        } catch (Throwable e) {
          PdfiumLibrary.ignore(e);
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    ensureThreadConfinement();
    release();
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("PdfPage is already closed");
    ensureThreadConfinement();
  }

  void acquire() {
    ensureThreadConfinement();
    if (closed) throw new IllegalStateException("PdfPage is already closed");
    REF_COUNT.getAndAdd(this, 1);
  }

  void release() {
    ensureThreadConfinement();
    if (closed) return;
    if ((int) REF_COUNT.getAndAdd(this, -1) == 1) {
      doClose();
    }
  }

  void closeFromDocument() {
    doClose();
  }

  private synchronized void doClose() {
    if (closed) return;
    closed = true;
    if (onClose != null) {
      onClose.accept(this);
    }
    try {
      closeCachedTextPage();
      ViewBindings.fpdfClosePage().invokeExact(handle);
    } catch (Throwable e) {
      PdfiumLibrary.ignore(e);
    }
  }

  /**
   * Flattens all annotations and form fields in this page.
   *
   * @param interactiveFields whether to flatten form fields (true) or annotations only (false)
   * @return true if successful
   */
  public boolean flatten(boolean interactiveFields) {
    ensureOpen();
    try {
      int flag = interactiveFields ? 2 : 1;
      int result = (int) EditBindings.fpdfPageFlatten().invokeExact(handle, flag);
      if (result > 0) {
        onModified.run();
        return true;
      }
      return false;
    } catch (Throwable t) {
      throw new PdfiumException("Failed to flatten page", t);
    }
  }

  /**
   * Flattens the entire page into a single uneditable raster image layer. This permanently replaces
   * all scalable vectors, text, and annotations with a flat bitmap.
   *
   * @param scaleFactor the resolution scale multiplier (e.g. 2.0f for 144 DPI equivalent)
   */
  public void flattenToImage(float scaleFactor) {
    ensureOpen();
    try {
      int width = (int) Math.ceil(size().width() * scaleFactor);
      int height = (int) Math.ceil(size().height() * scaleFactor);

      MemorySegment bitmap =
          (MemorySegment) MH_BITMAP_CREATE_EX.invokeExact(width, height, 4, MemorySegment.NULL, 0);
      if (FfmHelper.isNull(bitmap)) {
        throw new PdfiumException("Failed to allocate bitmap for flattenToImage");
      }

      try {
        MH_BITMAP_FILL_RECT.invokeExact(bitmap, 0, 0, width, height, 0xFFFFFFFFL);

        MH_RENDER_PAGE_BITMAP.invokeExact(
            bitmap, handle, 0, 0, width, height, 0, 1 /* FPDF_ANNOT */);

        int count = (int) EditBindings.fpdfPageCountObjects().invokeExact(handle);
        for (int i = count - 1; i >= 0; i--) {
          MemorySegment obj =
              (MemorySegment) EditBindings.fpdfPageGetObject().invokeExact(handle, i);
          if (!FfmHelper.isNull(obj)) {
            int removed = (int) EditBindings.fpdfPageRemoveObject().invokeExact(handle, obj);
            if (removed != 0) {
              EditBindings.fpdfPageObjDestroy().invokeExact(obj);
            }
          }
        }

        MemorySegment imageObj =
            (MemorySegment) EditBindings.fpdfPageObjNewImageObj().invokeExact(docHandle);
        if (FfmHelper.isNull(imageObj)) {
          throw new PdfiumException("Failed to create new image object");
        }

        int setBitmap =
            (int)
                EditBindings.fpdfImageObjSetBitmap()
                    .invokeExact(MemorySegment.NULL, 0, imageObj, bitmap);
        if (setBitmap == 0) {
          throw new PdfiumException("Failed to set bitmap into image object");
        }

        int setMatrix =
            (int)
                EditBindings.fpdfImageObjSetMatrix()
                    .invokeExact(
                        imageObj,
                        (double) size().width(),
                        0.0,
                        0.0,
                        (double) size().height(),
                        0.0,
                        0.0);
        if (setMatrix == 0) {
          throw new PdfiumException("Failed to set image matrix");
        }

        EditBindings.fpdfPageInsertObject().invokeExact(handle, imageObj);

        int generated = (int) EditBindings.fpdfPageGenerateContent().invokeExact(handle);
        if (generated == 0) {
          throw new PdfiumException("Failed to regenerate page content");
        }

        onModified.run();
      } finally {
        MH_BITMAP_DESTROY.invokeExact(bitmap);
      }
    } catch (Throwable t) {
      if (t instanceof PdfiumException pe) throw pe;
      throw new PdfiumException("Failed to flatten page to image layer", t);
    }
  }

  /**
   * Inserts a raw BGRA pixel array into the page as a single image layer, stretched to the page
   * bounds. This is useful for high-performance Image-to-PDF conversion.
   *
   * @param bgraData the raw BGRA pixel data
   * @param imgWidth the pixel width of the image
   * @param imgHeight the pixel height of the image
   */
  public void insertImage(byte[] bgraData, int imgWidth, int imgHeight) {
    ensureOpen();
    try {
      MemorySegment bitmap =
          (MemorySegment)
              MH_BITMAP_CREATE_EX.invokeExact(imgWidth, imgHeight, 4, MemorySegment.NULL, 0);
      if (FfmHelper.isNull(bitmap)) {
        throw new PdfiumException("Failed to allocate bitmap for insertImage");
      }

      try {
        MemorySegment buffer =
            (MemorySegment) BitmapBindings.fpdfBitmapGetBuffer().invokeExact(bitmap);
        buffer = buffer.reinterpret(bgraData.length);
        MemorySegment.copy(bgraData, 0, buffer, JAVA_BYTE, 0, bgraData.length);

        MemorySegment imageObj =
            (MemorySegment) EditBindings.fpdfPageObjNewImageObj().invokeExact(docHandle);
        if (FfmHelper.isNull(imageObj)) {
          throw new PdfiumException("Failed to create new image object");
        }

        int setBitmap =
            (int)
                EditBindings.fpdfImageObjSetBitmap()
                    .invokeExact(MemorySegment.NULL, 0, imageObj, bitmap);
        if (setBitmap == 0) {
          throw new PdfiumException("Failed to set bitmap into image object");
        }

        int setMatrix =
            (int)
                EditBindings.fpdfImageObjSetMatrix()
                    .invokeExact(
                        imageObj,
                        (double) size().width(),
                        0.0,
                        0.0,
                        (double) size().height(),
                        0.0,
                        0.0);
        if (setMatrix == 0) {
          throw new PdfiumException("Failed to set image matrix");
        }

        EditBindings.fpdfPageInsertObject().invokeExact(handle, imageObj);
        int generated = (int) EditBindings.fpdfPageGenerateContent().invokeExact(handle);
        if (generated == 0) {
          throw new PdfiumException("Failed to regenerate page content");
        }

        onModified.run();
      } finally {
        MH_BITMAP_DESTROY.invokeExact(bitmap);
      }
    } catch (Throwable t) {
      if (t instanceof PdfiumException pe) throw pe;
      throw new PdfiumException("Failed to insert image", t);
    }
  }

  /** Gets the CropBox page boundary box. */
  public PageBox getCropBox() {
    return getPageBox("CropBox");
  }

  /** Sets the CropBox page boundary box. */
  public void setCropBox(float left, float bottom, float right, float top) {
    setPageBox("CropBox", left, bottom, right, top);
  }

  /** Gets the MediaBox page boundary box. */
  public PageBox getMediaBox() {
    return getPageBox("MediaBox");
  }

  /** Sets the MediaBox page boundary box. */
  public void setMediaBox(float left, float bottom, float right, float top) {
    setPageBox("MediaBox", left, bottom, right, top);
  }

  private PageBox getPageBox(String type) {
    ensureOpen();
    try (var _ = ScratchBuffer.acquireScope()) {
      MemorySegment base = ScratchBuffer.get(4 * JAVA_FLOAT.byteSize());
      MemorySegment left = base.asSlice(0, JAVA_FLOAT.byteSize());
      MemorySegment bottom = base.asSlice(4, JAVA_FLOAT.byteSize());
      MemorySegment right = base.asSlice(8, JAVA_FLOAT.byteSize());
      MemorySegment top = base.asSlice(12, JAVA_FLOAT.byteSize());

      MethodHandle getter =
          switch (type) {
            case "MediaBox" -> EditBindings.fpdfPageGetMediaBox();
            case "CropBox" -> EditBindings.fpdfPageGetCropBox();
            default -> throw new IllegalArgumentException("Unknown box type: " + type);
          };

      int ok = (int) getter.invokeExact(handle, left, bottom, right, top);
      if (ok == 0) {
        // Fall back to size if box not set/fails
        PageSize pageSize = size();
        return new PageBox(0, 0, pageSize.width(), pageSize.height());
      }
      return new PageBox(
          left.get(JAVA_FLOAT, 0),
          bottom.get(JAVA_FLOAT, 0),
          right.get(JAVA_FLOAT, 0),
          top.get(JAVA_FLOAT, 0));
    } catch (Throwable t) {
      throw new PdfiumException("Failed to get " + type, t);
    }
  }

  private void setPageBox(String type, float left, float bottom, float right, float top) {
    ensureOpen();
    try {
      MethodHandle setter =
          switch (type) {
            case "MediaBox" -> EditBindings.fpdfPageSetMediaBox();
            case "CropBox" -> EditBindings.fpdfPageSetCropBox();
            default -> throw new IllegalArgumentException("Unknown box type: " + type);
          };
      setter.invokeExact(handle, left, bottom, right, top);
      cachedSize = null; // Clear cached size since it might have changed
      onModified.run();
    } catch (Throwable t) {
      throw new PdfiumException("Failed to set " + type, t);
    }
  }

  /**
   * Redacts the specified rectangular area. Any page objects intersecting this area will be
   * permanently removed from the page, and a solid black rectangle will be drawn over it.
   */
  public void redact(float left, float bottom, float right, float top) {
    redact(left, bottom, right, top, 0, 0, 0, 255);
  }

  /**
   * Redacts the specified rectangular area. Any page objects intersecting this area will be
   * permanently removed from the page, and a solid rectangle of the specified RGBA color will be
   * drawn over it.
   */
  @SuppressWarnings("PMD.UnusedLocalVariable")
  public void redact(float left, float bottom, float right, float top, int r, int g, int b, int a) {
    ensureOpen();
    try {
      // 1. Remove intersecting objects
      int count = (int) EditBindings.fpdfPageCountObjects().invokeExact(handle);
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment objLeft = arena.allocate(JAVA_FLOAT);
        MemorySegment objBottom = arena.allocate(JAVA_FLOAT);
        MemorySegment objRight = arena.allocate(JAVA_FLOAT);
        MemorySegment objTop = arena.allocate(JAVA_FLOAT);

        for (int i = count - 1; i >= 0; i--) {
          MemorySegment obj =
              (MemorySegment) EditBindings.fpdfPageGetObject().invokeExact(handle, i);
          if (FfmHelper.isNull(obj)) continue;

          int ok =
              (int)
                  EditBindings.fpdfPageObjGetBounds()
                      .invokeExact(obj, objLeft, objBottom, objRight, objTop);
          if (ok > 0) {
            float oLeft = objLeft.get(JAVA_FLOAT, 0);
            float oBottom = objBottom.get(JAVA_FLOAT, 0);
            float oRight = objRight.get(JAVA_FLOAT, 0);
            float oTop = objTop.get(JAVA_FLOAT, 0);

            // Simple intersection check
            if (oLeft < right && oRight > left && oBottom < top && oTop > bottom) {
              int removed = (int) EditBindings.fpdfPageRemoveObject().invokeExact(handle, obj);
              if (removed > 0) {
                EditBindings.fpdfPageObjDestroy().invokeExact(obj);
              }
            }
          }
        }
      }

      // 2. Draw cover rectangle path
      MemorySegment path =
          (MemorySegment) EditBindings.fpdfPageObjCreateNewPath().invokeExact(left, bottom);
      if (FfmHelper.isNull(path)) {
        throw new PdfiumException("Failed to create redaction path object");
      }

      int _ = (int) EditBindings.fpdfPathLineTo().invokeExact(path, right, bottom);
      int _ = (int) EditBindings.fpdfPathLineTo().invokeExact(path, right, top);
      int _ = (int) EditBindings.fpdfPathLineTo().invokeExact(path, left, top);
      int _ = (int) EditBindings.fpdfPathClosePath().invokeExact(path);
      int _ =
          (int)
              EditBindings.fpdfPathSetDrawMode()
                  .invokeExact(path, 1, 0); // fillmode = 1, stroke = 0
      int _ = (int) EditBindings.fpdfPathSetFillColor().invokeExact(path, r, g, b, a);

      EditBindings.fpdfPageInsertObject().invokeExact(handle, path);

      // 3. Regenerate page content
      int generated = (int) EditBindings.fpdfPageGenerateContent().invokeExact(handle);
      if (generated == 0) {
        throw new PdfiumException("Failed to generate content after redaction");
      }

      onModified.run();
    } catch (Throwable t) {
      throw new PdfiumException("Failed to redact page area", t);
    }
  }

  private void closeCachedTextPage() {
    if (!FfmHelper.isNull(cachedTextPage)) {
      try {
        TextBindings.fpdfTextClosePage().invokeExact(cachedTextPage);
      } catch (Throwable e) {
        PdfiumLibrary.ignore(e);
      } finally {
        cachedTextPage = MemorySegment.NULL;
      }
    }
  }

  /**
   * Programmatically creates a new annotation on this page.
   *
   * @param type the type of the annotation (e.g. AnnotationType.HIGHLIGHT or AnnotationType.LINK)
   * @param left left edge coordinate
   * @param bottom bottom edge coordinate
   * @param right right edge coordinate
   * @param top top edge coordinate
   * @param contents optional annotation content description
   */
  public void createAnnotation(
      AnnotationType type, float left, float bottom, float right, float top, String contents) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment annot =
          (MemorySegment) AnnotBindings.fpdfPageCreateAnnot().invokeExact(handle, type.code());
      if (FfmHelper.isNull(annot)) {
        throw new PdfiumException("Failed to create annotation of type: " + type);
      }
      try {
        // Set Rect bounds
        MemorySegment rectSeg = arena.allocate(AnnotBindings.FS_RECTF_LAYOUT);
        rectSeg.set(JAVA_FLOAT, 0, left);
        rectSeg.set(JAVA_FLOAT, 4, top);
        rectSeg.set(JAVA_FLOAT, 8, right);
        rectSeg.set(JAVA_FLOAT, 12, bottom);

        int rectOk = (int) AnnotBindings.fpdfAnnotSetRect().invokeExact(annot, rectSeg);
        if (rectOk == 0) {
          throw new PdfiumException("Failed to set annotation rectangle bounds");
        }

        // Set Contents
        if (contents != null) {
          MemorySegment keyProbe = FfmHelper.toWideString(arena, "Contents");
          MemorySegment valProbe = FfmHelper.toWideString(arena, contents);
          int _ =
              (int) AnnotBindings.fpdfAnnotSetStringValue().invokeExact(annot, keyProbe, valProbe);
        }
        onModified.run();
      } finally {
        AnnotBindings.fpdfPageCloseAnnot().invokeExact(annot);
      }
    } catch (Throwable t) {
      throw new PdfiumException("Failed to create annotation on page", t);
    }
  }
}
