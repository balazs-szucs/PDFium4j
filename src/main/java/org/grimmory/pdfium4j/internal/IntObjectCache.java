package org.grimmory.pdfium4j.internal;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** A simple LRU cache for int keys to Object values, supporting a byte-budget eviction policy. */
public class IntObjectCache<T> {
  private final long maxBytes;
  private long currentBytes;
  private final LinkedHashMap<Integer, Entry<T>> map;

  private record Entry<T>(T value, long size) {}

  public IntObjectCache(long maxBytes) {
    this.maxBytes = maxBytes;
    this.currentBytes = 0;
    this.map = new LinkedHashMap<>(16, 0.75f, true);
  }

  public synchronized T get(int key) {
    Entry<T> entry = map.get(key);
    return entry != null ? entry.value : null;
  }

  public synchronized void put(int key, T value, long size) {
    if (size > maxBytes) {
      // Too large to ever fit, just evict if it was there and return
      remove(key);
      onEvict(value);
      return;
    }

    Entry<T> old = map.put(key, new Entry<>(value, size));
    if (old != null) {
      currentBytes -= old.size;
    }
    currentBytes += size;

    evictIfNecessary();
  }

  private void evictIfNecessary() {
    if (currentBytes <= maxBytes) return;

    Iterator<Map.Entry<Integer, Entry<T>>> it = map.entrySet().iterator();
    while (it.hasNext() && currentBytes > maxBytes) {
      Map.Entry<Integer, Entry<T>> entry = it.next();
      it.remove();
      currentBytes -= entry.getValue().size;
      onEvict(entry.getValue().value);
    }
  }

  public synchronized T remove(int key) {
    Entry<T> entry = map.remove(key);
    if (entry != null) {
      currentBytes -= entry.size;
      return entry.value;
    }
    return null;
  }

  public synchronized void clear() {
    for (Entry<T> entry : map.values()) {
      onEvict(entry.value);
    }
    map.clear();
    currentBytes = 0;
  }

  public synchronized long currentBytes() {
    return currentBytes;
  }

  /**
   * Hook for subclasses or custom implementations to handle eviction (e.g. closing native handles).
   */
  protected void onEvict(T value) {
    // Default: do nothing. Specific caches can override this via anonymous subclasses.
  }
}
