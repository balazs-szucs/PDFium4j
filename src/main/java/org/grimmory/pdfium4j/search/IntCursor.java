package org.grimmory.pdfium4j.search;

import java.util.NoSuchElementException;

/**
 * A highly optimized primitive int iterator for full-text search results.
 *
 * <p>Avoids the GC allocation and boxing overhead associated with standard {@code List<Integer>}
 * iterators.
 */
public interface IntCursor {
  /** Returns true if the iteration has more elements. */
  boolean hasNext();

  /**
   * Returns the next primitive int in the iteration.
   *
   * @throws NoSuchElementException if the iteration has no more elements
   */
  int nextInt();

  /** An empty cursor implementation. */
  IntCursor EMPTY =
      new IntCursor() {
        @Override
        public boolean hasNext() {
          return false;
        }

        @Override
        public int nextInt() {
          throw new NoSuchElementException();
        }
      };
}
