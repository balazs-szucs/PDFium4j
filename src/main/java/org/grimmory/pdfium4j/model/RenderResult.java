package org.grimmory.pdfium4j.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.ShimBindings;

/**
 * Result of rendering a PDF page: raw pixel data plus dimensions.
 *
 * <p>Pixel data is in BGRA byte order (PDFium native format with {@code FPDF_REVERSE_BYTE_ORDER}
 * flag, which gives RGBA).
 *
 * @param width image width in pixels
 * @param height image height in pixels
 * @param rgba raw RGBA pixel data (4 bytes per pixel). <b>Warning:</b> This array is mutable. For a
 *     read-only view, use {@link #asReadOnlyBuffer()}.
 */
@SuppressWarnings("PMD.EmptyCatchBlock")
public record RenderResult(int width, int height, byte[] rgba) {

  public RenderResult {
    rgba = rgba.clone();
  }

  @Override
  public byte[] rgba() {
    return rgba.clone();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RenderResult other)) {
      return false;
    }
    return width == other.width && height == other.height && Arrays.equals(rgba, other.rgba);
  }

  @Override
  public int hashCode() {
    int result = Integer.hashCode(width);
    result = 31 * result + Integer.hashCode(height);
    result = 31 * result + Arrays.hashCode(rgba);
    return result;
  }

  @Override
  public String toString() {
    return "RenderResult[width="
        + width
        + ", height="
        + height
        + ", rgba="
        + Arrays.toString(rgba)
        + "]";
  }

  /**
   * Returns a read-only view of the pixel data.
   *
   * @return a read-only {@link ByteBuffer} wrapping the internal array
   */
  public ByteBuffer asReadOnlyBuffer() {
    return ByteBuffer.wrap(rgba).asReadOnlyBuffer();
  }

  /**
   * Encode this render result as JPEG bytes with the specified quality.
   *
   * @param quality JPEG quality from 0.0 (worst) to 1.0 (best)
   * @return JPEG-encoded bytes
   * @throws UncheckedIOException if encoding fails
   */
  public byte[] toJpegBytes(float quality) {
    if (quality < 0f || quality > 1f) {
      throw new IllegalArgumentException(
          "JPEG quality must be between 0.0 and 1.0, got: " + quality);
    }
    int qualityInt = Math.round(quality * 100f);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bgraSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, rgba);
      MemorySegment outBytesPtr = arena.allocate(FfmHelper.C_POINTER);
      MemorySegment outLenPtr = arena.allocate(FfmHelper.C_SIZE_T);

      int stride = width * 4;
      int rc =
          (int)
              ShimBindings.pdfium4jEncodeJpeg()
                  .invokeExact(
                      bgraSegment, width, height, stride, qualityInt, 0, outBytesPtr, outLenPtr);

      if (rc != 0) {
        throw new IOException("Failed to encode JPEG natively, error: " + rc);
      }

      MemorySegment outBytes = outBytesPtr.get(FfmHelper.C_POINTER, 0);
      long len = outLenPtr.get(ValueLayout.JAVA_LONG, 0);

      byte[] result = new byte[(int) len];
      MemorySegment.copy(outBytes.reinterpret(len), ValueLayout.JAVA_BYTE, 0, result, 0, (int) len);

      try {
        ShimBindings.pdfium4jFreeBuffer().invokeExact(outBytes);
      } catch (Throwable t) {
        // Ignore
      }
      return result;
    } catch (Throwable t) {
      throw new UncheckedIOException(
          "Failed to encode JPEG natively",
          t instanceof IOException ? (IOException) t : new IOException(t));
    }
  }

  /**
   * Encode this render result as JPEG bytes with default quality (0.85).
   *
   * @return JPEG-encoded bytes
   * @throws UncheckedIOException if encoding fails
   */
  public byte[] toJpegBytes() {
    return toJpegBytes(0.85f);
  }

  /**
   * Encode this render result as PNG bytes (lossless, with alpha).
   *
   * @return PNG-encoded bytes
   * @throws UncheckedIOException if encoding fails
   */
  public byte[] toPngBytes() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bgraSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, rgba);
      MemorySegment outBytesPtr = arena.allocate(FfmHelper.C_POINTER);
      MemorySegment outLenPtr = arena.allocate(FfmHelper.C_SIZE_T);

      int stride = width * 4;
      int rc =
          (int)
              ShimBindings.pdfium4jEncodePng()
                  .invokeExact(bgraSegment, width, height, stride, 9, 0, 1, outBytesPtr, outLenPtr);

      if (rc != 0) {
        throw new IOException("Failed to encode PNG natively, error: " + rc);
      }

      MemorySegment outBytes = outBytesPtr.get(FfmHelper.C_POINTER, 0);
      long len = outLenPtr.get(ValueLayout.JAVA_LONG, 0);

      byte[] result = new byte[(int) len];
      MemorySegment.copy(outBytes.reinterpret(len), ValueLayout.JAVA_BYTE, 0, result, 0, (int) len);

      try {
        ShimBindings.pdfium4jFreeBuffer().invokeExact(outBytes);
      } catch (Throwable t) {
        // Ignore
      }
      return result;
    } catch (Throwable t) {
      throw new UncheckedIOException(
          "Failed to encode PNG natively",
          t instanceof IOException ? (IOException) t : new IOException(t));
    }
  }
}
