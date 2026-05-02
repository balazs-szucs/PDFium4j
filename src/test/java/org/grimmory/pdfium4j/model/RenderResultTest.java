package org.grimmory.pdfium4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class RenderResultTest {

  @Test
  void testEqualityAndHashCode() {
    byte[] data1 = {1, 2, 3, 4};
    byte[] data2 = {1, 2, 3, 4};
    byte[] data3 = {1, 2, 3, 5};

    RenderResult r1 = new RenderResult(1, 1, data1);
    RenderResult r2 = new RenderResult(1, 1, data2);
    RenderResult r3 = new RenderResult(1, 1, data3);
    RenderResult r4 = new RenderResult(2, 1, data1);

    assertNotEquals(r1, r2);
    assertNotEquals(r1.hashCode(), r2.hashCode());

    assertEquals(true, r1.contentEquals(r2));
    assertEquals(r1.contentHashCode(), r2.contentHashCode());
    assertEquals(false, r1.contentEquals(r3));
    assertNotEquals(r1.contentHashCode(), r3.contentHashCode());
    assertNotEquals(r1, r4);

    assertEquals(false, r1.contentEquals(r4));
  }
}
