package org.grimmory.pdfium4j.model;

import java.util.List;

/**
 * A bookmark (outline item) in a PDF document.
 *
 * @param title the bookmark text
 * @param pageIndex the target page (0-based), or -1 if the destination is external/unresolvable
 * @param children child bookmarks (may be empty, never null)
 */
public record Bookmark(String title, int pageIndex, List<Bookmark> children) {
  public Bookmark {
    children = children != null ? List.copyOf(children) : List.of();
  }

  /**
   * Returns true if the bookmark's destination points to a valid internal page (pageIndex >= 0).
   * URI / LAUNCH / REMOTE_GOTO bookmarks point to external destinations and have pageIndex < 0.
   */
  public boolean isInternal() {
    return pageIndex >= 0;
  }
}
