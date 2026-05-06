package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
class PdfOpenReadAllocationTest {

  private static final int WARMUP_ITERATIONS = 400;
  private static final String TITLE = "Allocation Free Read";

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();

  private Path probeSource;
  private PdfDocument.NoAllocationPathProbe openProbe;
  private PdfDocument metadataDoc;

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

    probeSource = findCorpusPdf("gutenberg/996_Don Quixote.pdf");
    openProbe = PdfDocument.noAllocationPathProbe(probeSource, null);

    metadataDoc = PdfDocument.open(probeSource);
    metadataDoc.setMetadata(MetadataTag.TITLE, TITLE);
  }

  @AfterAll
  void tearDown() throws IOException {
    if (metadataDoc != null) {
      metadataDoc.close();
    }
    if (openProbe != null) {
      openProbe.close();
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pathOpenProbeDoesNotAllocateAfterWarmup() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment trailerBuffer =
          arena.allocate(32L * JAVA_INT.byteSize(), JAVA_INT.byteAlignment());
      int[] output = new int[3];

      for (int i = 0; i < 10000; i++) {
        openProbe.inspect(output, trailerBuffer);
      }

      asserter.startRecording();
      openProbe.inspect(output, trailerBuffer);
      asserter.assertNoAllocations(0);

      assertTrue(output[0] > 900, "Don Quixote should have many pages");
      assertEquals(1, output[1]); // Xref health
      assertTrue(output[2] > 0, "Expected at least one trailer end offset");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataProbeDoesNotAllocateAfterWarmup() {
    try (Arena arena = Arena.ofConfined()) {
      int needed = metadataDoc.probeMetadataUtf16ByteLength(MetadataTag.TITLE);
      assertTrue(needed > 2, "Expected UTF-16LE metadata bytes including terminator");

      MemorySegment metadataBuffer = arena.allocate(needed, 2);
      for (int i = 0; i < 10000; i++) {
        metadataDoc.readMetadataUtf16(MetadataTag.TITLE, metadataBuffer);
      }

      asserter.startRecording();
      int copied = metadataDoc.readMetadataUtf16(MetadataTag.TITLE, metadataBuffer);
      asserter.assertNoAllocations(0);

      assertEquals(needed, copied);
      String title =
          new String(
              metadataBuffer.asSlice(0, copied - 2).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
      assertEquals(TITLE, title);
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