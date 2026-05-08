package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.grimmory.pdfium4j.model.Bookmark;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfBookmarkAllocationTest {

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private PdfDocument doc;

  /**
   * Allocation tolerance for bookmark extraction.
   *
   * <p>This API returns a List of Bookmark objects, so allocations are expected. This threshold
   * ensures the implementation remains efficient.
   */
  private static final long EFFICIENCY_TOLERANCE = 256 * 1024;

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
    // Use a file that is known to have bookmarks
    Path source = findCorpusPdf();
    doc = PdfDocument.open(source);

    // Warmup JIT
    for (int i = 0; i < 500; i++) {
      doc.bookmarks();
    }
  }

  @AfterAll
  void tearDown() throws IOException {
    if (doc != null) {
      doc.close();
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void bookmarksReadingIsEfficient() throws IOException {
    // Returns List<PdfBookmark>, so some allocations are expected.
    asserter.startRecording();
    List<Bookmark> bookmarks = doc.bookmarks();
    assertNotNull(bookmarks);
    asserter.assertNoAllocations(EFFICIENCY_TOLERANCE);
  }

  private static Path findCorpusPdf() {
    Path projectRoot = Path.of("").toAbsolutePath();
    Path corpusPdf =
        projectRoot.resolve("corpus").resolve("gutenberg/1063_The Cask of Amontillado.pdf");
    if (!Files.exists(corpusPdf)) {
      corpusPdf =
          projectRoot
              .resolve("..")
              .resolve("corpus")
              .resolve("gutenberg/1063_The Cask of Amontillado.pdf");
    }
    if (!Files.exists(corpusPdf)) {
      throw new IllegalStateException("Corpus PDF not found at: " + corpusPdf);
    }
    return corpusPdf;
  }
}
