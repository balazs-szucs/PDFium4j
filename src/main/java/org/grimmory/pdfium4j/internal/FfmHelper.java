package org.grimmory.pdfium4j.internal;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Utility methods for Foreign Function &amp; Memory interop with PDFium.
 *
 * <p>PDFium primarily uses two string types: FPDF_BYTESTRING (UTF-8) and FPDF_WIDESTRING
 * (UTF-16LE). These helpers centralize the conversion logic to ensure consistency and minimize heap
 * allocations.
 */
public final class FfmHelper {

  private FfmHelper() {}

  /** Encode a Java String to a null-terminated UTF-16LE MemorySegment (FPDF_WIDESTRING). */
  public static MemorySegment toWideString(Arena arena, String text) {
    return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
  }

  /**
   * Decode a UTF-16LE buffer returned by PDFium into a Java String.
   *
   * <p>We subtract 2 bytes from the total length because PDFium's widestrings include a 2-byte null
   * terminator that is not part of the Java String content.
   */
  public static String fromWideString(MemorySegment seg, long byteLen) {
    if (byteLen <= 2) return "";
    long boundedByteLen = Math.min(byteLen, seg.byteSize());
    long lenLong = boundedByteLen - 2;
    if (lenLong <= 0) return "";
    if (lenLong > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Wide string length exceeds supported bounds: " + lenLong);
    }
    int len = (int) lenLong;
    byte[] arr = ScratchBuffer.getByteArray(len);
    MemorySegment.copy(seg, JAVA_BYTE, 0, arr, 0, len);
    return new String(arr, 0, len, StandardCharsets.UTF_16LE);
  }

  /**
   * Normalize a reported wide-string byte length from native code to a safe decode length.
   *
   * <p>Returns 0 when the value cannot represent a non-empty UTF-16LE null-terminated string.
   */
  public static long normalizeWideByteLength(MemorySegment seg, long reported, long requested) {
    long available = seg.byteSize();
    if (reported <= 0 || requested <= 2 || available <= 2) return 0;
    long max = Math.min(requested, available);
    long bounded = Math.min(reported, max);
    if ((bounded & 1L) != 0) {
      bounded -= 1;
    }
    if (bounded <= 2) return 0;
    if (hasTerminator(seg, bounded)) return bounded;

    long withTerminator = bounded + 2;
    if (withTerminator <= max && hasTerminator(seg, withTerminator)) {
      return withTerminator;
    }
    return 0;
  }

  private static boolean hasTerminator(MemorySegment seg, long byteLen) {
    if (byteLen < 2) return false;
    long idx = byteLen - 2;
    return seg.get(JAVA_BYTE, idx) == 0 && seg.get(JAVA_BYTE, idx + 1) == 0;
  }

  /**
   * Writes a Java string as a null-terminated UTF-8 string into the given segment.
   *
   * <p>We use a manual ASCII loop as a zero-allocation fast-path for the majority of PDF keys. For
   * strings containing non-ASCII characters, we fall back to standard UTF-8 encoding.
   */
  public static MemorySegment writeUtf8String(MemorySegment seg, String s) {
    int len = s.length();

    // Single-pass fast-path for ASCII
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c > 127) {
        // Fallback for UTF-8 if non-ASCII detected
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (seg.byteSize() < (long) bytes.length + 1) {
          throw new IllegalArgumentException("Segment too small for UTF-8 string");
        }
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        seg.set(JAVA_BYTE, bytes.length, (byte) 0);
        return seg.asSlice(0, (long) bytes.length + 1);
      }
      if (seg.byteSize() < (long) i + 1) {
        throw new IllegalArgumentException("Segment too small for UTF-8 string");
      }
      seg.set(JAVA_BYTE, i, (byte) c);
    }

    long required = (long) len + 1;
    if (seg.byteSize() < required) {
      throw new IllegalArgumentException("Segment too small for UTF-8 string");
    }
    seg.set(JAVA_BYTE, len, (byte) 0);
    return seg.asSlice(0, required);
  }

  /** Check for native null pointers. */
  public static boolean isNull(MemorySegment seg) {
    return seg == null || seg.equals(MemorySegment.NULL) || seg.address() == 0;
  }

  /** Check for native null pointers. */
  public static boolean isNull(long address) {
    return address == 0;
  }

  /** Encode a Java String to a null-terminated UTF-16LE MemorySegment. */
  public static MemorySegment writeWideString(Arena arena, String text) {
    return arena.allocateFrom(text, StandardCharsets.UTF_16LE);
  }

  /** Encode a Java String to a null-terminated UTF-16LE MemorySegment (FPDF_WIDESTRING). */
  public static MemorySegment writeWideString(MemorySegment seg, String text) {
    long len = text.length();
    long required = len * 2 + 2;
    if (seg.byteSize() < required) {
      throw new IllegalArgumentException("Segment too small for UTF-16 string");
    }
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      seg.set(JAVA_BYTE, (long) i * 2, (byte) (c & 0xFF));
      seg.set(JAVA_BYTE, (long) i * 2 + 1, (byte) (c >> 8));
    }
    seg.set(JAVA_BYTE, len * 2, (byte) 0);
    seg.set(JAVA_BYTE, len * 2 + 1, (byte) 0);
    return seg.asSlice(0, required);
  }

  /** Encode a Java String to a null-terminated UTF-8 MemorySegment. */
  public static MemorySegment writeUtf8String(Arena arena, String text) {
    return arena.allocateFrom(text, StandardCharsets.UTF_8);
  }

  /** Decode a UTF-16LE buffer into a Java String. */
  public static String readUtf16String(MemorySegment seg, long byteLen) {
    return fromWideString(seg, byteLen);
  }

  /** Decode an ASCII/UTF-8 buffer into a Java String. */
  public static String readAsciiString(MemorySegment seg, long byteLen) {
    if (byteLen <= 1) return "";
    long lenLong = byteLen - 1; // remove null terminator
    int len = (int) Math.min(lenLong, Integer.MAX_VALUE);
    byte[] arr = ScratchBuffer.getByteArray(len);
    MemorySegment.copy(seg, JAVA_BYTE, 0, arr, 0, len);
    return new String(arr, 0, len, StandardCharsets.UTF_8);
  }

  /** Calculate the byte length of a string in UTF-8 including the null terminator. */
  public static long utf8ByteLengthWithNull(String s) {
    return utf8ByteLength(s) + 1L;
  }

  /** Calculate the byte length of a string in UTF-8. */
  public static long utf8ByteLength(String s) {
    int len = s.length();
    for (int i = 0; i < len; i++) {
      if (s.charAt(i) > 127) {
        return s.getBytes(StandardCharsets.UTF_8).length;
      }
    }
    return len;
  }

  /** Writes a Java string as a UTF-8 string into the given segment (no null terminator). */
  public static void writeUtf8StringNoNull(MemorySegment seg, String s) {
    int len = s.length();
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c > 127) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (seg.byteSize() < bytes.length) {
          throw new IllegalArgumentException("Segment too small for UTF-8 string");
        }
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return;
      }
      if (seg.byteSize() < (long) i + 1) {
        throw new IllegalArgumentException("Segment too small for UTF-8 string");
      }
      seg.set(JAVA_BYTE, i, (byte) c);
    }
  }
}
