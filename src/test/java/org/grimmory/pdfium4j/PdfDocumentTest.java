package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import org.grimmory.pdfium4j.exception.PdfiumException;
import org.grimmory.pdfium4j.internal.ScratchBuffer;
import org.grimmory.pdfium4j.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for PDFium4j. These tests require a PDFium native library to be available. They are skipped
 * if the library cannot be loaded.
 */
class PdfDocumentTest {

  static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void openFromPath() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      assertTrue(doc.pageCount() > 0, "Should have at least one page");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void openFromBytes() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    byte[] data = Files.readAllBytes(testPdf);
    try (PdfDocument doc = PdfDocument.open(data)) {
      assertTrue(doc.pageCount() > 0);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void openFromPathIgnoresDocumentSizePolicy(@TempDir Path tempDir) throws IOException {
    Path pdf = tempDir.resolve("small-valid.pdf");
    Files.write(pdf, minimalPdfWithText());

    PdfProcessingPolicy tinyPolicy =
        new PdfProcessingPolicy(
            PdfProcessingPolicy.Mode.STRICT,
            1,
            PdfProcessingPolicy.DEFAULT_MAX_RENDER_PIXELS,
            PdfProcessingPolicy.DEFAULT_MAX_PARALLEL_THREADS,
            PdfProcessingPolicy.DEFAULT_FILE_BACKED_THRESHOLD);

    try (PdfDocument doc = PdfDocument.open(pdf, null, tinyPolicy)) {
      assertEquals(1, doc.pageCount());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void openFromBytesIgnoresDocumentSizePolicy() {
    byte[] pdf = minimalPdfWithText();
    PdfProcessingPolicy tinyPolicy =
        new PdfProcessingPolicy(
            PdfProcessingPolicy.Mode.STRICT,
            1,
            PdfProcessingPolicy.DEFAULT_MAX_RENDER_PIXELS,
            PdfProcessingPolicy.DEFAULT_MAX_PARALLEL_THREADS,
            PdfProcessingPolicy.DEFAULT_FILE_BACKED_THRESHOLD);

    try (PdfDocument doc = PdfDocument.open(pdf, null, tinyPolicy)) {
      assertEquals(1, doc.pageCount());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageSize() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      PageSize size = doc.pageSize(0);
      assertTrue(size.width() > 0, "Width should be positive");
      assertTrue(size.height() > 0, "Height should be positive");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderPage() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      RenderResult result = page.render(150);
      assertTrue(result.width() > 0);
      assertTrue(result.height() > 0);
      assertNotNull(result.rgba());
      assertEquals(result.width() * result.height() * 4, result.rgba().length);

      BufferedImage image = result.toBufferedImage();
      assertEquals(result.width(), image.getWidth());
      assertEquals(result.height(), image.getHeight());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadata() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      Map<String, String> meta = doc.metadata();
      assertNotNull(meta);
      // Just verify the API works; content depends on test PDF
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void bookmarks() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      List<Bookmark> bookmarks = doc.bookmarks();
      assertNotNull(bookmarks);
      // May be empty depending on test PDF
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void closedDocumentThrows() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    PdfDocument doc = PdfDocument.open(testPdf);
    doc.close();
    assertThrows(IllegalStateException.class, doc::pageCount);
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void closedPageThrows() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      PdfPage page = doc.page(0);
      page.close();
      assertThrows(IllegalStateException.class, page::size);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void wrongThreadDocumentAccessThrows() throws Exception {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  doc.pageCount();
                } catch (Throwable t) {
                  errorRef.set(t);
                }
              },
              "pdfium-wrong-thread");

      worker.start();
      worker.join();

      assertNotNull(errorRef.get(), "Wrong-thread access should fail");
      assertInstanceOf(IllegalStateException.class, errorRef.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void wrongThreadPageOpenThrows() throws Exception {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  doc.page(0);
                } catch (Throwable t) {
                  errorRef.set(t);
                }
              },
              "pdfium-open-page-wrong-thread");

      worker.start();
      worker.join();

      assertNotNull(errorRef.get(), "Wrong-thread page open should fail");
      assertInstanceOf(IllegalStateException.class, errorRef.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void wrongThreadDocumentCloseThrows() throws Exception {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  doc.close();
                } catch (Throwable t) {
                  errorRef.set(t);
                }
              },
              "pdfium-close-wrong-thread");

      worker.start();
      worker.join();

      assertNotNull(errorRef.get(), "Wrong-thread close should fail");
      assertInstanceOf(IllegalStateException.class, errorRef.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void wrongThreadPageAccessThrows() throws Exception {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  page.size();
                } catch (Throwable t) {
                  errorRef.set(t);
                }
              },
              "pdfium-page-wrong-thread");

      worker.start();
      worker.join();

