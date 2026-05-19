package org.grimmory.pdfium4j.search;

import java.util.NoSuchElementException;

/** An {@link IntCursor} implementation that iterates over a primitive int array. */
public final class ArrayIntCursor implements IntCursor {
  private final int[] array;
  private int index;

  /**
   * Constructs a new ArrayIntCursor wrapping the given array.
   *
   * @param array the primitive array to wrap
   */
  public ArrayIntCursor(int[] array) {
    this.array = array != null ? array : new int[0];
    this.index = 0;
  }

  @Override
  public boolean hasNext() {
    return index < array.length;
  }

  @Override
  public int nextInt() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    return array[index++];
  }
}
