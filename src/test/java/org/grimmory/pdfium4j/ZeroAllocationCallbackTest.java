package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ZeroAllocationCallbackTest {

  private static final Path SAMPLE_PDF = Path.of("src/test/resources/minimal.pdf");
  private final NoAllocationAsserter asserter = new NoAllocationAsserter();

  private static final PdfDocument.MemorySegmentConsumer CONSTANT_CONSUMER = (s, l) -> {};

  @BeforeAll
  static void setup() {
    PdfiumLibrary.initialize();
  }

  @Test
  void testWithMetadataUtf16() {
    try (PdfDocument doc = PdfDocument.open(SAMPLE_PDF)) {
      doc.setMetadata(MetadataTag.TITLE, "Zero Alloc Test");

      AtomicBoolean called = new AtomicBoolean(false);
      doc.withMetadataUtf16(
          MetadataTag.TITLE,
          (segment, length) -> {
            called.set(true);
            String title =
                new String(
                    segment.asSlice(0, length).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
            assertTrue(title.startsWith("Zero Alloc Test"));
          });
      assertTrue(called.get());
    }
  }

  @Test
  void testWithMetadataNoAllocation() {
    asserter.verifyAllocationTrackingAvailable();
    try (PdfDocument doc = PdfDocument.open(SAMPLE_PDF)) {
      doc.setMetadata(MetadataTag.TITLE, "Zero Alloc Test");

      try (var scope = ScratchBuffer.acquireScope()) {
        PdfiumLibrary.ignore(scope);
        // Warmup heavily with constant consumer
        for (int i = 0; i < 50000; i++) {
          doc.withMetadataUtf16(MetadataTag.TITLE, CONSTANT_CONSUMER);
        }

        asserter.startRecording();
        doc.withMetadataUtf16(MetadataTag.TITLE, CONSTANT_CONSUMER);
        asserter.assertNoAllocations(0);
      }
    }
  }

  @Test
  void testWithText() {
    Path testPdf = findCorpusPdf();
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      boolean foundText = false;
      int searchLimit = Math.min(doc.pageCount(), 10);
      for (int i = 0; i < searchLimit; i++) {
        try (PdfPage page = doc.page(i)) {
          AtomicBoolean called = new AtomicBoolean(false);
          page.withText(
              (segment, length) -> {
                called.set(true);
              });
          if (called.get()) {
            foundText = true;
            break;
          }
        }
      }
      assertTrue(
          foundText,
          "withText consumer was never called for any of the first " + searchLimit + " pages");
    }
  }

  @Test
  void testWithTextNoAllocation() {
    asserter.verifyAllocationTrackingAvailable();
    Path testPdf = findCorpusPdf();
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      try (PdfPage page = doc.page(1)) {
        try (var scope = ScratchBuffer.acquireScope()) {
          PdfiumLibrary.ignore(scope);
          // Warmup
          for (int i = 0; i < 50000; i++) {
            page.withText(CONSTANT_CONSUMER);
          }

          asserter.startRecording();
          page.withText(CONSTANT_CONSUMER);
          asserter.assertNoAllocations(0);
        }
      }
    }
  }

  private static Path findCorpusPdf() {
    Path projectRoot = Path.of("").toAbsolutePath();
    Path corpusPdf = projectRoot.resolve("corpus").resolve("gutenberg/996_Don Quixote.pdf");
    if (!Files.exists(corpusPdf)) {
      corpusPdf = projectRoot.resolve("..").resolve("corpus").resolve("gutenberg/996_Don Quixote.pdf");
    }
    if (!Files.exists(corpusPdf)) {
      return SAMPLE_PDF;
    }
    return corpusPdf;
  }
}
