package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.grimmory.pdfium4j.PdfBookmarkEditor;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfMerge;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.Bookmark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

public class MergeSample {

  private static final List<String> INPUT_PDFS = List.of(
      "../corpus/gutenberg/1063_The Cask of Amontillado.pdf",
      "../corpus/gutenberg/11_Alice's Adventures in Wonderland.pdf",
      "../corpus/gutenberg/1250_Anthem.pdf",
      "../corpus/gutenberg/1656_Apology.pdf",
      "../corpus/gutenberg/1719_The Ballad of the White Horse.pdf",
      "../corpus/gutenberg/1952_The Yellow Wallpaper.pdf",
      "../corpus/gutenberg/2002_Sonnets from the Portuguese.pdf",
      "../corpus/gutenberg/215_The call of the wild.pdf",
      "../corpus/gutenberg/10007_Carmilla.pdf",
      "../corpus/gutenberg/1658_Phaedo.pdf"
  );

  private static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  public void runMergeSample() {
    try {
      System.out.println("=== STARTING MERGE SAMPLES ===");
      Path outputDir = Path.of("samples_output/merged_samples");
      Files.createDirectories(outputDir);

      for (int i = 0; i < 2; i++) { // Limit loops in samples
        Path docAPath = Path.of(INPUT_PDFS.get(i));
        Path docBPath = Path.of(INPUT_PDFS.get((i + 1) % INPUT_PDFS.size()));

        String nameA = docAPath.getFileName().toString();
        String nameB = docBPath.getFileName().toString();
        Path outputPdf = outputDir.resolve("merged_pair_" + i + "_" + nameA.substring(0, Math.min(nameA.length(), 6)) + "_" + nameB.substring(0, Math.min(nameB.length(), 6)) + ".pdf");

        int countA = 0;
        int countB = 0;

        try (PdfDocument docA = PdfDocument.open(docAPath);
             PdfDocument docB = PdfDocument.open(docBPath)) {
          countA = docA.pageCount();
          countB = docB.pageCount();
        }

        // Perform merge
        PdfMerge.merge(List.of(docAPath, docBPath), outputPdf);
        assertTrue(Files.exists(outputPdf) && Files.size(outputPdf) > 0);

        try (PdfDocument mergedDoc = PdfDocument.open(outputPdf)) {
          assertEquals(countA + countB, mergedDoc.pageCount(), "Page counts must sum perfectly!");
        }
      }
    } catch (Throwable t) {
      fail(t);
    }
  }

  private static byte[] minimal3PagePdf() {
    String pdf =
        """
        %PDF-1.4
        1 0 obj
        << /Type /Catalog /Pages 2 0 R >>
        endobj
        2 0 obj
        << /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>
        endobj
        3 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
        endobj
        4 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
        endobj
        5 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
        endobj
        xref
        0 6
        0000000000 65535 f\r
        0000000009 00000 n\r
        0000000058 00000 n\r
        0000000120 00000 n\r
        0000000187 00000 n\r
        0000000254 00000 n\r
        trailer
        << /Size 6 /Root 1 0 R >>
        startxref
        321
        %%EOF
        """;
    return pdf.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  public void runMergeWithBookmarksSample() {
    try {
      System.out.println("=== STARTING MERGE WITH BOOKMARKS SAMPLE ===");
      Path outputDir = Path.of("samples_output/bookmark_merges");
      Files.createDirectories(outputDir);

      Path src1 = outputDir.resolve("bookmark_src1.pdf");
      Path src2 = outputDir.resolve("bookmark_src2.pdf");
      Path mergedDest = outputDir.resolve("bookmark_merged.pdf");

      Files.write(src1, minimal3PagePdf());
      Files.write(src2, minimal3PagePdf());

      // Set recursive/external outlines
      List<Bookmark> bm1 = List.of(
          new Bookmark("Root 1", 0, List.of()),
          new Bookmark("Root 2", 1, List.of(
              new Bookmark("Child 2.1", 2, List.of())
          )),
          new Bookmark("External Target 1", -1, List.of(
              new Bookmark("External Child 1.1", 1, List.of())
          ))
      );
      List<Bookmark> bm2 = List.of(
          new Bookmark("Root 3", 0, List.of()),
          new Bookmark("External Target 2", -1, List.of())
      );

      PdfBookmarkEditor.setBookmarks(src1, bm1);
      PdfBookmarkEditor.setBookmarks(src2, bm2);

      // Perform bookmark-preserving merge
      PdfMerge.mergeFilesWithBookmarks(List.of(src1, src2), mergedDest);

      // 1. QPDF structural verification
      ProcessBuilder pb = new ProcessBuilder("qpdf", "--check", mergedDest.toAbsolutePath().toString());
      int qpdfExitCode = pb.start().waitFor();
      assertTrue(qpdfExitCode == 0 || qpdfExitCode == 3, "Merged PDF must be structurally sound according to QPDF");

      // 2. PDFBox outlines verification
      byte[] mergedBytes = Files.readAllBytes(mergedDest);
      try (PDDocument pdDoc = Loader.loadPDF(mergedBytes)) {
        assertEquals(6, pdDoc.getNumberOfPages(), "Page count must sum to 6");

        PDDocumentOutline outline = pdDoc.getDocumentCatalog().getDocumentOutline();
        assertNotNull(outline, "Outline tree must exist in the merged document");

        PDOutlineItem item = outline.getFirstChild();
        assertNotNull(item, "There must be at least one outline element");

        // Verify flat sequential DFS sequence:
        // Expected elements: Root 1 (page 0), Root 2 (page 1), Child 2.1 (page 2), Root 3 (page 3)
        // External Targets (and their descendants) must be completely skipped/dropped.
        assertEquals("Root 1", item.getTitle());
        item = item.getNextSibling();
        assertNotNull(item);
        assertEquals("Root 2", item.getTitle());
        item = item.getNextSibling();
        assertNotNull(item);
        assertEquals("Child 2.1", item.getTitle());
        item = item.getNextSibling();
        assertNotNull(item);
        assertEquals("Root 3", item.getTitle());
        item = item.getNextSibling();
        assertNull(item, "All other elements (external targets) must be skipped");
      }

      // 3. Test in-memory mergeWithBookmarks returning byte[]
      try (PdfDocument doc1 = PdfDocument.open(src1);
           PdfDocument doc2 = PdfDocument.open(src2)) {
        byte[] inMemoryMergedBytes = PdfMerge.mergeWithBookmarks(List.of(doc1, doc2));
        assertNotNull(inMemoryMergedBytes);
        assertTrue(inMemoryMergedBytes.length > 0);

        try (PDDocument pdDoc2 = Loader.loadPDF(inMemoryMergedBytes)) {
          assertEquals(6, pdDoc2.getNumberOfPages());
          PDDocumentOutline outline2 = pdDoc2.getDocumentCatalog().getDocumentOutline();
          assertNotNull(outline2);
          assertEquals("Root 1", outline2.getFirstChild().getTitle());
        }
      }

      System.out.println("=== MERGE WITH BOOKMARKS SAMPLE COMPLETED SUCCESSFULLY ===");
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }
}
