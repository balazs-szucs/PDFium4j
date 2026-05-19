package org.grimmory.pdfium4j;

/** A functional interface for consuming raw UTF-8 string data directly from off-heap memory. */
@FunctionalInterface
public interface Utf8Consumer {
  /**
   * Consume raw UTF-8 data.
   *
   * @param address native memory address containing the null-terminated UTF-8 string
   * @param length length of the string in bytes (excluding the null terminator)
   */
  void accept(long address, int length);
}
