package org.grimmory.pdfium4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.grimmory.pdfium4j.internal.IoUtils;
import org.grimmory.pdfium4j.model.Bookmark;

/**
 * High-level utility to merge multiple PDF documents or files. Provides APIs to perform standard
 * fast-path merges and outline-preserving (bookmark) merges.
 */
public final class PdfMerge {

  private PdfMerge() {}

  private static void translateAndFlattenBookmarks(
      List<Bookmark> bookmarks, int offset, List<Bookmark> flatList) {
    for (Bookmark bm : bookmarks) {
      if (bm.isInternal()) {
        flatList.add(new Bookmark(bm.title(), bm.pageIndex() + offset, List.of()));
        translateAndFlattenBookmarks(bm.children(), offset, flatList);
      }
    }
  }

  /**
   * Merges multiple PDF files into a single output PDF file using the fast-path API.
   *
   * @param sources the paths to the source PDF files
   * @param destination the path to save the merged PDF file
   * @throws IOException if an I/O error occurs
   */
  public static void merge(List<Path> sources, Path destination) throws IOException {
    PdfDocument.merge(sources, destination);
  }

  /**
   * Merges multiple PDF files into a single output PDF file using the fast-path API.
   *
   * @param sources the paths to the source PDF files
   * @param destination the path to save the merged PDF file
   * @throws IOException if an I/O error occurs
   */
  public static void mergeFiles(List<Path> sources, Path destination) throws IOException {
    PdfDocument.merge(sources, destination);
  }

  /**
   * Merges multiple open PDF documents into a single merged PDF document as a byte array,
   * preserving all internal bookmarks in a flat depth-first structure.
   *
   * @param sources the list of open PdfDocument sources
   * @return the byte array of the merged PDF
   * @throws IOException if an I/O error occurs
   */
  public static byte[] mergeWithBookmarks(List<PdfDocument> sources) throws IOException {
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("Source list cannot be null or empty");
    }

    List<Bookmark> mergedBookmarks = new ArrayList<>();
    int runningOffset = 0;

    try (PdfDocument destDoc = PdfDocument.create()) {
      for (PdfDocument srcDoc : sources) {
        translateAndFlattenBookmarks(srcDoc.bookmarks(), runningOffset, mergedBookmarks);
        runningOffset += srcDoc.pageCount();
        destDoc.importAllPages(srcDoc);
      }

      Path temp = IoUtils.createTempFile("pdfium4j-merge-", ".pdf");
      try {
        destDoc.save(temp);
        if (!mergedBookmarks.isEmpty()) {
          return PdfBookmarkEditor.setBookmarks(temp, mergedBookmarks);
        } else {
          return Files.readAllBytes(temp);
        }
      } finally {
        Files.deleteIfExists(temp);
      }
    }
  }

  /**
   * Merges multiple PDF files into a single output PDF file, preserving all internal bookmarks in a
   * flat depth-first structure.
   *
   * @param sources the paths to the source PDF files
   * @param destination the path to save the merged PDF file
   * @throws IOException if an I/O error occurs
   */
  public static void mergeFilesWithBookmarks(List<Path> sources, Path destination)
      throws IOException {
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("Source list cannot be null or empty");
    }
    if (destination == null) {
      throw new IllegalArgumentException("Destination cannot be null");
    }

    List<Bookmark> mergedBookmarks = new ArrayList<>();
    int runningOffset = 0;

    try (PdfDocument destDoc = PdfDocument.create()) {
      for (Path srcPath : sources) {
        try (PdfDocument srcDoc = PdfDocument.open(srcPath)) {
          translateAndFlattenBookmarks(srcDoc.bookmarks(), runningOffset, mergedBookmarks);
          runningOffset += srcDoc.pageCount();
          destDoc.importAllPages(srcDoc);
        }
      }
      destDoc.save(destination);
    }

    if (!mergedBookmarks.isEmpty()) {
      PdfBookmarkEditor.setBookmarks(destination, mergedBookmarks);
    }
  }
}
