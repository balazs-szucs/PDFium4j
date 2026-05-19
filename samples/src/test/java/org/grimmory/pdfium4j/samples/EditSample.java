package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.AnnotationType;
import org.grimmory.pdfium4j.model.PageBox;
import org.grimmory.pdfium4j.model.PageSize;
import org.grimmory.pdfium4j.model.PdfAttachment;
import org.grimmory.pdfium4j.model.TextCharInfo;
import org.junit.jupiter.api.Test;

public class EditSample {

  @Test
  public void runWorkflowSample() {
    try {
      System.out.println("=== STARTING CONSOLIDATED EDIT WORKFLOW ===");
      PdfiumLibrary.initialize();

      Path outputDir = Path.of("samples_output/consolidated_samples");
      Files.createDirectories(outputDir);

      Path inputPdf = Path.of("../corpus/gutenberg/1063_The Cask of Amontillado.pdf");
      assertTrue(Files.exists(inputPdf), "Input PDF must exist: " + inputPdf);

      Path outputPdf = outputDir.resolve("edited_workflow.pdf");
      System.out.println("Processing: " + inputPdf.getFileName());

      // 1. Edit Page boundaries, Redact, Flatten, and Attach
      try (PdfDocument doc = PdfDocument.open(inputPdf)) {
        assertTrue(doc.pageCount() > 0);

        try (PdfPage page = doc.page(0)) {
          PageBox originalCrop = page.getCropBox();
          System.out.println("  Original CropBox: " + originalCrop);

          // Crop page
          page.setCropBox(
              originalCrop.left() + 30.0f,
              originalCrop.bottom() + 30.0f,
              originalCrop.right() - 30.0f,
              originalCrop.top() - 30.0f
          );
          System.out.println("  Tighter CropBox set.");

          // Create dynamic annotation
          System.out.println("  Creating highlight annotation to flatten...");
          page.createAnnotation(AnnotationType.HIGHLIGHT, 120.0f, 120.0f, 250.0f, 180.0f, "Dynamic workflow annot");
          assertEquals(1, page.annotations().size(), "Should have exactly 1 active annotation before flatten");

          // Flatten annotations
          System.out.println("  Flattening page annotations...");
          boolean flatOk = page.flatten(false);
          assertTrue(flatOk, "Flatten must succeed");
          assertEquals(0, page.annotations().size(), "Should have 0 active annotations after flatten");

          // Dynamic redaction of the word "Amontillado"
          System.out.println("  Applying secure word-based redaction for 'Amontillado'...");
          redactWord(page, "Amontillado");
        }

        // Add attachment
        System.out.println("  Adding attachment...");
        doc.addAttachment("sample_workflow.txt", "Consolidated edit workflow!".getBytes(), Map.of(
            "Desc", "Workflow Attachment",
            "CreationDate", "D:20260518120000Z"
        ));

        doc.save(outputPdf);
        System.out.println("  Edited workflow saved to: " + outputPdf);
      }

      // 2. Programmatic Verification
      System.out.println("  Verifying output workflow...");
      assertTrue(Files.exists(outputPdf) && Files.size(outputPdf) > 0);

      try (PdfDocument doc = PdfDocument.open(outputPdf)) {
        assertTrue(doc.pageCount() > 0);

        // Verify attachment
        List<PdfAttachment> atts = doc.attachments();
        assertEquals(1, atts.size());
        assertEquals("sample_workflow.txt", atts.getFirst().name());
        assertEquals("Workflow Attachment", atts.getFirst().description().orElse(""));

        // Verify page bounds and text redaction on page 0
        try (PdfPage page = doc.page(0)) {
          PageSize size = page.size();
          assertTrue(size.width() > 0 && size.height() > 0);
          System.out.println("  Verified page size: " + size);
          
          String text = page.extractText();
          assertFalse(text.contains("Amontillado"), "The redacted word 'Amontillado' must be completely removed from the text stream!");
          assertEquals(0, page.annotations().size(), "Saved output must have all annotations flattened");
        }
      }

      System.out.println("=== CONSOLIDATED EDIT WORKFLOW COMPLETED SUCCESSFULLY ===");
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }

  private static void redactWord(PdfPage page, String targetWord) {
    List<TextCharInfo> chars = page.extractTextWithBounds();
    StringBuilder sb = new StringBuilder();
    for (TextCharInfo c : chars) {
      sb.append(c.character());
    }
    String fullText = sb.toString();
    int idx = 0;
    while ((idx = fullText.indexOf(targetWord, idx)) != -1) {
      float left = Float.MAX_VALUE;
      float bottom = Float.MAX_VALUE;
      float right = Float.MIN_VALUE;
      float top = Float.MIN_VALUE;
      for (int i = 0; i < targetWord.length(); i++) {
        TextCharInfo c = chars.get(idx + i);
        left = Math.min(left, (float) c.left());
        bottom = Math.min(bottom, (float) c.bottom());
        right = Math.max(right, (float) c.right());
        top = Math.max(top, (float) c.top());
      }
      page.redact(left - 1.0f, bottom - 1.0f, right + 1.0f, top + 1.0f, 0, 0, 0, 255);
      idx += targetWord.length();
    }
  }
}
