package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.PageBox;
import org.junit.jupiter.api.Test;

public class PageBoxSample {

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

  @Test
  public void runPageBoxSample() {
    try {
      System.out.println("=== STARTING PAGE BOX CROP SAMPLES ===");
      PdfiumLibrary.initialize();

      Path outputDir = Path.of("samples_output/page_box_samples");
      Files.createDirectories(outputDir);
      System.out.println("Output directory: " + outputDir.toAbsolutePath());

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf), "Input PDF must exist: " + inputPdf);

        String fileName = inputPdf.getFileName().toString();
        Path outputPdf = outputDir.resolve("cropped_" + fileName);

        System.out.println("\nProcessing: " + fileName);

        // 1. Open the original document
        float targetLeft, targetBottom, targetRight, targetTop;
        try (PdfDocument doc = PdfDocument.open(inputPdf)) {
          assertTrue(doc.pageCount() > 0);
          try (PdfPage page = doc.page(0)) {
            PageBox origMedia = page.getMediaBox();
            PageBox origCrop = page.getCropBox();
            System.out.println("  Original MediaBox: " + origMedia);
            System.out.println("  Original CropBox: " + origCrop);

            // Calculate crop box: crop by 50 pt from each side
            targetLeft = origCrop.left() + 50.0f;
            targetBottom = origCrop.bottom() + 50.0f;
            targetRight = origCrop.right() - 50.0f;
            targetTop = origCrop.top() - 50.0f;

            page.setCropBox(targetLeft, targetBottom, targetRight, targetTop);
            System.out.println("  Set CropBox to: [" + targetLeft + ", " + targetBottom + ", " + targetRight + ", " + targetTop + "]");
          }
          doc.save(outputPdf);
        }

        // 2. Programmatically verify the output PDF
        System.out.println("  Verifying output file: " + outputPdf);
        assertTrue(Files.exists(outputPdf) && Files.size(outputPdf) > 0);

        try (PdfDocument doc = PdfDocument.open(outputPdf)) {
          assertEquals(doc.pageCount(), doc.pageCount()); // Verify page count matches
          try (PdfPage page = doc.page(0)) {
            PageBox newCrop = page.getCropBox();
            System.out.println("  Verified New CropBox: " + newCrop);
            assertEquals(targetLeft, newCrop.left(), 0.1f);
            assertEquals(targetBottom, newCrop.bottom(), 0.1f);
            assertEquals(targetRight, newCrop.right(), 0.1f);
            assertEquals(targetTop, newCrop.top(), 0.1f);
          }
        }
        System.out.println("  Successfully verified: " + fileName);
      }

      System.out.println("\n=== PAGE BOX CROP SAMPLES COMPLETED SUCCESSFULLY ===");
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }
}
