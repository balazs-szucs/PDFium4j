package org.grimmory.pdfium4j.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import org.grimmory.pdfium4j.model.PageSize;

/**
 * A highly optimized primitive sparse cache for {@link PageSize} instances.
 *
 * <p>Uses VarHandle element-wise volatile access to eliminate boxed Integer keys, Map overhead, and
 * garbage collection allocations.
 */
public final class PageSizeArray {
  private static final VarHandle SIZES = MethodHandles.arrayElementVarHandle(PageSize[].class);

  private volatile PageSize[] sizes = new PageSize[0];

  /**
   * Retrieve a cached page size safely without locks.
   *
   * @param index page index
   * @return the cached PageSize or null if not present
   */
  public PageSize get(int index) {
    PageSize[] current = sizes;
    if (index >= current.length) return null;
    return (PageSize) SIZES.getAcquire(current, index);
  }

  /**
   * Cache a page size safely.
   *
   * @param index page index
   * @param size the PageSize instance to cache
   */
  public void set(int index, PageSize size) {
    expand(index);
    SIZES.setRelease(sizes, index, size);
  }

  private void expand(int index) {
    if (index < sizes.length) return;
    synchronized (this) {
      if (index < sizes.length) return;
      int newLen = Math.max(index + 1, sizes.length * 2);
      PageSize[] newSizes = new PageSize[newLen];
      System.arraycopy(sizes, 0, newSizes, 0, sizes.length);
      sizes = newSizes;
    }
  }

  /** Clear the sparse array. */
  public void clear() {
    synchronized (this) {
      Arrays.fill(sizes, null);
      sizes = new PageSize[0];
    }
  }
}
