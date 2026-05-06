package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import org.grimmory.pdfium4j.model.RenderFlags;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfRenderAllocationTest {

  private static final int WARMUP_ITERATIONS = 20000;

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private PdfDocument doc;
  private PdfPage page;
  private Arena arena;
  private MemorySegment renderBuffer;

  @BeforeAll
  void setUp() throws IOException {
    asserter.verifyAllocationTrackingAvailable();
    arena = Arena.ofShared();
    
    Path source = findCorpusPdf("gutenberg/996_Don Quixote.pdf");
    doc = PdfDocument.open(source);
    page = doc.page(0);
    
    // Allocate a buffer large enough for thumbnail rendering
    renderBuffer = arena.allocate(1024 * 1024 * 4);
    
    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      page.renderTo(renderBuffer, 256, 256, 256 * 4, RenderFlags.DEFAULT.value(), 0xFFFFFFFF);
    }
  }

  @AfterAll
  void tearDown() throws IOException {
    if (page != null) page.close();
    if (doc != null) doc.close();
    if (arena != null) arena.close();
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderThumbnailToSegmentDoesNotAllocateAfterWarmup() {
    long allocatedBefore = asserter.getAllocatedBytes();
    for (int i = 0; i < 1; i++) {
        page.renderThumbnailTo(renderBuffer, 256);
    }
    long delta = asserter.getAllocatedBytes() - allocatedBefore;
    System.out.println("RENDER ALLOCATION DELTA: " + delta);
    assertTrue(delta < 1024, "Too many allocations: " + delta);
  }

  static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable t) {
      return false;
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
