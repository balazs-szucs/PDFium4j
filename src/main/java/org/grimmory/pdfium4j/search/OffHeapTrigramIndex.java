package org.grimmory.pdfium4j.search;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.internal.TrigramTokenizer;

/**
 * A highly optimized trigram full-text search index stored entirely off-heap.
 *
 * <p>Avoids Java heap retention and GC pressure under sustained concurrent searches by utilizing
 * Panama memory segments and a binary directory layout with O(log N) search times.
 */
public final class OffHeapTrigramIndex {
  private MemorySegment indexSegment = MemorySegment.NULL;
  private long pagesBaseOffset = 0;
  private int uniqueHashCount = 0;

  /**
   * Builds the off-heap trigram index for the given document and loads it into the target arena.
   *
   * @param doc the PDF document to index
   * @param arena the Arena to allocate the off-heap memory from
   */
  public void build(PdfDocument doc, Arena arena) {
    int count = doc.pageCount();
    Map<Long, List<Integer>> tempIndex = new HashMap<>();

    // Standard multi-way intersection in IntersectingCursor requires page lists to be sorted.
    // We iterate pages in document order (0 to count-1), which naturally produces sorted lists.
    for (int i = 0; i < count; i++) {
      try (PdfPage p = doc.page(i)) {
        String text = p.extractText().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) continue;

        long[] hashes = TrigramTokenizer.generateTrigramHashes(text);
        for (long hash : hashes) {
          tempIndex.computeIfAbsent(hash, _ -> new ArrayList<>(8)).add(i);
        }
      }
    }

    this.uniqueHashCount = tempIndex.size();
    if (uniqueHashCount == 0) {
      this.indexSegment = MemorySegment.NULL;
      this.pagesBaseOffset = 0;
      return;
    }

    // Extract and sort unique hashes for binary search directory
    long[] sortedHashes = new long[uniqueHashCount];
    int hashIdx = 0;
    for (long hash : tempIndex.keySet()) {
      sortedHashes[hashIdx++] = hash;
    }
    Arrays.sort(sortedHashes);

    int totalInts = 0;
    for (List<Integer> list : tempIndex.values()) {
      totalInts += list.size();
    }

    // Binary directory layout:
    // Offset 0: uniqueHashCount (4 bytes)
    // Offset 8: Directory of uniqueHashCount * 16 bytes.
    //           Each entry: [long hash (8 bytes)][int pageOffset (4 bytes)][int pageCount (4
    // bytes)]
    // Offset pagesBaseOffset: Flat pages segment of totalInts * 4 bytes
    long directoryOffset = 8;
    long pagesBaseOffset = directoryOffset + (long) uniqueHashCount * 16L;
    long totalBytes = pagesBaseOffset + (long) totalInts * 4L;

    MemorySegment seg = arena.allocate(totalBytes, 8);
    seg.set(ValueLayout.JAVA_INT, 0, uniqueHashCount);

    int currentIntOffset = 0;
    for (int i = 0; i < uniqueHashCount; i++) {
      long hash = sortedHashes[i];
      List<Integer> pageList = tempIndex.get(hash);
      int listCount = pageList.size();

      long dirEntryOffset = directoryOffset + (long) i * 16L;
      seg.set(ValueLayout.JAVA_LONG, dirEntryOffset, hash);
      seg.set(ValueLayout.JAVA_INT, dirEntryOffset + 8, currentIntOffset);
      seg.set(ValueLayout.JAVA_INT, dirEntryOffset + 12, listCount);

      long listBytesOffset = pagesBaseOffset + (long) currentIntOffset * 4L;
      for (int k = 0; k < listCount; k++) {
        seg.set(ValueLayout.JAVA_INT, listBytesOffset + (long) k * 4L, pageList.get(k));
      }
      currentIntOffset += listCount;
    }

    this.indexSegment = seg;
    this.pagesBaseOffset = pagesBaseOffset;
  }

  /**
   * Search the off-heap trigram index for page matches.
   *
   * @param query the text query to run
   * @return an IntCursor traversing matched page indices
   */
  public IntCursor search(String query) {
    if (indexSegment == MemorySegment.NULL || uniqueHashCount == 0) {
      return IntCursor.EMPTY;
    }

    String normalized = query.toLowerCase(Locale.ROOT);
    long[] queryHashes = TrigramTokenizer.generateTrigramHashes(normalized);
    if (queryHashes.length == 0) {
      return IntCursor.EMPTY;
    }

    int[][] matchInfo = new int[queryHashes.length][2];
    for (int i = 0; i < queryHashes.length; i++) {
      long hash = queryHashes[i];
      int dirIdx = binarySearch(hash);
      if (dirIdx == -1) {
        return IntCursor.EMPTY;
      }
      long dirEntryOffset = 8L + (long) dirIdx * 16L;
      matchInfo[i][0] = indexSegment.get(ValueLayout.JAVA_INT, dirEntryOffset + 8);
      matchInfo[i][1] = indexSegment.get(ValueLayout.JAVA_INT, dirEntryOffset + 12);
    }

    return new IntersectingCursor(indexSegment, pagesBaseOffset, matchInfo);
  }

  private int binarySearch(long targetHash) {
    int low = 0;
    int high = uniqueHashCount - 1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      long dirEntryOffset = 8L + (long) mid * 16L;
      long midHash = indexSegment.get(ValueLayout.JAVA_LONG, dirEntryOffset);
      if (midHash < targetHash) {
        low = mid + 1;
      } else if (midHash > targetHash) {
        high = mid - 1;
      } else {
        return mid;
      }
    }
    return -1;
  }

  /** Clear the off-heap index segment. */
  public void clear() {
    this.indexSegment = MemorySegment.NULL;
    this.pagesBaseOffset = 0;
    this.uniqueHashCount = 0;
  }
}
