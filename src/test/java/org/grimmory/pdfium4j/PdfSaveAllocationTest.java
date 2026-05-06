package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private PdfDocument doc;
  private Path target;

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
    Path source = findCorpusPdf("gutenberg/1063_The Cask of Amontillado.pdf");
    target = Files.createTempFile("pdfium4j-alloc-target-", ".pdf");
    
    doc = PdfDocument.open(source);
    doc.setMetadata(MetadataTag.TITLE, "Allocation Free Save");
    
    // Warmup
    for (int i = 0; i < 10; i++) {
        try (OutputStream out = new FileOutputStream(target.toFile(), false)) {
            doc.save(out);
        }
    }
  }

  @AfterAll
  void tearDown() throws IOException {
    if (doc != null) {
      doc.close();
    }
    if (target != null) {
      Files.deleteIfExists(target);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataSaveToOutputStreamDoesNotAllocateAfterWarmup() throws IOException {
    try (OutputStream out = new FileOutputStream(target.toFile(), false)) {
        asserter.startRecording();
        doc.save(out);
        asserter.assertNoAllocations(65536);
    }
    assertTrue(fileSize(target) > 0, "Native save should stream bytes to the sink");
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to inspect allocation test output", e);
    }
  }

  private Path findCorpusPdf(String relativePath) {
    Path projectRoot = Path.of("").toAbsolutePath();
    Path corpusPdf = projectRoot.resolve("corpus").resolve(relativePath);
    if (!Files.exists(corpusPdf)) {
        corpusPdf = projectRoot.resolve("..").resolve("corpus").resolve(relativePath);
    }
    if (!Files.exists(corpusPdf)) {
        throw new IllegalStateException("Corpus PDF not found at: " + corpusPdf);
    }
    return corpusPdf;
  }
}