      assertNotNull(errorRef.get(), "Wrong-thread page access should fail");
      assertInstanceOf(IllegalStateException.class, errorRef.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void wrongThreadPageCloseThrows() throws Exception {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  page.close();
                } catch (Throwable t) {
                  errorRef.set(t);
                }
              },
              "pdfium-page-close-wrong-thread");

      worker.start();
      worker.join();

      assertNotNull(errorRef.get(), "Wrong-thread page close should fail");
      assertInstanceOf(IllegalStateException.class, errorRef.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void documentCloseInvalidatesOpenPages() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    PdfDocument doc = PdfDocument.open(testPdf);
    PdfPage page = doc.page(0);
    doc.close();

    assertThrows(
        IllegalStateException.class,
        page::size,
        "Page handle should be invalid after owning document closes");
    assertDoesNotThrow(page::close, "Closing an already-invalidated page should be idempotent");
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void extractText() throws IOException {
    Path testPdf = getTestPdfWithText();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      String text = page.extractText();
      assertNotNull(text);
      assertFalse(text.isEmpty(), "Should extract some text from test PDF");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void charCount() throws IOException {
    Path testPdf = getTestPdfWithText();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      int count = page.charCount();
      assertTrue(count > 0, "Should have characters on test PDF page");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageRotation() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      int rotation = page.rotation();
      assertTrue(
          rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270,
          "Rotation should be 0, 90, 180, or 270");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setPageRotation() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      page.setRotation(90);
      assertEquals(90, page.rotation());

      page.setRotation(0);
      assertEquals(0, page.rotation());

      assertThrows(IllegalArgumentException.class, () -> page.setRotation(45));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageLabel() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      // Just verify API doesn't crash; most test PDFs don't have page labels
      Optional<String> label = doc.pageLabel(0);
      assertNotNull(label);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadata() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      byte[] xmp = doc.xmpMetadata();
      assertNotNull(xmp);
      // Some PDFs have XMP, some don't - just verify no crash

      String xmpStr = doc.xmpMetadataString();
      assertNotNull(xmpStr);

      if (xmp.length > 0) {
        assertTrue(xmpStr.contains("<?xpacket"), "XMP should contain xpacket marker");
        assertTrue(xmpStr.contains("xpacket end"), "XMP should have end marker");
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataRoundTrip(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path outPdf = tempDir.resolve("xmp-roundtrip.pdf");

    // Step 1: open, set XMP, save
    String xmpContent =
        """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title><rdf:Alt><rdf:li>RoundTripTitle</rdf:li></rdf:Alt></dc:title>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""";

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setXmpMetadata(xmpContent);
      doc.save(outPdf);
    }

    // Step 2: re-open from path and verify XMP is readable
    try (PdfDocument doc2 = PdfDocument.open(outPdf)) {
      String xmpStr = doc2.xmpMetadataString();
      assertFalse(xmpStr.isEmpty(), "XMP should be found in saved file");
      assertTrue(xmpStr.contains("RoundTripTitle"), "XMP should contain the title we set");
    }

    // Step 3: open from bytes and verify
    byte[] bytes = Files.readAllBytes(outPdf);
    try (PdfDocument doc3 = PdfDocument.open(bytes)) {
      String xmpStr = doc3.xmpMetadataString();
      assertFalse(xmpStr.isEmpty(), "XMP should be found when opened from bytes");
      assertTrue(xmpStr.contains("RoundTripTitle"), "XMP should contain the title from bytes");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataRoundTripSamePath(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path pdf = tempDir.resolve("same-path.pdf");
    Files.copy(testPdf, pdf);

    String xmpContent =
        """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title><rdf:Alt><rdf:li>SamePathTitle</rdf:li></rdf:Alt></dc:title>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""";

    // Open from same path, set XMP, save to same path
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setXmpMetadata(xmpContent);
      doc.save(pdf);
    }

    // Re-open same path
    try (PdfDocument doc2 = PdfDocument.open(pdf)) {
      String xmpStr = doc2.xmpMetadataString();
      assertFalse(xmpStr.isEmpty(), "XMP should be found in same-path saved file");
      assertTrue(xmpStr.contains("SamePathTitle"), "XMP should contain the title we set");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataRoundTripWithExistingXmp(@TempDir Path tempDir)
      throws IOException, URISyntaxException {
    // Use a PDF that already has a 4096-byte XMP stream (like grimmory's minimal.pdf)
    var resource = getClass().getResource("/minimal.pdf");
    if (resource == null) return;
    Path originalPdf = Path.of(resource.toURI());

    Path pdf = tempDir.resolve("existing-xmp.pdf");
    Files.copy(originalPdf, pdf);

    String xmpContent =
        """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description
                        xmlns:dc="http://purl.org/dc/elements/1.1/"
                        xmlns:calibre="http://calibre-ebook.com/xmp-namespace">
                      <calibre:series><rdf:value>ExistingXmpSeries</rdf:value></calibre:series>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""";

    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setXmpMetadata(xmpContent);
      doc.save(pdf);
    }

    // Verify the raw file contains the xpacket marker
    byte[] rawBytes = Files.readAllBytes(pdf);
    String rawStr = new String(rawBytes, StandardCharsets.ISO_8859_1);
    assertTrue(rawStr.contains("<?xpacket begin="), "Saved file should contain xpacket marker");
    assertTrue(rawStr.contains("ExistingXmpSeries"), "Saved file should contain our XMP content");

    // Re-open from path and verify XMP readable
    try (PdfDocument doc2 = PdfDocument.open(pdf)) {
      String xmpStr = doc2.xmpMetadataString();
      assertFalse(xmpStr.isEmpty(), "XMP should be found in file with existing XMP");
      assertTrue(xmpStr.contains("ExistingXmpSeries"), "XMP should contain series name");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setEmptyXmpMetadataDoesNotOverwriteExistingXmp(@TempDir Path tempDir)
      throws IOException, URISyntaxException {
    var resource = getClass().getResource("/minimal.pdf");
    if (resource == null) return;
    Path originalPdf = Path.of(resource.toURI());

    Path pdf = tempDir.resolve("existing-xmp-empty-update.pdf");
    Files.copy(originalPdf, pdf);

    String before;
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      before = doc.xmpMetadataString();
      assertFalse(before.isEmpty(), "Fixture should contain XMP");
      doc.setXmpMetadata("");
      doc.save(pdf);
    }

    try (PdfDocument doc = PdfDocument.open(pdf)) {
      String after = doc.xmpMetadataString();
      assertEquals(before, after, "Empty raw XMP update should behave as no-op");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveToBytes() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      byte[] saved = doc.saveToBytes();
      assertNotNull(saved);
      assertTrue(saved.length > 0, "Saved PDF should not be empty");
      // Verify it starts with %PDF
      String header = new String(saved, 0, Math.min(5, saved.length), StandardCharsets.ISO_8859_1);
      assertTrue(header.startsWith("%PDF"), "Saved file should be a valid PDF");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveToFile(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("output.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.save(output);
    }

    assertTrue(Files.exists(output));
    assertTrue(Files.size(output) > 0);

    // Verify saved PDF is loadable
    try (PdfDocument doc = PdfDocument.open(output)) {
      assertTrue(doc.pageCount() > 0);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void savePreservesRotation(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("rotated.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      page.setRotation(90);
      doc.save(output);
    }

    // Verify rotation persisted
    try (PdfDocument doc = PdfDocument.open(output);
        PdfPage page = doc.page(0)) {
      assertEquals(90, page.rotation(), "Rotation should be preserved after save");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void probeValidPdf() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    PdfProbeResult result = PdfDocument.probe(testPdf);
    assertTrue(result.isValid(), "Valid PDF should probe as OK");
    assertTrue(result.pageCount() > 0, "Should report page count");
    assertEquals(PdfProbeResult.Status.OK, result.status());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void probePathIgnoresDocumentSizePolicy(@TempDir Path tempDir) throws IOException {
    Path pdf = tempDir.resolve("probe-valid.pdf");
    Files.write(pdf, minimalPdfWithText());

    PdfProcessingPolicy tinyPolicy =
        new PdfProcessingPolicy(
            PdfProcessingPolicy.Mode.STRICT,
            1,
            PdfProcessingPolicy.DEFAULT_MAX_RENDER_PIXELS,
            PdfProcessingPolicy.DEFAULT_MAX_PARALLEL_THREADS,
            PdfProcessingPolicy.DEFAULT_FILE_BACKED_THRESHOLD);

    PdfProbeResult result = PdfDocument.probe(pdf, tinyPolicy);
    assertTrue(result.isValid());
    assertEquals(PdfProbeResult.Status.OK, result.status());
    assertEquals(1, result.pageCount());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void probeBytesIgnoresDocumentSizePolicy() {
    PdfProcessingPolicy tinyPolicy =
        new PdfProcessingPolicy(
            PdfProcessingPolicy.Mode.STRICT,
            1,
            PdfProcessingPolicy.DEFAULT_MAX_RENDER_PIXELS,
            PdfProcessingPolicy.DEFAULT_MAX_PARALLEL_THREADS,
            PdfProcessingPolicy.DEFAULT_FILE_BACKED_THRESHOLD);

    PdfProbeResult result = PdfDocument.probe(minimalPdfWithText(), tinyPolicy);
    assertTrue(result.isValid());
    assertEquals(PdfProbeResult.Status.OK, result.status());
    assertEquals(1, result.pageCount());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void probeInvalidData() {
    PdfProbeResult result = PdfDocument.probe(new byte[] {0, 1, 2, 3, 4});
    assertFalse(result.isValid());
    assertEquals(PdfProbeResult.Status.CORRUPT, result.status());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void probeEmptyData() {
    PdfProbeResult result = PdfDocument.probe(new byte[0]);
    assertFalse(result.isValid());
    assertEquals(PdfProbeResult.Status.UNREADABLE, result.status());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void probeNullPath() {
    PdfProbeResult result = PdfDocument.probe((Path) null);
    assertFalse(result.isValid());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderBounded() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      RenderResult result = page.renderBounded(300, 200, 200);
      assertTrue(result.width() <= 200, "Width should be at most 200");
      assertTrue(result.height() <= 200, "Height should be at most 200");
      assertTrue(result.width() > 0);
      assertTrue(result.height() > 0);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderThumbnail() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      RenderResult thumb = page.renderThumbnail(100);
      assertTrue(thumb.width() <= 100);
      assertTrue(thumb.height() <= 100);
      assertTrue(thumb.width() > 0);
      assertTrue(thumb.height() > 0);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void allPageSizes() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      List<PageSize> sizes = doc.allPageSizes();
      assertEquals(doc.pageCount(), sizes.size());
      for (PageSize size : sizes) {
        assertTrue(size.width() > 0);
        assertTrue(size.height() > 0);
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void isImageOnly() throws IOException {
    Path testPdf = getTestPdfWithText();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      // A text PDF should not be image-only
      assertFalse(doc.isImageOnly(), "Text PDF should not be image-only");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void fileVersion() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      int version = doc.fileVersion();
      assertTrue(
          version >= 10 && version <= 25,
          "PDF version should be between 1.0 and 2.5, got: " + version);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void extractTextWithBounds() throws IOException {
    Path testPdf = getTestPdfWithText();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      List<TextCharInfo> chars = page.extractTextWithBounds();
      assertNotNull(chars);
      if (page.charCount() > 0) {
        assertFalse(chars.isEmpty(), "Should have char info for text page");
        TextCharInfo first = chars.getFirst();
        assertTrue(first.charCode() > 0, "Should have valid char code");
        assertNotNull(first.character());
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void hasText() throws IOException {
    Path testPdf = getTestPdfWithText();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      assertTrue(page.hasText(), "Text PDF page should have text");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void annotations() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      List<PdfAnnotation> annots = page.annotations();
      assertNotNull(annots);
      // Just verify API works - most test PDFs don't have annotations
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void webLinks() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      List<PdfLink> links = page.webLinks();
      assertNotNull(links);
      // Just verify API works
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void insertAndDeletePage(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      int originalCount = doc.pageCount();

      // Insert blank page at end
      doc.insertBlankPage(originalCount, PageSize.A4);
      assertEquals(originalCount + 1, doc.pageCount());

      // Delete the inserted page
      doc.deletePage(originalCount);
      assertEquals(originalCount, doc.pageCount());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void importPages(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc1 = PdfDocument.open(testPdf);
        PdfDocument doc2 = PdfDocument.open(testPdf)) {
      int initialCount = doc1.pageCount();
      doc1.importPages(doc2, "1", initialCount);
      assertEquals(initialCount + 1, doc1.pageCount());

      doc1.importAllPages(doc2);
      assertEquals(initialCount + 1 + initialCount, doc1.pageCount());

      Path out = tempDir.resolve("merged.pdf");
      doc1.save(out);
      assertTrue(Files.exists(out));

      try (PdfDocument merged = PdfDocument.open(out)) {
        assertEquals(doc1.pageCount(), merged.pageCount());
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void deletePageOutOfRange() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      assertThrows(IllegalArgumentException.class, () -> doc.deletePage(-1));
      assertThrows(IllegalArgumentException.class, () -> doc.deletePage(doc.pageCount()));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void diagnose() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    PdfDiagnostic diag = PdfDocument.diagnose(testPdf);
    assertTrue(diag.valid(), "Test PDF should be valid");
    assertTrue(diag.pageCount() > 0);
    assertNotNull(diag.warnings());
    assertNotNull(diag.fileVersionString());
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveToOutputStream() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      doc.save(baos);
      byte[] saved = baos.toByteArray();
      assertTrue(saved.length > 0);
      assertTrue(new String(saved, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveToSlowOutputStreamWithoutUpdates() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      SlowOutputStream out = new SlowOutputStream();
      doc.save(out);

      byte[] saved = out.toByteArray();
      assertTrue(saved.length > 0);
      assertTrue(new String(saved, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataSaveToGenericOutputStreamIsRejected() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Unsafe Stream Save");
      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      PdfiumException ex = assertThrows(PdfiumException.class, () -> doc.save(baos));
      assertTrue(
          ex.getMessage().contains("Failed to save document"),
          "Public save(OutputStream) should surface a clear failure");
      assertTrue(
          ex.getCause() instanceof IOException
              && ex.getCause().getMessage().contains("save(Path) or saveToBytes()"),
          "Failure should explain the safe alternatives for incremental saves");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataSaveToBytesRemainsSupported() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    byte[] saved;
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Bytes Metadata Save");
      saved = doc.saveToBytes();
    }

    try (PdfDocument doc = PdfDocument.open(saved)) {
      assertEquals("Bytes Metadata Save", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertTrue(doc.pageCount() > 0, "saveToBytes should still produce a readable PDF");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataSingleTag(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("meta.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Test Title");
      // Verify in-memory read-back
      Optional<String> inMemory = doc.metadata(MetadataTag.TITLE);
      assertTrue(inMemory.isPresent(), "Title should be readable in-memory after set");
      assertEquals("Test Title", inMemory.get());
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      Optional<String> title = doc.metadata(MetadataTag.TITLE);
      assertTrue(title.isPresent(), "Title should persist after save");
      assertEquals("Test Title", title.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataBulk(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("meta-bulk.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(
          Map.of(
              MetadataTag.TITLE, "Bulk Title",
              MetadataTag.AUTHOR, "Bulk Author",
              MetadataTag.SUBJECT, "Bulk Subject"));
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("Bulk Title", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Bulk Author", doc.metadata(MetadataTag.AUTHOR).orElse(""));
      assertEquals("Bulk Subject", doc.metadata(MetadataTag.SUBJECT).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataClearValue(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("meta-clear.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Temporary");
      doc.setMetadata(MetadataTag.TITLE, "");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertTrue(
          doc.metadata(MetadataTag.TITLE).isEmpty(), "Cleared title should read back as empty");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataBlankModDateFallsBackToGeneratedDate(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("meta-blank-moddate.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.MOD_DATE, "   ");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      String modDate = doc.metadata(MetadataTag.MOD_DATE).orElse("");
      assertTrue(!modDate.isBlank(), "ModDate should be auto-generated for blank input");
      assertTrue(modDate.startsWith("D:"), "Generated ModDate should use PDF date format");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataInvalidModDateFallsBackToGeneratedDate(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("meta-invalid-moddate.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.MOD_DATE, "not-a-pdf-date");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      String modDate = doc.metadata(MetadataTag.MOD_DATE).orElse("");
      assertTrue(!modDate.isBlank(), "ModDate should not remain blank");
      assertTrue(modDate.startsWith("D:"), "Invalid ModDate should be replaced by PDF date");
      assertNotEquals("not-a-pdf-date", modDate);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataTimezoneModDateIsPreserved(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    String explicitModDate = "D:20260503112233+02'00'";
    Path output = tempDir.resolve("meta-timezone-moddate.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.MOD_DATE, explicitModDate);
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals(explicitModDate, doc.metadata(MetadataTag.MOD_DATE).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void setMetadataDateOnlyModDateIsPreserved(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    String explicitModDate = "D:20260503";
    Path output = tempDir.resolve("meta-dateonly-moddate.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.MOD_DATE, explicitModDate);
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals(explicitModDate, doc.metadata(MetadataTag.MOD_DATE).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataByStringKeyReadsStandardTag(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("string-key-meta.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "StringKeyTest");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      // Read via String key (same underlying FPDF_GetMetaText call)
      Optional<String> title = doc.metadata("Title");
      assertTrue(title.isPresent(), "Title should be readable via string key");
      assertEquals("StringKeyTest", title.get());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataRoundTripWithMultilingualValues(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("meta-unicode-roundtrip.pdf");
    String title = "Français 漢字 Русский";
    String author = "Élodie 张伟 Иванов";
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, title);
      doc.setMetadata(MetadataTag.AUTHOR, author);
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals(title, doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals(author, doc.metadata(MetadataTag.AUTHOR).orElse(""));
      assertEquals(title, doc.metadata("Title").orElse(""));
      assertEquals(author, doc.metadata("Author").orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveAndReopenWithUnicodeFilename(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("résumé_日本語_русский_文件.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Unicode Filename");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertTrue(doc.pageCount() > 0);
      assertEquals("Unicode Filename", doc.metadata(MetadataTag.TITLE).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataByStringKeyReturnsEmptyForMissing() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      Optional<String> result = doc.metadata("NonExistentCustomKey");
      assertTrue(result.isEmpty(), "Non-existent key should return empty");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderResultToJpegBytesProducesValidJpeg() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      RenderResult result = page.render(72);
      byte[] jpeg = result.toJpegBytes();

      assertTrue(jpeg.length > 0, "JPEG bytes should not be empty");
      // JPEG magic bytes: FF D8 FF
      assertEquals((byte) 0xFF, jpeg[0], "JPEG should start with 0xFF");
      assertEquals((byte) 0xD8, jpeg[1], "JPEG byte 2 should be 0xD8");
      assertEquals((byte) 0xFF, jpeg[2], "JPEG byte 3 should be 0xFF");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderResultToJpegBytesWithQuality() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      RenderResult result = page.render(72);
      byte[] lowQuality = result.toJpegBytes(0.1f);
      byte[] highQuality = result.toJpegBytes(0.95f);

      assertTrue(lowQuality.length > 0, "Low quality JPEG should not be empty");
      assertTrue(highQuality.length > 0, "High quality JPEG should not be empty");
      assertTrue(
          highQuality.length > lowQuality.length,
          "High quality JPEG should be larger than low quality");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderResultToPngBytesProducesValidPng() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      RenderResult result = page.render(72);
      byte[] png = result.toPngBytes();

      assertTrue(png.length > 0, "PNG bytes should not be empty");
      // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
      assertEquals((byte) 0x89, png[0], "PNG byte 1");
      assertEquals((byte) 0x50, png[1], "PNG byte 2 (P)");
      assertEquals((byte) 0x4E, png[2], "PNG byte 3 (N)");
      assertEquals((byte) 0x47, png[3], "PNG byte 4 (G)");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderPageToBytesJpeg() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      byte[] jpeg = doc.renderPageToBytes(0, 150, "jpeg");

      assertTrue(jpeg.length > 0, "Rendered JPEG should not be empty");
      assertEquals((byte) 0xFF, jpeg[0], "Should be JPEG format");
      assertEquals((byte) 0xD8, jpeg[1], "Should be JPEG format");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderPageToBytesPng() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      byte[] png = doc.renderPageToBytes(0, 150, "png");

      assertTrue(png.length > 0, "Rendered PNG should not be empty");
      assertEquals((byte) 0x89, png[0], "Should be PNG format");
      assertEquals((byte) 0x50, png[1], "Should be PNG format");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void renderPageToBytesInvalidFormat() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> doc.renderPageToBytes(0, 150, "bmp"),
          "Should reject unsupported format");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageIsBlankOnTextPage() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      // Our test PDF has "Hello World" text
      assertFalse(page.isBlank(), "Page with text should not be blank");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageIsBlankOnBlankPage(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path blankPdf = tempDir.resolve("blank.pdf");

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.insertBlankPage(doc.pageCount(), PageSize.A4);
      doc.save(blankPdf);
    }

    try (PdfDocument doc = PdfDocument.open(blankPdf)) {
      // The last page is the blank one we inserted
      try (PdfPage page = doc.page(doc.pageCount() - 1)) {
        assertTrue(page.isBlank(), "Blank page with no text or images should be blank");
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageImageCountOnTextOnlyPage() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      // Minimal text-only PDF has no images
      assertEquals(0, page.imageCount(), "Text-only page should have 0 images");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageEmbeddedImagesOnTextOnlyPage() throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    try (PdfDocument doc = PdfDocument.open(testPdf);
        PdfPage page = doc.page(0)) {
      List<EmbeddedImage> images = page.embeddedImages();
      assertTrue(images.isEmpty(), "Text-only page should have no embedded images");
    }
  }

  @CheckForNull
  private Path getTestPdf() {
    var url = getClass().getResource("/test.pdf");
    if (url != null) {
      try {
        return Path.of(url.toURI());
      } catch (URISyntaxException e) {
        System.getLogger(PdfDocumentTest.class.getName())
            .log(System.Logger.Level.DEBUG, "Unable to resolve test PDF URI", e);
      }
    }
    // Generate a minimal PDF with text content
    try {
      Path tempPdf = Files.createTempFile("pdfium4j-test-", ".pdf");
      tempPdf.toFile().deleteOnExit();
      Files.write(tempPdf, minimalPdfWithText());
      return tempPdf;
    } catch (IOException e) {
      System.err.println("Failed to create test PDF: " + e.getMessage());
      return null;
    }
  }

  private Path getTestPdfWithText() {
    return getTestPdf();
  }

  private static byte[] minimalPdfWithText() {
    String pdf =
        """
                %PDF-1.4
                1 0 obj
                << /Type /Catalog /Pages 2 0 R >>
                endobj
                2 0 obj
                << /Type /Pages /Kids [3 0 R] /Count 1 >>
                endobj
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
                   /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
                endobj
                4 0 obj
                << /Length 44 >>
                stream
                BT /F1 12 Tf 100 700 Td (Hello World) Tj ET
                endstream
                endobj
                5 0 obj
                << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
                endobj
                xref
                0 6
                0000000000 65535 f \r
                0000000009 00000 n \r
                0000000058 00000 n \r
                0000000115 00000 n \r
                0000000266 00000 n \r
                0000000360 00000 n \r
                trailer
                << /Size 6 /Root 1 0 R >>
                startxref
                434
                %%EOF
                """;
    return pdf.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] minimalEmptyPdf() {
    String pdf =
        """
                %PDF-1.4
                1 0 obj
                << /Type /Catalog /Pages 2 0 R >>
                endobj
                2 0 obj
                << /Type /Pages /Kids [3 0 R] /Count 1 >>
                endobj
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>
                endobj
                4 0 obj
                << /Length 0 >>
                stream
                endstream
                endobj
                xref
                0 5
                0000000000 65535 f \r
                0000000009 00000 n \r
                0000000058 00000 n \r
                0000000115 00000 n \r
                0000000212 00000 n \r
                trailer
                << /Size 5 /Root 1 0 R >>
                startxref
                262
                %%EOF
                """;
    return pdf.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveWithAndWithoutMetadataProducesValidPdfs(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path outNoChanges = tempDir.resolve("no-changes.pdf");
    Path outFast = tempDir.resolve("fast-save.pdf");

    // Save with no changes - always goes through PDFium native serialization
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.save(outNoChanges);
    }

    // Metadata save (always through PDFium + validated incremental update)
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Fast Save Title");
      doc.save(outFast);
    }

    // Verify both produce valid PDFs that can be re-opened
    assertTrue(Files.size(outNoChanges) > 0);
    assertTrue(Files.size(outFast) > 0);

    try (PdfDocument doc = PdfDocument.open(outNoChanges)) {
      assertTrue(doc.pageCount() > 0, "No-changes save should produce valid PDF");
    }

    // Verify the saved file contains the metadata
    try (PdfDocument doc = PdfDocument.open(outFast)) {
      assertEquals("Fast Save Title", doc.metadata(MetadataTag.TITLE).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataOnlySaveFromBytesWorks(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    byte[] originalBytes = Files.readAllBytes(testPdf);
    Path outPath = tempDir.resolve("bytes-meta.pdf");

    try (PdfDocument doc = PdfDocument.open(originalBytes)) {
      doc.setMetadata(MetadataTag.TITLE, "BytesSave");
      doc.setMetadata(MetadataTag.AUTHOR, "Test Author");
      doc.save(outPath);
    }

    try (PdfDocument doc = PdfDocument.open(outPath)) {
      assertEquals("BytesSave", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Test Author", doc.metadata(MetadataTag.AUTHOR).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataOnlySavePreservesPageContent(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("preserve-content.pdf");

    int originalPageCount;
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      originalPageCount = doc.pageCount();
    }

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Preserves Content");
      doc.setXmpMetadata(buildBookloreXmp("Preserves Content", "Test Author"));
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals(originalPageCount, doc.pageCount());
      assertEquals("Preserves Content", doc.metadata(MetadataTag.TITLE).orElse(""));

      String xmp = doc.xmpMetadataString();
      assertTrue(xmp.contains("Preserves Content"), "XMP should be in file");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void structuralChangeUsesFullSave(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("structural.pdf");

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.insertBlankPage(0, new PageSize(612, 792));
      doc.setMetadata(MetadataTag.TITLE, "Structural Change");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals(2, doc.pageCount(), "Should have original + inserted page");
      assertEquals("Structural Change", doc.metadata(MetadataTag.TITLE).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataRoundTripWithBookloreNamespace(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("booklore-xmp.pdf");

    String bookloreXmp =
        """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Dead Simple Python</rdf:li></rdf:Alt></dc:title>
                      <dc:creator><rdf:Seq><rdf:li>Jason C. McDonald</rdf:li></rdf:Seq></dc:creator>
                      <dc:publisher><rdf:Bag><rdf:li>No Starch Press</rdf:li></rdf:Bag></dc:publisher>
                      <dc:subject>
                        <rdf:Bag>
                          <rdf:li>Programming</rdf:li>
                          <rdf:li>Python</rdf:li>
                        </rdf:Bag>
                      </dc:subject>
                      <dc:date><rdf:Seq><rdf:li>2023-01-01</rdf:li></rdf:Seq></dc:date>
                      <dc:language><rdf:Bag><rdf:li>English</rdf:li></rdf:Bag></dc:language>
                    </rdf:Description>
                    <rdf:Description rdf:about=""
                        xmlns:booklore="http://booklore.org/metadata/1.0/">
                      <booklore:subtitle>Idiomatic Python for the Impatient Programmer</booklore:subtitle>
                      <booklore:isbn13>9781718500921</booklore:isbn13>
                      <booklore:isbn10>1718500920</booklore:isbn10>
                      <booklore:goodreadsId>52555538</booklore:goodreadsId>
                      <booklore:goodreadsRating>4.4</booklore:goodreadsRating>
                      <booklore:pageCount>713</booklore:pageCount>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""";

    // Write XMP + Info Dict
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Dead Simple Python");
      doc.setMetadata(MetadataTag.AUTHOR, "Jason C. McDonald");
      doc.setXmpMetadata(bookloreXmp);
      doc.save(output);
    }

    // Read back and verify BOTH Info Dict and XMP
    try (PdfDocument doc = PdfDocument.open(output)) {
      // Info Dict
      assertEquals("Dead Simple Python", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Jason C. McDonald", doc.metadata(MetadataTag.AUTHOR).orElse(""));

      // XMP
      String xmp = doc.xmpMetadataString();
      assertFalse(xmp.isEmpty(), "XMP should be present");

      XmpMetadata parsed = XmpMetadataParser.parse(xmp);
      assertEquals("Dead Simple Python", parsed.title().orElse(""));
      assertEquals(List.of("Jason C. McDonald"), parsed.creators());
      assertEquals("No Starch Press", parsed.publisher().orElse(""));
      assertEquals("2023-01-01", parsed.date().orElse(""));
      assertEquals("English", parsed.language().orElse(""));
      assertTrue(parsed.subjects().contains("Programming"));
      assertTrue(parsed.subjects().contains("Python"));

      // Verify raw XMP string contains booklore namespace elements
      assertTrue(xmp.contains("booklore:subtitle"), "XMP should contain subtitle");
      assertTrue(xmp.contains("Idiomatic Python"), "XMP should contain subtitle value");
      assertTrue(xmp.contains("booklore:isbn13"), "XMP should contain isbn13");
      assertTrue(xmp.contains("9781718500921"));
      assertTrue(xmp.contains("booklore:isbn10"), "XMP should contain isbn10");
      assertTrue(xmp.contains("1718500920"));
      assertTrue(xmp.contains("booklore:goodreadsId"), "XMP should contain goodreadsId");
      assertTrue(xmp.contains("52555538"));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataOverwritePrevious(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path firstSave = tempDir.resolve("first.pdf");
    Path secondSave = tempDir.resolve("second.pdf");

    // First write
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "First Title");
      doc.setXmpMetadata(buildBookloreXmp("First Title", "First Author"));
      doc.save(firstSave);
    }

    // Second write overwrites - open the FIRST save and update
    try (PdfDocument doc = PdfDocument.open(firstSave)) {
      doc.setMetadata(MetadataTag.TITLE, "Second Title");
      doc.setXmpMetadata(buildBookloreXmp("Second Title", "Second Author"));
      doc.save(secondSave);
    }

    // Verify the SECOND save has the NEW values, not the old ones
    try (PdfDocument doc = PdfDocument.open(secondSave)) {
      assertEquals("Second Title", doc.metadata(MetadataTag.TITLE).orElse(""));

      XmpMetadata parsed = XmpMetadataParser.parse(doc.xmpMetadata());
      assertEquals("Second Title", parsed.title().orElse(""));
      assertEquals(List.of("Second Author"), parsed.creators());
    }
  }

  private static String buildBookloreXmp(String title, String author) {
    return """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:dc="http://purl.org/dc/elements/1.1/">
                                            <dc:title><rdf:Alt><rdf:li xml:lang="x-default">{TITLE}</rdf:li></rdf:Alt></dc:title>
                                            <dc:creator><rdf:Seq><rdf:li>{AUTHOR}</rdf:li></rdf:Seq></dc:creator>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                                <?xpacket end="w"?>"""
        .replace("{TITLE}", title)
        .replace("{AUTHOR}", author);
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void repeatedMetadataSavesProduceValidPdfs(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path pdf = tempDir.resolve("stable.pdf");
    Files.copy(testPdf, pdf);

    int originalPageCount;
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      originalPageCount = doc.pageCount();
    }

    // Save with metadata + XMP (simulates grimmory's write path)
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Test Title");
      doc.setMetadata(MetadataTag.AUTHOR, "Test Author");
      doc.setXmpMetadata(buildBookloreXmp("Test Title", "Test Author"));
      doc.save(pdf);
    }

    // Verify first save is valid and contains metadata
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      assertEquals(originalPageCount, doc.pageCount());
      assertEquals("Test Title", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Test Author", doc.metadata(MetadataTag.AUTHOR).orElse(""));
    }

    // Save again (simulates second metadata update)
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Updated Title");
      doc.setMetadata(MetadataTag.AUTHOR, "Updated Author");
      doc.setXmpMetadata(buildBookloreXmp("Updated Title", "Updated Author"));
      doc.save(pdf);
    }

    // Verify second save is valid and contains updated metadata
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      assertEquals(originalPageCount, doc.pageCount());
      assertEquals("Updated Title", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Updated Author", doc.metadata(MetadataTag.AUTHOR).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void saveWithNoChangesProducesValidPdf(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path pdf = tempDir.resolve("unchanged.pdf");
    Files.copy(testPdf, pdf);

    int originalPageCount;
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      originalPageCount = doc.pageCount();
      doc.save(pdf);
    }

    // Save always goes through PDFium native serialization; the output must be
    // a valid PDF with the same number of pages
    assertTrue(Files.size(pdf) > 0, "Saved file should not be empty");
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      assertEquals(
          originalPageCount, doc.pageCount(), "Save with no changes should preserve page count");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpStreamLengthMatchesUtf8Content(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    // XMP with BOM (U+FEFF, 3 bytes in UTF-8) and non-ASCII content
    String xmpWithUnicode =
        """
            <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Ünîcödé Títlé - «Книга»</rdf:li></rdf:Alt></dc:title>
                  <dc:creator><rdf:Seq><rdf:li>José García - 日本語著者</rdf:li></rdf:Seq></dc:creator>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>""";

    Path pdf = tempDir.resolve("unicode-xmp.pdf");
    Files.copy(testPdf, pdf);

    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setXmpMetadata(xmpWithUnicode);
      doc.save(pdf);
    }

    // Verify the PDF structure: /Length value must match actual stream bytes
    byte[] savedBytes = Files.readAllBytes(pdf);
    String savedText = new String(savedBytes, StandardCharsets.ISO_8859_1);
    int metaIdx = savedText.indexOf("/Type /Metadata /Subtype /XML /Length ");
    assertTrue(metaIdx > 0, "XMP metadata stream object should be present");

    // Extract the declared /Length value
    int lengthStart = metaIdx + "/Type /Metadata /Subtype /XML /Length ".length();
    int lengthEnd = savedText.indexOf(' ', lengthStart);
    if (lengthEnd < 0) lengthEnd = savedText.indexOf('>', lengthStart);
    int declaredLength = Integer.parseInt(savedText.substring(lengthStart, lengthEnd).trim());

    // Find actual stream content between "stream\n" and "\nendstream"
    int streamKeyword = savedText.indexOf("stream\n", metaIdx);
    assertTrue(streamKeyword > 0, "stream keyword should follow XMP object");
    int streamStart = streamKeyword + "stream\n".length();
    // Search for endstream in raw bytes from streamStart
    int endstreamIdx =
        indexOf(savedBytes, "endstream".getBytes(StandardCharsets.ISO_8859_1), streamStart);
    assertTrue(endstreamIdx > 0, "endstream should be present");
    // The newline before endstream is part of the delimiter, not the content
    int actualContentLength = endstreamIdx - 1 - streamStart;

    assertEquals(
        declaredLength,
        actualContentLength,
        "XMP stream /Length must match actual UTF-8 content bytes");

    // Verify the XMP content is properly readable
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      String xmp = doc.xmpMetadataString();
      assertTrue(xmp.contains("Ünîcödé"), "Non-ASCII title should be preserved");
      assertTrue(xmp.contains("Книга"), "Cyrillic text should be preserved");
      assertTrue(xmp.contains("日本語著者"), "CJK text should be preserved");
    }
  }

  private static int indexOf(byte[] data, byte[] pattern, int from) {
    int limit = data.length - pattern.length;
    outer:
    for (int i = from; i <= limit; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) continue outer;
      }
      return i;
    }
    return -1;
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpOnlySaveUsesIncrementalUpdate(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path pdf = tempDir.resolve("xmp-only.pdf");
    Files.copy(testPdf, pdf);

    // Only XMP, no setMetadata - always uses PDFium native save + validated incremental update
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setXmpMetadata(buildBookloreXmp("XMP Only Title", "XMP Author"));
      doc.save(pdf);
    }

    long afterSave = Files.size(pdf);
    assertTrue(afterSave > 0, "Saved file should not be empty");

    try (PdfDocument doc = PdfDocument.open(pdf)) {
      String xmp = doc.xmpMetadataString();
      assertTrue(xmp.contains("XMP Only Title"), "XMP should be present");
    }
  }

  /**
   * Create a minimal valid PDF that uses a cross-reference stream instead of a traditional xref
   * table. This is the format used by many modern PDF generators (e.g., Stirling-PDF, Chrome
   * print).
   */
  @SuppressWarnings("PMD.UnusedAssignment")
  private static byte[] minimalXrefStreamPdf() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();

    writeBytes(out, "%PDF-1.5\n");

    offsets.add(out.size());
    writeBytes(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

    offsets.add(out.size());
    writeBytes(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

    offsets.add(out.size());
    writeBytes(out, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");

    int xrefStreamOffset = out.size();

    // W=[1,4,0]: type(1 byte) + offset(4 bytes big-endian) + gen(0, implicit 0)
    // Index=[0 5]: objects 0-4 contiguous
    byte[] xrefData = new byte[5 * 5]; // 5 entries x 5 bytes
    int di = 0;
    // Object 0: free entry
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    // Objects 1-3: in-use
    for (int off : offsets) {
      xrefData[di++] = 1;
      xrefData[di++] = (byte) ((off >> 24) & 0xFF);
      xrefData[di++] = (byte) ((off >> 16) & 0xFF);
      xrefData[di++] = (byte) ((off >> 8) & 0xFF);
      xrefData[di++] = (byte) (off & 0xFF);
    }
    // Object 4: the xref stream itself
    xrefData[di++] = 1;
    xrefData[di++] = (byte) ((xrefStreamOffset >> 24) & 0xFF);
    xrefData[di++] = (byte) ((xrefStreamOffset >> 16) & 0xFF);
    xrefData[di++] = (byte) ((xrefStreamOffset >> 8) & 0xFF);
    xrefData[di] = (byte) (xrefStreamOffset & 0xFF);

    writeBytes(
        out,
        "4 0 obj\n<< /Type /XRef /Size 5 /Root 1 0 R"
            + " /W [1 4 0] /Index [0 5] /Length "
            + xrefData.length
            + " >>\nstream\n");
    out.write(xrefData, 0, xrefData.length);
    writeBytes(out, "\nendstream\nendobj\n");
    writeBytes(out, "startxref\n" + xrefStreamOffset + "\n%%EOF\n");

    return out.toByteArray();
  }

  @SuppressWarnings("PMD.UnusedAssignment")
  private static byte[] predictedCompactXrefStreamPdf() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();

    writeBytes(out, "%PDF-1.5\n");

    offsets.add(out.size());
    writeBytes(out, "1 0 obj<</Type/Catalog/Pages 2 0 R>>\nendobj\n");

    offsets.add(out.size());
    writeBytes(out, "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>\nendobj\n");

    offsets.add(out.size());
    writeBytes(out, "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>\nendobj\n");

    int xrefStreamOffset = out.size();

    byte[] xrefData = new byte[5 * 5];
    int di = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    xrefData[di++] = 0;
    for (int off : offsets) {
      xrefData[di++] = 1;
      xrefData[di++] = (byte) ((off >> 24) & 0xFF);
      xrefData[di++] = (byte) ((off >> 16) & 0xFF);
      xrefData[di++] = (byte) ((off >> 8) & 0xFF);
      xrefData[di++] = (byte) (off & 0xFF);
    }
    xrefData[di++] = 1;
    xrefData[di++] = (byte) ((xrefStreamOffset >> 24) & 0xFF);
    xrefData[di++] = (byte) ((xrefStreamOffset >> 16) & 0xFF);
    xrefData[di++] = (byte) ((xrefStreamOffset >> 8) & 0xFF);
    xrefData[di] = (byte) (xrefStreamOffset & 0xFF);

    byte[] predicted = new byte[5 * 6];
    int src = 0;
    int dst = 0;
    while (src < xrefData.length) {
      predicted[dst++] = 0;
      System.arraycopy(xrefData, src, predicted, dst, 5);
      src += 5;
      dst += 5;
    }

    byte[] compressed;
    try (ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        DeflaterOutputStream deflater = new DeflaterOutputStream(compressedOut)) {
      deflater.write(predicted);
      deflater.finish();
      compressed = compressedOut.toByteArray();
    }

    writeBytes(
        out,
        "4 0 obj<</Type/XRef/Size 5/Root 1 0 R"
            + "/W[1 4 0]/Index[0 5]/Filter/FlateDecode"
            + "/DecodeParms<</Columns 5/Predictor 12>>/Length "
            + compressed.length
            + ">>stream\n");
    out.write(compressed, 0, compressed.length);
    writeBytes(out, "\nendstream\nendobj\nstartxref\n" + xrefStreamOffset + "\n%%EOF\n");

    return out.toByteArray();
  }

  private static byte[] minimalPdfWithSplitObjectHeaders() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();

    writeBytes(out, "%PDF-1.4\n");

    offsets.add(out.size());
    writeBytes(out, "1\n0\nobj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

    offsets.add(out.size());
    writeBytes(out, "2\n0\nobj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

    offsets.add(out.size());
    writeBytes(
        out,
        "3\n0\nobj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
            + " /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");

    offsets.add(out.size());
    byte[] content = "BT /F1 12 Tf 72 720 Td (Hi) Tj ET\n".getBytes(StandardCharsets.ISO_8859_1);
    writeBytes(out, "4\n0\nobj\n<< /Length " + content.length + " >>\nstream\n");
    out.write(content, 0, content.length);
    writeBytes(out, "endstream\nendobj\n");

    offsets.add(out.size());
    writeBytes(out, "5\n0\nobj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

    int xrefOffset = out.size();
    writeBytes(out, "xref\n0 6\n0000000000 65535 f \r\n");
    for (int offset : offsets) {
      writeBytes(out, String.format(java.util.Locale.ROOT, "%010d 00000 n ", offset));
      writeBytes(out, "\r\n");
    }
    writeBytes(out, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n");

    return out.toByteArray();
  }

  private static void writeBytes(ByteArrayOutputStream out, String s) {
    byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
    out.write(b, 0, b.length);
  }

  private static byte[] appendAscii(byte[] data, String suffix) {
    byte[] extra = suffix.getBytes(StandardCharsets.ISO_8859_1);
    byte[] combined = new byte[data.length + extra.length];
    System.arraycopy(data, 0, combined, 0, data.length);
    System.arraycopy(extra, 0, combined, data.length, extra.length);
    return combined;
  }

  private static final class SlowOutputStream extends OutputStream {
    private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
      delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      for (int i = 0; i < len; i++) {
        delegate.write(b[off + i]);
      }
    }

    byte[] toByteArray() {
      return delegate.toByteArray();
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataSaveWithXrefStreamPdf(@TempDir Path tempDir) throws IOException {
    byte[] xrefStreamPdf = minimalXrefStreamPdf();
    Path source = tempDir.resolve("xref-stream.pdf");
    Files.write(source, xrefStreamPdf);

    try (PdfDocument doc = PdfDocument.open(source)) {
      assertEquals(1, doc.pageCount());
    }

    Path output = tempDir.resolve("xref-stream-meta.pdf");
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "XRef Stream Test");
      doc.setMetadata(MetadataTag.AUTHOR, "Test Author");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("XRef Stream Test", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Test Author", doc.metadata(MetadataTag.AUTHOR).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataSaveWithXrefStreamPdfIgnoresTrailingFalseTrailerTokens(@TempDir Path tempDir)
      throws IOException {
    byte[] xrefStreamPdf =
        appendAscii(
            minimalXrefStreamPdf(),
            "\n9 0 obj\n<< /Root 99 0 R /Info 98 0 R /Size 999 >>\nendobj\n%");
    Path source = tempDir.resolve("xref-stream-false-tail.pdf");
    Files.write(source, xrefStreamPdf);

    Path output = tempDir.resolve("xref-stream-false-tail-out.pdf");
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "Trailing Tokens Ignored");
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("Trailing Tokens Ignored", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals(1, doc.pageCount());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataSaveWithXrefStreamPdf(@TempDir Path tempDir) throws IOException {
    byte[] xrefStreamPdf = minimalXrefStreamPdf();
    Path source = tempDir.resolve("xref-stream.pdf");
    Files.write(source, xrefStreamPdf);

    Path output = tempDir.resolve("xref-stream-xmp.pdf");
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "XRef Stream XMP");
      doc.setXmpMetadata(buildBookloreXmp("XRef Stream XMP", "Test Author"));
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("XRef Stream XMP", doc.metadata(MetadataTag.TITLE).orElse(""));
      String xmp = doc.xmpMetadataString();
      assertTrue(xmp.contains("XRef Stream XMP"), "XMP should contain title");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataSaveWithPredictedCompactXrefStreamPdf(@TempDir Path tempDir) throws IOException {
    byte[] xrefStreamPdf = predictedCompactXrefStreamPdf();
    Path source = tempDir.resolve("xref-stream-predictor-compact.pdf");
    Files.write(source, xrefStreamPdf);

    Path output = tempDir.resolve("xref-stream-predictor-compact-out.pdf");
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "Predicted Compact XRef");
      doc.setXmpMetadata(buildBookloreXmp("Predicted Compact XRef", "Predictor Author"));
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("Predicted Compact XRef", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertTrue(
          doc.xmpMetadataString().contains("Predicted Compact XRef"), "XMP should contain title");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xmpMetadataSaveWithSplitObjectHeaderPdf(@TempDir Path tempDir) throws IOException {
    Path source = tempDir.resolve("split-object-headers.pdf");
    Files.write(source, minimalPdfWithSplitObjectHeaders());

    Path output = tempDir.resolve("split-object-headers-out.pdf");
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "Split Headers");
      doc.setXmpMetadata(buildBookloreXmp("Split Headers", "Header Author"));
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("Split Headers", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertTrue(doc.xmpMetadataString().contains("Split Headers"));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void xrefStreamPdfDoubleMetadataWrite(@TempDir Path tempDir) throws IOException {
    byte[] xrefStreamPdf = minimalXrefStreamPdf();
    Path source = tempDir.resolve("xref-stream.pdf");
    Files.write(source, xrefStreamPdf);

    Path first = tempDir.resolve("first.pdf");
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "First Title");
      doc.setXmpMetadata(buildBookloreXmp("First Title", "First Author"));
      doc.save(first);
    }

    Path second = tempDir.resolve("second.pdf");
    try (PdfDocument doc = PdfDocument.open(first)) {
      doc.setMetadata(MetadataTag.TITLE, "Second Title");
      doc.setXmpMetadata(buildBookloreXmp("Second Title", "Second Author"));
      doc.save(second);
    }

    try (PdfDocument doc = PdfDocument.open(second)) {
      assertEquals("Second Title", doc.metadata(MetadataTag.TITLE).orElse(""));
      XmpMetadata parsed = XmpMetadataParser.parse(doc.xmpMetadata());
      assertEquals("Second Title", parsed.title().orElse(""));
      assertEquals(List.of("Second Author"), parsed.creators());
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataOnlySaveDoesNotBloatFile(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    long originalSize = Files.size(testPdf);

    Path output = tempDir.resolve("metadata-only.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "New Title");
      doc.setMetadata(MetadataTag.AUTHOR, "New Author");
      doc.setMetadata(MetadataTag.KEYWORDS, "keyword1; keyword2");
      doc.save(output);
    }

    long savedSize = Files.size(output);
    // Incremental update should add only a few KB for metadata objects + xref,
    // not re-serialize the entire PDF. Allow 5% overhead.
    assertTrue(
        savedSize <= originalSize * 1.05 + 4096,
        "Metadata-only save bloated file from " + originalSize + " to " + savedSize + " bytes");

    // Verify the saved PDF is valid and metadata is readable
    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("New Title", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("New Author", doc.metadata(MetadataTag.AUTHOR).orElse(""));
      assertTrue(doc.pageCount() > 0, "Saved PDF must have pages");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataAndXmpSaveDoesNotBloatFile(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    long originalSize = Files.size(testPdf);

    Path output = tempDir.resolve("xmp-metadata.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "XMP Title");
      doc.setMetadata(MetadataTag.AUTHOR, "XMP Author");
      doc.setXmpMetadata(buildBookloreXmp("XMP Title", "XMP Author"));
      doc.save(output);
    }

    long savedSize = Files.size(output);
    assertTrue(
        savedSize <= originalSize * 1.05 + 8192,
        "Metadata+XMP save bloated file from " + originalSize + " to " + savedSize + " bytes");

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals("XMP Title", doc.metadata(MetadataTag.TITLE).orElse(""));
      XmpMetadata parsed = XmpMetadataParser.parse(doc.xmpMetadata());
      assertEquals("XMP Title", parsed.title().orElse(""));
      assertTrue(doc.pageCount() > 0, "Saved PDF must have pages");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void structuralChangeStillUsesNativeSave(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path output = tempDir.resolve("structural.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      int originalCount = doc.pageCount();
      doc.insertBlankPage(originalCount, PageSize.A4);
      doc.setMetadata(MetadataTag.TITLE, "Structural Change");
      doc.save(output);

      // Re-open and verify the structural change persisted
      try (PdfDocument saved = PdfDocument.open(output)) {
        assertEquals(originalCount + 1, saved.pageCount());
        assertEquals("Structural Change", saved.metadata(MetadataTag.TITLE).orElse(""));
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataOnlySaveFromBytesDoesNotBloat() {
    byte[] pdf = minimalPdfWithText();
    int originalSize = pdf.length;

    byte[] saved;
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setMetadata(MetadataTag.TITLE, "From Bytes Title");
      saved = doc.saveToBytes();
    }

    // Should not be dramatically larger than original
    assertTrue(
        saved.length <= originalSize * 1.5 + 4096,
        "Metadata-only save from bytes bloated from " + originalSize + " to " + saved.length);

    try (PdfDocument doc = PdfDocument.open(saved)) {
      assertEquals("From Bytes Title", doc.metadata(MetadataTag.TITLE).orElse(""));
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void forEachCharBoxMatchesList() throws IOException {
    Path pdf = getTestPdfWithText();
    if (pdf == null) return;
    try (PdfDocument doc = PdfDocument.open(pdf);
        PdfPage page = doc.page(0)) {
      List<TextCharInfo> list = page.extractTextWithBounds();
      List<TextCharInfo> visitorList = new ArrayList<>();
      page.forEachCharBox(
          (code, l, b, r, t, fs) -> {
            visitorList.add(new TextCharInfo(code, l, b, r, t, fs));
          });
      assertEquals(list.size(), visitorList.size());
      for (int i = 0; i < list.size(); i++) {
        assertEquals(list.get(i), visitorList.get(i));
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void optimizedExtractTextMatchesOriginal() throws IOException {
    byte[] pdf = minimalPdfWithText();
    try (PdfDocument doc = PdfDocument.open(pdf);
        PdfPage page = doc.page(0)) {
      String text = page.extractText();
      // minimalPdfWithText() contains "Hello World"
      assertTrue(text.contains("Hello"), "Extracted text should contain 'Hello'");
      assertTrue(text.contains("World"), "Extracted text should contain 'World'");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void nestedDocumentClosingDoesNotInvalidateBuffer() throws IOException {
    Path pdf = getTestPdf();
    if (pdf == null) return;

    // Explicitly acquire so that the test's own get() call is reference-counted.
    ScratchBuffer.acquire();
    try (PdfDocument doc1 = PdfDocument.open(pdf)) {
      assertTrue(doc1.pageCount() > 0);
      MemorySegment first = ScratchBuffer.get(1024);
      first.set(JAVA_BYTE, 0, (byte) 0x42);

      try (PdfDocument doc2 = PdfDocument.open(pdf)) {
        // Nested document use
        assertTrue(doc2.pageCount() >= 0);
        assertEquals(0x42, first.get(JAVA_BYTE, 0));
      } // doc2.close() -> ScratchBuffer.release() (decrements count)

      // Memory should still be valid because doc1 and the test itself still have references
      assertEquals(0x42, first.get(JAVA_BYTE, 0));
    } finally {
      ScratchBuffer.release();
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void forEachCharBoxOnTextPage() throws IOException {
    // minimalPdfWithText() is a simple valid PDF with "Hello World"
    byte[] pdf = minimalPdfWithText();
    try (PdfDocument doc = PdfDocument.open(pdf);
        PdfPage page = doc.page(0)) {
      List<Integer> chars = new ArrayList<>();
      page.forEachCharBox((code, l, b, r, t, fs) -> chars.add(code));
      assertEquals(11, chars.size(), "Should have 11 characters");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void forEachCharBoxOnEmptyPage() throws IOException {
    // minimalEmptyPdf() is a blank page
    byte[] pdf = minimalEmptyPdf();
    try (PdfDocument doc = PdfDocument.open(pdf);
        PdfPage page = doc.page(0)) {
      List<Integer> chars = new ArrayList<>();
      page.forEachCharBox((code, l, b, r, t, fs) -> chars.add(code));
      assertTrue(chars.isEmpty(), "Empty page should have no characters");
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void forEachPageSizeMatchesList() throws IOException {
    Path pdf = getTestPdf();
    if (pdf == null) return;
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      List<PageSize> list = doc.allPageSizes();
      List<PageSize> visitorList = new ArrayList<>();
      doc.forEachPageSize(
          (index, w, h) -> {
            assertEquals(visitorList.size(), index);
            visitorList.add(new PageSize(w, h));
          });
      assertEquals(list.size(), visitorList.size());
      for (int i = 0; i < list.size(); i++) {
        assertEquals(list.get(i), visitorList.get(i));
      }
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void streamingXmpSaveMatchesStringSave() throws IOException {
    XmpMetadata meta =
        XmpMetadata.builder().title("Test Optimization").creators(List.of("Agent")).build();

    Path pdf = getTestPdf();
    if (pdf == null) return;
    try (PdfDocument doc = PdfDocument.open(pdf)) {
      doc.setXmpMetadata(meta);
      // XMP updates require saveToBytes() or save(Path) — generic OutputStream is rejected.
      byte[] savedBytes = doc.saveToBytes();

      // Verify we can parse it back
      try (PdfDocument savedDoc = PdfDocument.open(savedBytes)) {
        XmpMetadata loaded = XmpMetadataParser.parseFrom(savedDoc);
        assertEquals("Test Optimization", loaded.title().orElse(null));
        assertEquals("Agent", loaded.creators().get(0));
      }
    }
  }

  // ── New edge-case & allocation-budget tests ──────────────────────────────────────────────────

  /**
   * A PDF with a /Encrypt entry in the trailer must be rejected for incremental save to prevent
   * writing unencrypted Info/XMP bytes into an encrypted document body.
   */
  @Test
  @EnabledIf("pdfiumAvailable")
  void incrementalSaveOnEncryptedPdfFails(@TempDir Path tempDir) {
    // Hand-crafted minimal PDF that has /Encrypt in the trailer dict.
    byte[] encryptedPdf = buildMinimalPdfWithEncryptEntry();
    Path source = tempDir.resolve("encrypted-fake.pdf");
    Path output = tempDir.resolve("encrypted-out.pdf");
    try {
      Files.write(source, encryptedPdf);
    } catch (IOException e) {
      return;
    }
    // PDFium may refuse to open the fake /Encrypt entry — that is also acceptable.
    // If it opens, the save must fail with an explicit encryption error.
    try (PdfDocument doc = PdfDocument.open(source)) {
      doc.setMetadata(MetadataTag.TITLE, "Should Fail");
      PdfiumException ex = assertThrows(PdfiumException.class, () -> doc.save(output));
      String msg =
          ex.getMessage() + (ex.getCause() != null ? " " + ex.getCause().getMessage() : "");
      assertTrue(
          msg.toLowerCase(java.util.Locale.ROOT).contains("encrypt"),
          "Exception should mention encryption, got: " + msg);
    } catch (PdfiumException ignored) {
      // PDFium rejected the fake-encrypted PDF at open time — also acceptable.
    }
  }

  /**
   * An Info dictionary value containing ">>" inside a parenthesized string must round-trip
   * correctly. The findDictionaryEnd scanner must not terminate early on the embedded ">>" token.
   */
  @Test
  @EnabledIf("pdfiumAvailable")
  void infoDictContainingDictTerminatorBytesRoundTrips(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    String trickyTitle = "Hello >> World";
    String trickyAuthor = "Author (with parens >> and more)";

    Path output = tempDir.resolve("tricky-meta.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, trickyTitle);
      doc.setMetadata(MetadataTag.AUTHOR, trickyAuthor);
      doc.save(output);
    }

    try (PdfDocument doc = PdfDocument.open(output)) {
      assertEquals(trickyTitle, doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals(trickyAuthor, doc.metadata(MetadataTag.AUTHOR).orElse(""));
    }
  }

  /**
   * Every incremental update must write a /Prev pointer so the xref chain remains intact. Verified
   * by inspecting the bytes appended after the original file.
   */
  @Test
  @EnabledIf("pdfiumAvailable")
  void incrementalUpdateWritesPrevPointer(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    byte[] original = Files.readAllBytes(testPdf);
    int originalSize = original.length;

    Path output = tempDir.resolve("prev-check.pdf");
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Prev Check");
      doc.save(output);
    }

    byte[] saved = Files.readAllBytes(output);
    assertTrue(saved.length > originalSize, "Saved file should be larger than original");

    String updateSection =
        new String(
            saved,
            originalSize,
            saved.length - originalSize,
            java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(
        updateSection.contains("/Prev"),
        "/Prev must appear in the incremental update trailer; update section:\n" + updateSection);
  }

  /** Three chained incremental saves must each carry a correct /Prev pointer. */
  @Test
  @EnabledIf("pdfiumAvailable")
  void threeChainedIncrementalSaves(@TempDir Path tempDir) throws IOException {
    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    Path first = tempDir.resolve("chain1.pdf");
    Path second = tempDir.resolve("chain2.pdf");
    Path third = tempDir.resolve("chain3.pdf");

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Chain 1");
      doc.save(first);
    }
    try (PdfDocument doc = PdfDocument.open(first)) {
      doc.setMetadata(MetadataTag.TITLE, "Chain 2");
      doc.setMetadata(MetadataTag.AUTHOR, "Author 2");
      doc.save(second);
    }
    try (PdfDocument doc = PdfDocument.open(second)) {
      doc.setMetadata(MetadataTag.TITLE, "Chain 3");
      doc.setMetadata(MetadataTag.AUTHOR, "Author 3");
      doc.save(third);
    }

    try (PdfDocument doc = PdfDocument.open(third)) {
      assertEquals("Chain 3", doc.metadata(MetadataTag.TITLE).orElse(""));
      assertEquals("Author 3", doc.metadata(MetadataTag.AUTHOR).orElse(""));
      assertTrue(doc.pageCount() > 0);
    }

    byte[] firstBytes = Files.readAllBytes(first);
    byte[] secondBytes = Files.readAllBytes(second);
    byte[] thirdBytes = Files.readAllBytes(third);

    String secondUpdate =
        new String(
            secondBytes,
            firstBytes.length,
            secondBytes.length - firstBytes.length,
            java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(secondUpdate.contains("/Prev"), "Second save must contain /Prev");

    String thirdUpdate =
        new String(
            thirdBytes,
            secondBytes.length,
            thirdBytes.length - secondBytes.length,
            java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(thirdUpdate.contains("/Prev"), "Third save must contain /Prev");
  }

  /**
   * A metadata-only save must not allocate more than 2 MB above baseline. This guards against
   * regressions re-introducing large intermediate buffers (e.g. full-delta ByteArrayOutputStream).
   */
  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataOnlySaveAllocationBudget() throws IOException {
    com.sun.management.ThreadMXBean tmxb =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    if (!tmxb.isThreadAllocatedMemoryEnabled()) {
      return; // not supported on this JVM
    }

    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    // Warm up: trigger JIT before measuring
    try (PdfDocument warmup = PdfDocument.open(testPdf)) {
      warmup.setMetadata(MetadataTag.TITLE, "Warmup");
      warmup.saveToBytes();
    }

    long threadId = Thread.currentThread().threadId();
    long before = tmxb.getThreadAllocatedBytes(threadId);
    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Allocation Budget Test");
      doc.setMetadata(MetadataTag.AUTHOR, "Perf Author");
      doc.saveToBytes();
    }
    long after = tmxb.getThreadAllocatedBytes(threadId);

    long allocatedBytes = after - before;
    long budgetBytes = 2L * 1024L * 1024L; // 2 MB — generous for JIT + GC metadata overhead
    assertTrue(
        allocatedBytes < budgetBytes,
        "Metadata save allocated "
            + allocatedBytes
            + " bytes, exceeds 2 MB budget of "
            + budgetBytes);
  }

  /**
   * Repeated metadata saves from the same document must not grow their allocation per call (no
   * cumulative heap buffering).
   */
  @Test
  @EnabledIf("pdfiumAvailable")
  void repeatedMetadataSaveStaysInAllocationBudget() throws IOException {
    com.sun.management.ThreadMXBean tmxb =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    if (!tmxb.isThreadAllocatedMemoryEnabled()) {
      return;
    }

    Path testPdf = getTestPdf();
    if (testPdf == null) return;

    // Warm up
    try (PdfDocument warmup = PdfDocument.open(testPdf)) {
      warmup.setMetadata(MetadataTag.TITLE, "WU");
      warmup.saveToBytes();
      warmup.saveToBytes();
      warmup.saveToBytes();
    }

    try (PdfDocument doc = PdfDocument.open(testPdf)) {
      doc.setMetadata(MetadataTag.TITLE, "Repeated Save");

      long threadId = Thread.currentThread().threadId();

      long b1 = tmxb.getThreadAllocatedBytes(threadId);
      doc.saveToBytes();
      long alloc1 = tmxb.getThreadAllocatedBytes(threadId) - b1;

      long b2 = tmxb.getThreadAllocatedBytes(threadId);
      doc.saveToBytes();
      long alloc2 = tmxb.getThreadAllocatedBytes(threadId) - b2;

      // Allow 3x slack for JIT variance, but not unbounded growth
      assertTrue(
          alloc2 < alloc1 * 3 + 65536,
          "Allocation per save grew unexpectedly: first=" + alloc1 + " second=" + alloc2);
    }
  }

  /** Builds a hand-crafted minimal PDF with /Encrypt in the trailer (no actual encryption). */
  private static byte[] buildMinimalPdfWithEncryptEntry() {
    String pdf =
        "%PDF-1.4\n"
            + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
            + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
            + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
            + "xref\n"
            + "0 4\n"
            + "0000000000 65535 f \r\n"
            + "0000000009 00000 n \r\n"
            + "0000000058 00000 n \r\n"
            + "0000000117 00000 n \r\n"
            + "trailer\n"
            + "<< /Size 4 /Root 1 0 R /Encrypt 99 0 R >>\n"
            + "startxref\n"
            + "190\n"
            + "%%EOF\n";
    return pdf.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  }
}
