package org.grimmory.pdfium4j.model;

/** Represents a rectangular page boundary box (e.g. MediaBox, CropBox) in PDF points. */
public record PageBox(float left, float bottom, float right, float top) {

  public float width() {
    return Math.abs(right - left);
  }

  public float height() {
    return Math.abs(top - bottom);
  }

  @Override
  public String toString() {
    return "PageBox[left=%.2f, bottom=%.2f, right=%.2f, top=%.2f, width=%.2f, height=%.2f]"
        .formatted(left, bottom, right, top, width(), height());
  }
}
