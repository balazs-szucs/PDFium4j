package org.grimmory.pdfium4j.model;

import java.util.Objects;
import org.grimmory.pdfium4j.internal.ViewBindings;

/**
 * Rendering flags for PDF page rasterization.
 *
 * <p>Use the builder to combine flags:
 *
 * <pre>{@code
 * RenderFlags flags = RenderFlags.builder()
 *     .annotations(true)
 *     .lcdText(true)
 *     .antiAlias(true)
 *     .build();
 * }</pre>
 */
public record RenderFlags(int value) {

  /** Default flags: annotations rendered, anti-aliasing on, RGBA byte order. */
  public static final RenderFlags DEFAULT = builder().build();

  public static Builder builder() {
    return new Builder();
  }

  public static RenderFlags forProfile(RenderProfile profile) {
    Objects.requireNonNull(profile, "profile");
    return switch (profile) {
      case VIEWER -> builder().annotations(true).lcdText(true).antiAlias(true).build();
      case PREFETCH ->
          builder().annotations(true).lcdText(false).nativeText(false).antiAlias(true).build();
      case THUMBNAIL ->
          builder()
              .annotations(false)
              .lcdText(false)
              .antiAlias(false)
              .limitedImageCache(true)
              .build();
      case PRINT ->
          builder().annotations(true).printing(true).forceHalftone(true).antiAlias(true).build();
      case ARCHIVE ->
          builder()
              .annotations(true)
              .printing(true)
              .nativeText(false)
              .grayscale(true)
              .antiAlias(true)
              .build();
    };
  }

  public static final class Builder {
    private boolean annotations = true;
    private boolean antiAlias = true;
    private boolean lcdText = false;
    private boolean nativeText = true;
    private boolean printing = false;
    private boolean grayscale = false;
    private boolean forceHalftone = false;
    private boolean limitedImageCache = false;

    private Builder() {}

    public Builder annotations(boolean v) {
      this.annotations = v;
      return this;
    }

    public Builder antiAlias(boolean v) {
      this.antiAlias = v;
      return this;
    }

    public Builder lcdText(boolean v) {
      this.lcdText = v;
      return this;
    }

    public Builder nativeText(boolean v) {
      this.nativeText = v;
      return this;
    }

    public Builder printing(boolean v) {
      this.printing = v;
      return this;
    }

    public Builder grayscale(boolean v) {
      this.grayscale = v;
      return this;
    }

    public Builder forceHalftone(boolean v) {
      this.forceHalftone = v;
      return this;
    }

    public Builder limitedImageCache(boolean v) {
      this.limitedImageCache = v;
      return this;
    }

    public RenderFlags build() {
      int flags = ViewBindings.FPDF_REVERSE_BYTE_ORDER; // always RGBA for Java
      if (annotations) flags |= ViewBindings.FPDF_ANNOT;
      if (lcdText) flags |= ViewBindings.FPDF_LCD_TEXT;
      if (!nativeText) flags |= ViewBindings.FPDF_NO_NATIVETEXT;
      if (printing) flags |= ViewBindings.FPDF_PRINTING;
      if (grayscale) flags |= ViewBindings.FPDF_GRAYSCALE;
      if (forceHalftone) flags |= ViewBindings.FPDF_RENDER_FORCEHALFTONE;
      if (limitedImageCache) flags |= ViewBindings.FPDF_RENDER_LIMITEDIMAGECACHE;
      if (!antiAlias) {
        flags |= ViewBindings.FPDF_RENDER_NO_SMOOTHTEXT;
        flags |= ViewBindings.FPDF_RENDER_NO_SMOOTHIMAGE;
        flags |= ViewBindings.FPDF_RENDER_NO_SMOOTHPATH;
      }
      return new RenderFlags(flags);
    }
  }
}
