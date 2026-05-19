package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.grimmory.pdfium4j.model.BitmapSlab;
import org.grimmory.pdfium4j.model.PageSize;
import org.grimmory.pdfium4j.model.RenderFlags;
import org.grimmory.pdfium4j.model.RenderProfile;
import org.grimmory.pdfium4j.model.RenderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class CorpusRenderMethodsRegressionTest {

  static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable _) {
      return false;
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void corpusRenderMethodsDoNotCorruptFiles() throws Exception {
    String corpusDirProp = System.getProperty("corpus.dir");
    if (corpusDirProp == null || corpusDirProp.isBlank()) {
      corpusDirProp = System.getenv("CORPUS_DIR");
    }
    assertTrue(corpusDirProp != null && !corpusDirProp.isBlank(), "-Dcorpus.dir must be provided");

    Path corpusDir = Path.of(corpusDirProp);
    assertTrue(corpusDir.isAbsolute(), "corpus.dir must be an absolute path");
    assertTrue(Files.isDirectory(corpusDir), "corpus.dir must be an existing directory");

    AtomicInteger total = new AtomicInteger();
    AtomicInteger opened = new AtomicInteger();
    AtomicInteger skippedOpen = new AtomicInteger();
    AtomicInteger renderFailures = new AtomicInteger();

    try (Stream<Path> files = Files.walk(corpusDir)) {
      files
          .filter(Files::isRegularFile)
          .filter(CorpusRenderMethodsRegressionTest::isPdf)
          .forEach(
              pdf -> {
                total.incrementAndGet();
                try {
                  byte[] before = Files.readAllBytes(pdf);
                  String beforeHash = sha256Hex(before);

                  try (PdfDocument doc = PdfDocument.open(pdf)) {
                    if (doc.pageCount() <= 0) {
                      opened.incrementAndGet();
                      String afterHash = sha256Hex(Files.readAllBytes(pdf));
                      assertEquals(beforeHash, afterHash, "File changed unexpectedly: " + pdf);
                      return;
                    }

                    try (PdfPage page = doc.page(0);
                        BitmapSlab slab = new BitmapSlab();
                        Arena arena = Arena.ofConfined()) {
                      RenderResult viewer = page.render(120, RenderProfile.VIEWER);
                      assertTrue(
                          viewer.width() > 0 && viewer.height() > 0, "viewer render empty: " + pdf);

                      RenderResult asyncViewer = page.renderAsync(120, RenderProfile.VIEWER).join();
                      assertEquals(
                          viewer.width(), asyncViewer.width(), "async width mismatch: " + pdf);

                      PageSize size = page.size();
                      float viewW = Math.max(1f, size.width() / 2f);
                      float viewH = Math.max(1f, size.height() / 2f);
                      RenderResult viewport =
                          page.renderViewport(
                              0f,
                              0f,
                              viewW,
                              viewH,
                              120,
                              RenderFlags.forProfile(RenderProfile.VIEWER));
                      assertTrue(
                          viewport.width() > 0 && viewport.height() > 0, "viewport empty: " + pdf);

                      int w = 256;
                      int h = 256;
                      MemorySegment seg = arena.allocate((long) w * h * 4);
                      page.renderProgressiveTo(
                          seg,
                          w,
                          h,
                          w * 4,
                          RenderFlags.forProfile(RenderProfile.PREFETCH).value(),
                          0xFFFFFFFF,
                          3000);
                      byte[] progressiveBytes = seg.asSlice(0, (long) w * h * 4).toArray(JAVA_BYTE);
                      assertEquals(
                          w * h * 4, progressiveBytes.length, "progressive size mismatch: " + pdf);

                      page.renderToSlab(
                          slab, 96, RenderFlags.forProfile(RenderProfile.THUMBNAIL), 0xFFFFFFFF);
                      assertTrue(
                          slab.width() > 0 && slab.height() > 0, "slab render empty: " + pdf);
                    }
                  }

                  opened.incrementAndGet();
                  String afterHash = sha256Hex(Files.readAllBytes(pdf));
                  assertEquals(beforeHash, afterHash, "File changed unexpectedly: " + pdf);
                } catch (Throwable t) {
                  if (isExpectedCorpusFailure(t)) {
                    skippedOpen.incrementAndGet();
                  } else {
                    renderFailures.incrementAndGet();
                    System.err.println("RENDER FAILURE: " + pdf + " :: " + t);
                  }
                }
              });
    }

    assertTrue(total.get() > 0, "No PDFs found in corpus: " + corpusDir);
    assertTrue(opened.get() > 0, "No PDFs opened successfully in corpus: " + corpusDir);
    assertEquals(
        0,
        renderFailures.get(),
        "Render regressions found. total="
            + total.get()
            + " opened="
            + opened.get()
            + " skippedOpen="
            + skippedOpen.get()
            + " renderFailures="
            + renderFailures.get());
  }

  private static boolean isPdf(Path p) {
    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
    return n.endsWith(".pdf");
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(md.digest(bytes));
  }

  private static boolean isExpectedCorpusFailure(Throwable t) {
    Throwable cur = t;
    while (cur != null) {
      String msg = cur.getMessage();
      String norm = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
      if (norm.contains("password")
          || norm.contains("encrypted")
          || norm.contains("format")
          || norm.contains("corrupt")
          || norm.contains("failed to load page")
          || norm.contains("failed to open")
          || norm.contains("repair failed")
          || norm.contains("already closed")
          || norm.contains("exceeds policy pixel budget")) {
        return true;
      }
      cur = cur.getCause();
    }
    return false;
  }
}
