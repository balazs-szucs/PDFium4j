package org.grimmory.pdfium4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.grimmory.pdfium4j.internal.ViewBindings;
import org.junit.jupiter.api.Test;

class RenderFlagsTest {

  @Test
  void builderSupportsLcdTextAndGrayscalePerInstance() {
    RenderFlags withLcd = RenderFlags.builder().lcdText(true).build();
    RenderFlags withGray = RenderFlags.builder().grayscale(true).build();

    assertTrue((withLcd.value() & ViewBindings.FPDF_LCD_TEXT) != 0);
    assertTrue((withGray.value() & ViewBindings.FPDF_GRAYSCALE) != 0);
    assertNotEquals(withLcd.value(), withGray.value());
  }

  @Test
  void builderInstancesRemainIndependent() {
    RenderFlags defaults = RenderFlags.builder().build();
    RenderFlags custom = RenderFlags.builder().lcdText(true).grayscale(true).build();

    assertEquals(0, defaults.value() & ViewBindings.FPDF_LCD_TEXT);
    assertEquals(0, defaults.value() & ViewBindings.FPDF_GRAYSCALE);
    assertTrue((custom.value() & ViewBindings.FPDF_LCD_TEXT) != 0);
    assertTrue((custom.value() & ViewBindings.FPDF_GRAYSCALE) != 0);
  }
}
