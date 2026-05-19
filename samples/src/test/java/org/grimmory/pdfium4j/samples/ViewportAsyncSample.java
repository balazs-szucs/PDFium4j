package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.BitmapSlab;
import org.grimmory.pdfium4j.model.PageSize;
import org.grimmory.pdfium4j.model.RenderFlags;
import org.grimmory.pdfium4j.model.RenderProfile;
import org.grimmory.pdfium4j.model.RenderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

public class ViewportAsyncSample {

  private static final List<String> INPUT_PDFS =
      List.of("../corpus/gutenberg/1063_The Cask of Amontillado.pdf");

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
  public void runViewportAsyncSample() {
    try {
      PdfiumLibrary.initialize();
      Path outputDir = Path.of("samples_output/viewport_async_samples");
      Files.createDirectories(outputDir);

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf));

        try (PdfDocument doc = PdfDocument.open(inputPdf);
            PdfPage page = doc.page(0);
            BitmapSlab slab = new BitmapSlab();
            Arena arena = Arena.ofConfined()) {

          // Async viewer render
          RenderResult full = page.renderAsync(144, RenderProfile.VIEWER).join();
          File fullOut = outputDir.resolve("viewer_full.png").toFile();
          writeRgbaPng(full.rgba(), full.width(), full.height(), fullOut);
          assertTrue(fullOut.exists() && fullOut.length() > 0);

          // Matrix-based viewport render
          PageSize size = page.size();
          float viewW = Math.max(1f, size.width() / 2f);
          float viewH = Math.max(1f, size.height() / 2f);
          RenderResult viewport =
              page.renderViewport(0f, 0f, viewW, viewH, 144, RenderFlags.forProfile(RenderProfile.VIEWER));
          File viewportOut = outputDir.resolve("viewer_viewport.png").toFile();
          writeRgbaPng(viewport.rgba(), viewport.width(), viewport.height(), viewportOut);
          assertTrue(viewportOut.exists() && viewportOut.length() > 0);

          // Progressive render into caller-owned segment
          int w = 512;
          int h = 512;
          MemorySegment progressiveBuffer = arena.allocate((long) w * h * 4);
          page.renderProgressiveTo(
              progressiveBuffer,
              w,
              h,
              w * 4,
              RenderFlags.forProfile(RenderProfile.PREFETCH).value(),
              0xFFFFFFFF,
              3000);
          File progressiveOut = outputDir.resolve("prefetch_progressive.png").toFile();
          writeRgbaPng(progressiveBuffer.asSlice(0, (long) w * h * 4).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), w, h, progressiveOut);
          assertTrue(progressiveOut.exists() && progressiveOut.length() > 0);

          // External reusable slab render
          page.renderToSlab(slab, 120, RenderFlags.forProfile(RenderProfile.THUMBNAIL), 0xFFFFFFFF);
          File slabOut = outputDir.resolve("thumbnail_slab.png").toFile();
          writeRgbaPng(
              slab.pixels().asSlice(0, (long) slab.stride() * slab.height()).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
              slab.width(),
              slab.height(),
              slabOut);
          assertTrue(slabOut.exists() && slabOut.length() > 0);
        }
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw new AssertionError(t);
    }
  }

  private static void writeRgbaPng(byte[] rgba, int width, int height, File out) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
    byte[] abgr = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
    for (int i = 0; i < rgba.length; i += 4) {
      abgr[i] = rgba[i + 3];
      abgr[i + 1] = rgba[i];
      abgr[i + 2] = rgba[i + 1];
      abgr[i + 3] = rgba[i + 2];
    }
    ImageIO.write(image, "png", out);
  }
}
