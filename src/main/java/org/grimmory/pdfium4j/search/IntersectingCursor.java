package org.grimmory.pdfium4j.search;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.NoSuchElementException;

/** An {@link IntCursor} that computes the intersection of multiple sorted off-heap page lists. */
public final class IntersectingCursor implements IntCursor {
  private final MemorySegment indexSegment;
  private final long pagesBaseOffset;
  private final int[][] cursors; // [queryIndex][0 = offset, 1 = count, 2 = currentPosition]
  private int nextMatch = -1;
  private boolean checked = false;

  /**
   * Constructs a new IntersectingCursor.
   *
   * @param indexSegment the off-heap index segment
   * @param pagesBaseOffset the base byte offset where page lists start
   * @param matchInfo array containing [offsetInInts, count] for each matched query trigram
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP2")
  public IntersectingCursor(MemorySegment indexSegment, long pagesBaseOffset, int[][] matchInfo) {
    this.indexSegment = indexSegment;
    this.pagesBaseOffset = pagesBaseOffset;
    this.cursors = new int[matchInfo.length][3];
    for (int i = 0; i < matchInfo.length; i++) {
      cursors[i][0] = matchInfo[i][0]; // offset in ints
      cursors[i][1] = matchInfo[i][1]; // count
      cursors[i][2] = 0; // current position
    }
  }

  @Override
  public boolean hasNext() {
    if (!checked) {
      nextMatch = findNextMatch();
      checked = true;
    }
    return nextMatch != -1;
  }

  @Override
  public int nextInt() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    int val = nextMatch;
    checked = false;
    return val;
  }

  private int findNextMatch() {
    if (cursors.length == 0) return -1;

    // Standard multi-way intersection for sorted arrays
    while (true) {
      // Check if any list is exhausted
      for (int[] c : cursors) {
        if (c[2] >= c[1]) return -1;
      }

      // Find the maximum value at the current positions
      int maxVal = -1;
      for (int[] c : cursors) {
        int val = getPageValue(c[0], c[2]);
        if (val > maxVal) {
          maxVal = val;
        }
      }

      // Check if all lists match this maximum value
      boolean allMatch = true;
      for (int[] c : cursors) {
        int val = getPageValue(c[0], c[2]);
        if (val < maxVal) {
          allMatch = false;
          // Advance lists that are smaller than maxVal
          while (c[2] < c[1] && getPageValue(c[0], c[2]) < maxVal) {
            c[2]++;
          }
        }
      }

      if (allMatch) {
        // Advance all positions and return the match
        for (int[] c : cursors) {
          c[2]++;
        }
        return maxVal;
      }
    }
  }

  private int getPageValue(int offsetInInts, int position) {
    long byteOffset = pagesBaseOffset + ((long) offsetInInts + position) * 4L;
    return indexSegment.get(ValueLayout.JAVA_INT, byteOffset);
  }
}
