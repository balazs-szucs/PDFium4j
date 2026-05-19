package org.grimmory.pdfium4j.model;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Reusable native RGBA render slab for repeated page rendering.
 *
 * <p>The slab is intentionally not thread-safe; use one slab per render worker.
 */
public final class BitmapSlab implements AutoCloseable {

  private Arena arena = Arena.ofConfined();
  private MemorySegment buffer = MemorySegment.NULL;
  private long capacity;
  private int width;
  private int height;
  private int stride;

  public MemorySegment resizeRgba(int w, int h) {
    if (w <= 0 || h <= 0) {
      throw new IllegalArgumentException("Dimensions must be positive");
    }
    int nextStride = Math.multiplyExact(w, 4);
    long needed = Math.multiplyExact(nextStride, (long) h);
    if (needed > capacity) {
      arena.close();
      arena = Arena.ofConfined();
      buffer = arena.allocate(needed);
      capacity = needed;
    }
    width = w;
    height = h;
    stride = nextStride;
    return buffer.asSlice(0, needed);
  }

  public MemorySegment pixels() {
    if (capacity == 0) {
      throw new IllegalStateException("Slab not initialized. Call resizeRgba first.");
    }
    return buffer.asSlice(0, (long) stride * height);
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public int stride() {
    return stride;
  }

  public long capacity() {
    return capacity;
  }

  @Override
  public void close() {
    arena.close();
    buffer = MemorySegment.NULL;
    capacity = 0;
    width = 0;
    height = 0;
    stride = 0;
  }
}
