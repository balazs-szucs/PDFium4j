package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.grimmory.pdfium4j.model.PdfProcessingPolicy;
import org.junit.jupiter.api.Test;

public class RepairVerificationTest {

  @Test
  public void testRepairBug2004951() throws IOException {
    Path corruptPath = Paths.get("corpus/mozilla-pdfjs/bug2004951.pdf");
    if (!Files.exists(corruptPath)) {
      System.out.println("Corrupt file not found: " + corruptPath);
      return;
    }

    PdfProcessingPolicy recover = PdfProcessingPolicy.defaultPolicy();
    try (PdfDocument doc = PdfDocument.open(corruptPath, null, recover)) {
      System.out.println("RECOVER open SUCCEEDED! Page count: " + doc.pageCount());
    } catch (Exception e) {
      System.out.println("RECOVER open FAILED for bug2004951: " + e.getMessage());
      if (e.getCause() != null) {
        System.out.println("  Cause: " + e.getCause().getMessage());
        e.getCause().printStackTrace();
      }
    }
  }
}
