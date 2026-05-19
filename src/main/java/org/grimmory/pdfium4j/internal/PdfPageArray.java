package org.grimmory.pdfium4j.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import org.grimmory.pdfium4j.PdfPage;

/**
 * A primitive sparse cache for {@link PdfPage} instances.
 *
 * <p>Uses VarHandle element-wise volatile access to eliminate boxed Integer keys, Map overhead, and
 * garbage collection allocations.
 */
public final class PdfPageArray {
  private static final VarHandle PAGES = MethodHandles.arrayElementVarHandle(PdfPage[].class);

  private volatile PdfPage[] pages = new PdfPage[0];

  /**
   * Retrieve a cached page safely without locks.
   *
   * @param index page index
   * @return the cached PdfPage or null if not present
   */
  public PdfPage get(int index) {
    PdfPage[] current = pages;
    if (index >= current.length) return null;
    return (PdfPage) PAGES.getAcquire(current, index);
  }

  /**
   * Cache a page safely.
   *
   * @param index page index
   * @param page the PdfPage instance to cache
   */
  public void set(int index, PdfPage page) {
    expand(index);
    PAGES.setRelease(pages, index, page);
  }

  /**
   * Atomic lock-free compare-and-set for eviction/evaporating closed pages.
   *
   * @param index page index
   * @param expected expected PdfPage instance
   * @param update new PdfPage instance (often null)
   * @return true if successful
   */
  public boolean compareAndSet(int index, PdfPage expected, PdfPage update) {
    PdfPage[] current = pages;
    if (index >= current.length) return false;
    return PAGES.compareAndSet(current, index, expected, update);
  }

  private void expand(int index) {
    if (index < pages.length) return;
    synchronized (this) {
      if (index < pages.length) return;
      int newLen = Math.max(index + 1, pages.length * 2);
      PdfPage[] newPages = new PdfPage[newLen];
      System.arraycopy(pages, 0, newPages, 0, pages.length);
      pages = newPages;
    }
  }

  /**
   * Clear the sparse array references. Page closing is handled by PdfDocument's openPages or the
   * user's close call.
   */
  public void clear() {
    synchronized (this) {
      Arrays.fill(pages, null);
      pages = new PdfPage[0];
    }
  }
}
