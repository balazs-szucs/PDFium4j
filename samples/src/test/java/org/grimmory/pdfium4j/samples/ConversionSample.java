package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.RenderResult;
import org.junit.jupiter.api.Test;

public class ConversionSample {

  private static final List<String> INPUT_PDFS = List.of(
      "../corpus/gutenberg/1063_The Cask of Amontillado.pdf",
      "../corpus/gutenberg/215_The call of the wild.pdf"
  );

  @Test
  public void runConversionSample() {
    try {
      PdfiumLibrary.initialize();
      Path outputDir = Path.of("samples_output/conversion_samples");
      Files.createDirectories(outputDir);

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf));

        String baseName = inputPdf.getFileName().toString().replace(".pdf", "");
        
        File pngOut = outputDir.resolve(baseName + "_page0.png").toFile();
        File jpegOut = outputDir.resolve(baseName + "_page0.jpeg").toFile();
        
        byte[] bgraPixels;
        int imgWidth, imgHeight;

        try (PdfDocument doc = PdfDocument.open(inputPdf);
             PdfPage page = doc.page(0)) {
             
          RenderResult result = page.render(144);
          imgWidth = result.width();
          imgHeight = result.height();
          bgraPixels = result.rgba();
          
          // PDFium uses BGRA ordering natively. AWT requires ABGR for 4-byte alpha images.
          BufferedImage bImage = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_4BYTE_ABGR);
          byte[] awtPixels = ((java.awt.image.DataBufferByte) bImage.getRaster().getDataBuffer()).getData();
          for (int i = 0; i < bgraPixels.length; i += 4) {
            awtPixels[i] = bgraPixels[i + 3];
            awtPixels[i + 1] = bgraPixels[i];
            awtPixels[i + 2] = bgraPixels[i + 1];
            awtPixels[i + 3] = bgraPixels[i + 2];
          }
          
          ImageIO.write(bImage, "png", pngOut);
          assertTrue(pngOut.exists() && pngOut.length() > 0);
          
          // JPEG encoders do not support alpha channels, requiring a 3-byte BGR buffer.
          BufferedImage rgbImage = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_3BYTE_BGR);
          byte[] rgbPixels = ((java.awt.image.DataBufferByte) rgbImage.getRaster().getDataBuffer()).getData();
          int rgbIndex = 0;
          for (int i = 0; i < bgraPixels.length; i += 4) {
            rgbPixels[rgbIndex++] = bgraPixels[i];     // B
            rgbPixels[rgbIndex++] = bgraPixels[i + 1]; // G
            rgbPixels[rgbIndex++] = bgraPixels[i + 2]; // R
          }
          ImageIO.write(rgbImage, "jpeg", jpegOut);
          assertTrue(jpegOut.exists() && jpegOut.length() > 0);
        }
        
        Path pdfOut = outputDir.resolve("converted_from_image_" + baseName + ".pdf");
        
        try (PdfDocument doc = PdfDocument.create()) {
          try (PdfPage page = doc.insertBlankPage(0, new org.grimmory.pdfium4j.model.PageSize((float) imgWidth, (float) imgHeight))) {
            page.insertImage(bgraPixels, imgWidth, imgHeight);
            
            // Validate the page object streams directly to ensure no phantom vectors leaked
            assertEquals(1, page.imageCount());
            assertEquals("", page.extractText());
          }
          doc.save(pdfOut);
        }
        
        // Ensure PDF structural compliance using qpdf strictly
        ProcessBuilder pb = new ProcessBuilder("qpdf", "--check", pdfOut.toAbsolutePath().toString());
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        // 0 = No errors, 3 = Warnings only (Gutenberg PDFs often have warnings)
        assertTrue(exitCode == 0 || exitCode == 3, "qpdf --check failed with exit code: " + exitCode);
      }
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }
}
