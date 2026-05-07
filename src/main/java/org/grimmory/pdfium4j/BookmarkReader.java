package org.grimmory.pdfium4j;
 
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.grimmory.pdfium4j.internal.FfmHelper;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.internal.ShimBindings;
import org.grimmory.pdfium4j.model.Bookmark;
 
/**
 * Internal helper to read the document outline (bookmarks) using optimized shim bindings.
 */
final class BookmarkReader {
 
  private BookmarkReader() {}
 
  static List<Bookmark> readBookmarks(MemorySegment docHandle) {
    try {
      MemorySegment first = (MemorySegment) ShimBindings.pdfium4j_bookmark_first.invokeExact(docHandle);
      if (FfmHelper.isNull(first)) {
        return List.of();
      }
      return collectBookmarks(docHandle, first);
    } catch (Throwable t) {
      PdfiumLibrary.ignore(t);
      return List.of();
    }
  }
 
  private static List<Bookmark> collectBookmarks(MemorySegment docHandle, MemorySegment current) throws Throwable {
    List<Bookmark> result = new ArrayList<>();
    while (!FfmHelper.isNull(current)) {
      result.add(toBookmark(docHandle, current));
      current = (MemorySegment) ShimBindings.pdfium4j_bookmark_next.invokeExact(docHandle, current);
    }
    return List.copyOf(result);
  }
 
  private static Bookmark toBookmark(MemorySegment docHandle, MemorySegment bm) throws Throwable {
    String title = getBookmarkTitle(bm);
    int pageIndex = (int) ShimBindings.pdfium4j_bookmark_page_index.invokeExact(docHandle, bm);
    
    MemorySegment firstChild = (MemorySegment) ShimBindings.pdfium4j_bookmark_first_child.invokeExact(docHandle, bm);
    List<Bookmark> children = FfmHelper.isNull(firstChild) ? List.of() : collectBookmarks(docHandle, firstChild);
    
    return new Bookmark(title, pageIndex, children);
  }
 
  private static String getBookmarkTitle(MemorySegment bm) throws Throwable {
    try (var _ = ScratchBuffer.acquireScope()) {
      int needed = (int) ShimBindings.pdfium4j_bookmark_title.invokeExact(bm, MemorySegment.NULL, 0);
      if (needed <= 1) return "";
 
      MemorySegment buf = ScratchBuffer.get(needed);
      int copied = (int) ShimBindings.pdfium4j_bookmark_title.invokeExact(bm, buf, needed);
      if (copied <= 1) return "";
 
      return buf.reinterpret(needed).getString(0);
    }
  }
}
