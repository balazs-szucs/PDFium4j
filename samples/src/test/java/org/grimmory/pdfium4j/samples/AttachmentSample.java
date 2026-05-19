package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.PdfAttachment;
import org.junit.jupiter.api.Test;

public class AttachmentSample {

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
  public void runAttachmentSample() {
    try {
      System.out.println("=== STARTING ATTACHMENT SAMPLES ===");
      PdfiumLibrary.initialize();

      Path outputDir = Path.of("samples_output/attachment_samples");
      Files.createDirectories(outputDir);
      System.out.println("Output directory: " + outputDir.toAbsolutePath());

      byte[] attachBytes = "This is the embedded text file data content!".getBytes(java.nio.charset.StandardCharsets.UTF_8);

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf), "Input PDF must exist: " + inputPdf);

        String fileName = inputPdf.getFileName().toString();
        Path outputPdf = outputDir.resolve("attached_" + fileName);

        System.out.println("\nProcessing: " + fileName);

        // 1. Open original PDF and add an attachment
        try (PdfDocument doc = PdfDocument.open(inputPdf)) {
          assertTrue(doc.pageCount() > 0);
          System.out.println("  Adding embedded attachment: attachment_data.txt");
          doc.addAttachment("attachment_data.txt", attachBytes, Map.of(
              "Desc", "Sample embedded file attachment for testing",
              "CreationDate", "D:20260518120000Z"
          ));
          doc.save(outputPdf);
        }

        // 2. Programmatically verify the output PDF
        System.out.println("  Verifying output file: " + outputPdf);
        assertTrue(Files.exists(outputPdf) && Files.size(outputPdf) > 0);

        try (PdfDocument doc = PdfDocument.open(outputPdf)) {
          List<PdfAttachment> attachments = doc.attachments();
          System.out.println("  Embedded attachment count: " + attachments.size());
          assertEquals(1, attachments.size(), "Should have exactly 1 attachment");
          
          PdfAttachment att = attachments.getFirst();
          System.out.println("  Verified attachment [0]: " + att.name() + " (" + att.size() + " bytes)");
          assertEquals("attachment_data.txt", att.name());
          assertEquals(attachBytes.length, att.size());
          assertEquals("Sample embedded file attachment for testing", att.description().orElse(""));
        }
        System.out.println("  Successfully verified: " + fileName);
      }

      System.out.println("\n=== ATTACHMENT SAMPLES COMPLETED SUCCESSFULLY ===");
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }
}
