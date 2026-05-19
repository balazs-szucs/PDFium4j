package org.grimmory.pdfium4j.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.function.Function;

/**
 * A highly optimized thread-local Arena and slab buffer pool.
 *
 * <p>Avoids the overhead of repeatedly calling {@code Arena.ofConfined()} in hot paths by
 * maintaining a reusable pre-allocated 8KB slab buffer per thread.
 */
@SuppressWarnings("PMD.EmptyCatchBlock")
public final class ThreadLocalArenaPool {
  private static final ThreadLocal<Arena> POOL = ThreadLocal.withInitial(Arena::ofAuto);
  private static final ThreadLocal<Boolean> IN_USE = ThreadLocal.withInitial(() -> false);
  private static final ThreadLocal<MemorySegment> SLAB =
      ThreadLocal.withInitial(() -> POOL.get().allocate(8192, 8));

  private ThreadLocalArenaPool() {}

  /**
   * Run an action with a thread-local segment of up to 8KB.
   *
   * @param action the function to run with the segment
   * @param <T> the result type
   * @return the action result
   */
  public static <T> T withArena(Function<MemorySegment, T> action) {
    return withArena(8192, action);
  }

  /**
   * Run an action with a thread-local segment of the specified size. If the size exceeds 8KB or a
   * nested/recursive call is detected, a temporary confined arena is used as a fallback.
   *
   * @param size required size in bytes
   * @param action the function to run with the segment
   * @param <T> the type of the result
   * @return the result of the function
   *     <p><strong>Safety:</strong> The provided {@link MemorySegment} is only valid for the
   *     duration of the {@code action} call. If the pool is already in use by the current thread, a
   *     fresh {@link Arena#ofConfined()} is allocated and closed immediately after the call, making
   *     any escaped segments invalid.
   */
  public static <T> T withArena(long size, Function<MemorySegment, T> action) {
    if (size <= 8192 && !IN_USE.get()) {
      IN_USE.set(true);
      try {
        return action.apply(SLAB.get());
      } finally {
        IN_USE.set(false);
      }
    }
    // Fallback to fresh confined arena for nested or oversized allocations
    try (Arena fallback = Arena.ofConfined()) {
      return action.apply(fallback.allocate(size, 8));
    }
  }

  /** Close the thread-local arena pool and release all allocated native memory. */
  public static void clear() {
    Arena old = POOL.get();
    POOL.set(Arena.ofAuto());
    SLAB.remove();
    // old might be ofAuto or ofConfined depending on state
    if (old != null) {
      try {
        old.close();
      } catch (UnsupportedOperationException _) {
        // ofAuto cannot be closed explicitly
      }
    }
  }
}
