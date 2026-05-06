package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfSaveAllocationTest {

  private static final int WARMUP_ITERATIONS = 400;

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();

  private PdfDocument doc;
  private Path source;
  private Path target;
  private FileOutputStream targetOut;
  private FileChannel targetChannel;

  static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @BeforeAll
  void setUp() throws IOException {
    asserter.verifyAllocationTrackingAvailable();
    source = Files.createTempFile("pdfium4j-alloc-save-", ".pdf");
    target = Files.createTempFile("pdfium4j-alloc-target-", ".pdf");
    Files.writeString(source, minimalPdf(), StandardCharsets.ISO_8859_1);
    doc = PdfDocument.open(source);
    doc.setMetadata(MetadataTag.TITLE, "Allocation Free Save");
    targetOut = new FileOutputStream(target.toFile(), false);
    targetChannel = targetOut.getChannel();
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      prepareTarget();
      doc.save(targetOut);
    }
  }

  @AfterAll
  void tearDown() throws IOException {
    if (doc != null) {
      doc.close();
    }
    if (targetChannel != null) {
      targetChannel.close();
    }
    if (targetOut != null) {
      targetOut.close();
    }
    if (source != null) {
      Files.deleteIfExists(source);
    }
    if (target != null) {
      Files.deleteIfExists(target);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataSaveToOutputStreamDoesNotAllocateAfterWarmup() {
    prepareTarget();
    asserter.startRecording();
    doc.save(targetOut);
    asserter.assertNoAllocations();
    assertTrue(fileSize(target) > 0, "Native save should stream bytes to the sink");
  }

  private void prepareTarget() {
    try {
      targetChannel.truncate(0);
      targetChannel.position(0);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to reset allocation test target", e);
    }
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to inspect allocation test output", e);
    }
  }

  private static String minimalPdf() {
    return "%PDF-1.4\n"
        + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
        + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R >>\nendobj\n"
        + "4 0 obj\n<< /Length 35 >>\nstream\nBT /F1 12 Tf 72 72 Td (Hello) Tj ET\nendstream\nendobj\n"
        + "xref\n0 5\n"
        + "0000000000 65535 f \n"
        + "0000000009 00000 n \n"
        + "0000000058 00000 n \n"
        + "0000000115 00000 n \n"
        + "0000000202 00000 n \n"
        + "trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n287\n%%EOF\n";
  }

}