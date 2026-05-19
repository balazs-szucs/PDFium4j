package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.PageSize;
import org.junit.jupiter.api.Test;

public class LinearizeSample {

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
  public void runLinearizeSample() {
    try {
      System.out.println("=== STARTING LINEARIZATION SAMPLES ===");
      PdfiumLibrary.initialize();

      Path outputDir = Path.of("samples_output/linearized_samples");
      Files.createDirectories(outputDir);
      System.out.println("Output directory: " + outputDir.toAbsolutePath());

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf), "Input PDF must exist: " + inputPdf);

        String fileName = inputPdf.getFileName().toString();
        Path outputPdf = outputDir.resolve("linearized_" + fileName);

        System.out.println("\nProcessing: " + fileName);

        // 1. Save document with linearization enabled
        try (PdfDocument doc = PdfDocument.open(inputPdf)) {
          assertTrue(doc.pageCount() > 0);
          System.out.println("  Saving with setLinearize(true)...");
          doc.setLinearize(true);
          doc.save(outputPdf);
        }

        // 2. Programmatic validation via library loading
        System.out.println("  Verifying output file: " + outputPdf);
        assertTrue(Files.exists(outputPdf) && Files.size(outputPdf) > 0);

        try (PdfDocument doc = PdfDocument.open(outputPdf)) {
          assertTrue(doc.pageCount() > 0);
          try (PdfPage page = doc.page(0)) {
            PageSize size = page.size();
            System.out.println("  Verified saved page 0 size: " + size);
            assertTrue(size.width() > 0 && size.height() > 0);
          }
        }

        // 3. Double verification via native qpdf CLI tool!
        verifyLinearizationUsingQpdfCli(outputPdf);
        
        System.out.println("  Successfully verified: " + fileName);
      }

      System.out.println("\n=== LINEARIZATION SAMPLES COMPLETED SUCCESSFULLY ===");
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }

  private static void verifyLinearizationUsingQpdfCli(Path pdfPath) {
    try {
      System.out.println("  [CLI] Running qpdf --check-linearization on: " + pdfPath.getFileName());
      ProcessBuilder pb = new ProcessBuilder("/opt/homebrew/bin/qpdf", "--check-linearization", pdfPath.toAbsolutePath().toString());
      pb.redirectErrorStream(true);
      Process process = pb.start();
      String output;
      try (InputStream is = process.getInputStream()) {
        output = new String(is.readAllBytes());
      }
      int exitCode = process.waitFor();
      System.out.println("  [CLI] qpdf output:\n" + output.trim().indent(4));
      assertTrue(exitCode == 0 || exitCode == 3, "qpdf --check-linearization must exit with code 0 or 3, got: " + exitCode);
      assertTrue(output.contains("file is linearized") || output.contains("no linearization errors"), "qpdf CLI must explicitly confirm: 'file is linearized' or 'no linearization errors'");
      System.out.println("  [CLI] QPDF Verification Passed!");
    } catch (Exception e) {
      System.out.println("  [CLI WARNING] Could not verify with qpdf CLI: " + e.getMessage());
    }
  }
}
