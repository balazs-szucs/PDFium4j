package org.grimmory.pdfium4j.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * A highly optimized primitive sparse cache for page rotations.
 *
 * <p>Uses VarHandle element-wise volatile access to eliminate boxed Integer keys, Map overhead, and
 * garbage collection allocations.
 */
public final class PageRotationArray {
  private static final VarHandle ROTATIONS = MethodHandles.arrayElementVarHandle(int[].class);

  private volatile int[] rotations = new int[0];

  /**
   * Retrieve a cached rotation safely without locks.
   *
   * @param index page index
   * @return the cached rotation, or -1 if not cached
   */
  public int get(int index) {
    int[] current = rotations;
    if (index >= current.length) return -1;
    return (int) ROTATIONS.getAcquire(current, index);
  }

  /**
   * Cache a rotation safely.
   *
   * @param index page index
   * @param rotation the rotation value to cache
   */
  public void set(int index, int rotation) {
    expand(index);
    ROTATIONS.setRelease(rotations, index, rotation);
  }

  private void expand(int index) {
    if (index < rotations.length) return;
    synchronized (this) {
      if (index < rotations.length) return;
      int newLen = Math.max(index + 1, rotations.length * 2);
      int[] newRotations = new int[newLen];
      Arrays.fill(newRotations, -1);
      System.arraycopy(rotations, 0, newRotations, 0, rotations.length);
      rotations = newRotations;
    }
  }

  /** Clear the sparse array. */
  public void clear() {
    synchronized (this) {
      rotations = new int[0];
    }
  }
}
