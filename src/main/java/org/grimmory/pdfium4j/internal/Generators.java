package org.grimmory.pdfium4j.internal;

import java.lang.foreign.Linker;
import java.nio.file.attribute.FileAttribute;

/**
 * Utility for providing shared constant instances of commonly used objects to avoid allocations.
 */
public final class Generators {

  private static final FileAttribute<?>[] EMPTY_FILE_ATTRIBUTES = new FileAttribute[0];
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final int[] EMPTY_INT_ARRAY = new int[0];
  private static final Linker.Option[] NO_OPTIONS = new Linker.Option[0];

  private Generators() {}

  /** Returns a shared zero-length {@link FileAttribute} array. */
  @SuppressWarnings("unchecked")
  public static <T> FileAttribute<T>[] emptyFileAttributes() {
    return (FileAttribute<T>[]) EMPTY_FILE_ATTRIBUTES;
  }

  /** Returns a shared zero-length byte array. */
  public static byte[] emptyByteArray() {
    return EMPTY_BYTE_ARRAY;
  }

  /** Returns a shared zero-length int array. */
  public static int[] emptyIntArray() {
    return EMPTY_INT_ARRAY;
  }

  /** Returns a shared zero-length {@link Linker.Option} array. */
  public static Linker.Option[] noOptions() {
    return NO_OPTIONS;
  }
}
