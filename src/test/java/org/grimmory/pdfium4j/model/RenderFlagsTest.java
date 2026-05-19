package org.grimmory.pdfium4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.grimmory.pdfium4j.internal.ViewBindings;
import org.junit.jupiter.api.Test;

class RenderFlagsTest {

  @Test
  void viewerProfileEnablesLcdTextAndAnnotations() {
    int flags = RenderFlags.forProfile(RenderProfile.VIEWER).value();
    assertNotEquals(0, flags & ViewBindings.FPDF_ANNOT);
    assertNotEquals(0, flags & ViewBindings.FPDF_LCD_TEXT);
    assertNotEquals(0, flags & ViewBindings.FPDF_REVERSE_BYTE_ORDER);
  }

  @Test
  void prefetchProfileDisablesNativeText() {
    int flags = RenderFlags.forProfile(RenderProfile.PREFETCH).value();
    assertNotEquals(0, flags & ViewBindings.FPDF_ANNOT);
    assertNotEquals(0, flags & ViewBindings.FPDF_NO_NATIVETEXT);
  }

  @Test
  void thumbnailProfileTurnsOffSmoothingAndLimitsImageCache() {
    int flags = RenderFlags.forProfile(RenderProfile.THUMBNAIL).value();
    int noSmoothMask =
        ViewBindings.FPDF_RENDER_NO_SMOOTHTEXT
            | ViewBindings.FPDF_RENDER_NO_SMOOTHIMAGE
            | ViewBindings.FPDF_RENDER_NO_SMOOTHPATH;

    assertEquals(0, flags & ViewBindings.FPDF_ANNOT);
    assertEquals(noSmoothMask, flags & noSmoothMask);
    assertNotEquals(0, flags & ViewBindings.FPDF_RENDER_LIMITEDIMAGECACHE);
  }

  @Test
  void printProfileEnablesPrintingAndHalftone() {
    int flags = RenderFlags.forProfile(RenderProfile.PRINT).value();
    assertNotEquals(0, flags & ViewBindings.FPDF_PRINTING);
    assertNotEquals(0, flags & ViewBindings.FPDF_RENDER_FORCEHALFTONE);
    assertNotEquals(0, flags & ViewBindings.FPDF_ANNOT);
  }

  @Test
  void archiveProfileEnablesPrintingGrayscaleAndNoNativeText() {
    int flags = RenderFlags.forProfile(RenderProfile.ARCHIVE).value();
    assertNotEquals(0, flags & ViewBindings.FPDF_PRINTING);
    assertNotEquals(0, flags & ViewBindings.FPDF_GRAYSCALE);
    assertNotEquals(0, flags & ViewBindings.FPDF_NO_NATIVETEXT);
  }
}
