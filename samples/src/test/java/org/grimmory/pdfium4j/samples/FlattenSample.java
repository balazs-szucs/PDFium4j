package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.grimmory.pdfium4j.FlattenConfig;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.AnnotationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

public class FlattenSample {

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
  public void runFlattenSample() {
    try {
      Path outputDir = Path.of("samples_output/flatten_samples");
      Files.createDirectories(outputDir);

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf), "Input PDF must exist: " + inputPdf);

        String fileName = inputPdf.getFileName().toString();
        Path outputPdf = outputDir.resolve("flattened_" + fileName);

        try (PdfDocument doc = PdfDocument.open(inputPdf)) {
          assertTrue(doc.pageCount() > 0);
          try (PdfPage page = doc.page(0)) {
            try {
              int initialCount = page.annotations().size();
              
              // Programmatically inject interactive elements to guarantee the flatten operation has real vector targets.
              page.createAnnotation(AnnotationType.HIGHLIGHT, 100.0f, 100.0f, 300.0f, 150.0f, "Test highlight contents to flatten");
              page.createAnnotation(AnnotationType.LINK, 150.0f, 300.0f, 400.0f, 350.0f, "https://github.com/google/pdfium4j");

              int addedCount = page.annotations().size();
              assertEquals(initialCount + 2, addedCount);

              // FLAT_NORMALDISPLAY burns vector annotations into the base raster content stream.
              assertTrue(page.flatten(false));
              assertEquals(0, page.annotations().size());
            } catch (UnsupportedOperationException e) {
              System.out.println("Skipping annotation injection/flattening on this platform: " + e.getMessage());
            }
          }
          doc.save(outputPdf);
        }

        // Use qpdf to structurally enforce that the flattened document contains no broken streams
        ProcessBuilder pb = new ProcessBuilder("qpdf", "--check", outputPdf.toAbsolutePath().toString());
        int exitCode = pb.start().waitFor();
        assertTrue(exitCode == 0 || exitCode == 3);
      }
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  public void runAdvancedRasterJPEG() {
    runAdvancedFlatten(new FlattenConfig()
        .mode(FlattenConfig.FlattenMode.RASTER)
        .dpi(72)
        .scale(1.0f)
        .encoding(FlattenConfig.ImageEncoding.JPEG)
        .jpegQuality(85), "jpeg");
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  public void runAdvancedRasterPNG() {
    runAdvancedFlatten(new FlattenConfig()
        .mode(FlattenConfig.FlattenMode.RASTER)
        .dpi(72)
        .scale(1.0f)
        .encoding(FlattenConfig.ImageEncoding.PNG)
        .pngCompressionLevel(6), "png");
  }

  private static void runAdvancedFlatten(FlattenConfig config, String suffix) {
    try {
      Path outputDir = Path.of("samples_output/advanced_flatten_" + suffix);
      Files.createDirectories(outputDir);

      for (String pdfPathStr : List.of(INPUT_PDFS.get(0))) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf));

        String fileName = inputPdf.getFileName().toString();
        Path outputPdf = outputDir.resolve("rasterized_" + fileName);

        try (PdfDocument doc = PdfDocument.open(inputPdf)) {
          assertTrue(doc.pageCount() > 0);
          
          byte[] resultBytes = doc.flattenAdvanced(config);
          assertTrue(resultBytes != null && resultBytes.length > 0);
          
          Files.write(outputPdf, resultBytes);
        }

        // Use qpdf to structurally enforce that the flattened document contains no broken streams
        ProcessBuilder pb = new ProcessBuilder("qpdf", "--check", outputPdf.toAbsolutePath().toString());
        int exitCode = pb.start().waitFor();
        assertTrue(exitCode == 0 || exitCode == 3);
      }
    } catch (Throwable t) {
      fail(t);
    }
  }
}
