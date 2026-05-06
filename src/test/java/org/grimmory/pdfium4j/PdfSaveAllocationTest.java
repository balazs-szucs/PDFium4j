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
    Path source = findCorpusPdf("gutenberg/996_Don Quixote.pdf");
    target = Files.createTempFile("pdfium4j-alloc-target-", ".pdf");
    
    doc = PdfDocument.open(source);
    doc.setMetadata(MetadataTag.TITLE, "Allocation Free Save");
    targetOut = new FileOutputStream(target.toFile(), false);
    targetChannel = targetOut.getChannel();
    for (int i = 0; i < 5000; i++) {
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
    asserter.assertNoAllocations(0);
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

  private Path findCorpusPdf(String relativePath) {
    Path projectRoot = Path.of("").toAbsolutePath();
    Path corpusPdf = projectRoot.resolve("corpus").resolve(relativePath);
    if (!Files.exists(corpusPdf)) {
        // Fallback for different test execution environments
        corpusPdf = projectRoot.resolve("..").resolve("corpus").resolve(relativePath);
    }
    if (!Files.exists(corpusPdf)) {
        throw new IllegalStateException("Corpus PDF not found at: " + corpusPdf);
    }
    return corpusPdf;
  }

}