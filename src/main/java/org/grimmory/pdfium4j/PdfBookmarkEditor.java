package org.grimmory.pdfium4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.grimmory.pdfium4j.exception.PdfiumException;
import org.grimmory.pdfium4j.internal.IoUtils;
import org.grimmory.pdfium4j.internal.ShimBindings;
import org.grimmory.pdfium4j.model.Bookmark;

/** Public editor to modify or reconstruct the outline tree (bookmarks) of a PDF file. */
public final class PdfBookmarkEditor {

  private PdfBookmarkEditor() {}

  private record FlatBookmark(String title, int pageIndex, int depth) {}

  private static void flattenBookmarks(List<Bookmark> bookmarks, List<FlatBookmark> flatList) {
    for (Bookmark bm : bookmarks) {
      if (bm.isInternal()) {
        flatList.add(new FlatBookmark(bm.title(), bm.pageIndex(), 0));
        flattenBookmarks(bm.children(), flatList);
      }
    }
  }

  /**
   * Sets the complete outline tree (bookmarks) of the PDF file from the source byte array.
   *
   * @param pdfBytes the source PDF file bytes
   * @param bookmarks the tree of bookmarks to set
   * @return the modified PDF file bytes
   * @throws IOException if an I/O error occurs
   */
  public static byte[] setBookmarks(byte[] pdfBytes, List<Bookmark> bookmarks) throws IOException {
    if (pdfBytes == null) {
      throw new IllegalArgumentException("pdfBytes must not be null");
    }
    if (bookmarks == null) {
      throw new IllegalArgumentException("bookmarks must not be null");
    }

    Path tempIn = IoUtils.createTempFile("pdfium4j-bookmarks-in-", ".pdf");
    try {
      Files.write(tempIn, pdfBytes);
      return setBookmarks(tempIn, bookmarks);
    } finally {
      Files.deleteIfExists(tempIn);
    }
  }

  /**
   * Sets the complete outline tree (bookmarks) of the PDF file at the given path.
   *
   * @param path the path to the PDF file
   * @param bookmarks the tree of bookmarks to set
   * @return the modified PDF file bytes
   * @throws IOException if an I/O error occurs
   */
  public static byte[] setBookmarks(Path path, List<Bookmark> bookmarks) throws IOException {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    if (bookmarks == null) {
      throw new IllegalArgumentException("bookmarks must not be null");
    }

    List<FlatBookmark> flatList = new ArrayList<>();
    flattenBookmarks(bookmarks, flatList);

    byte[] serialized;
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos)) {
      dos.writeInt(flatList.size());
      for (FlatBookmark fb : flatList) {
        dos.writeInt(fb.pageIndex());
        dos.writeInt(fb.depth());
        byte[] titleBytes = fb.title().getBytes(StandardCharsets.UTF_8);
        dos.writeInt(titleBytes.length);
        dos.write(titleBytes);
      }
      serialized = baos.toByteArray();
    }

    Path tempOut = IoUtils.createTempFile("pdfium4j-bookmarks-out-", ".pdf");
    try {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment srcPathSegment = arena.allocateFrom(path.toAbsolutePath().toString());
        MemorySegment dstPathSegment = arena.allocateFrom(tempOut.toAbsolutePath().toString());
        MemorySegment serializedSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, serialized);

        int rc =
            (int)
                ShimBindings.pdfium4jSetBookmarks()
                    .invokeExact(
                        srcPathSegment, dstPathSegment, serializedSegment, serialized.length);

        if (rc != 0) {
          throw new PdfiumException("Failed to set bookmarks in PDF, native error: " + rc);
        }
      } catch (Error e) {
        throw e;
      } catch (Throwable t) {
        throw new PdfiumException("Failed to set bookmarks", t);
      }

      byte[] resultBytes = Files.readAllBytes(tempOut);
      Files.move(
          tempOut, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return resultBytes;
    } finally {
      Files.deleteIfExists(tempOut);
    }
  }
}
