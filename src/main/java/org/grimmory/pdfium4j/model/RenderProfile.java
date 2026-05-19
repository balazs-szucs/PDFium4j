package org.grimmory.pdfium4j.model;

/**
 * Intent-driven rendering profiles mapped to practical PDFium flag combinations.
 *
 * <p>Profiles are tuned for common workloads and can be passed directly to PdfPage render APIs.
 */
public enum RenderProfile {
  /** User-visible page rendering with high text quality. */
  VIEWER,
  /** Background prefetch path for neighboring pages. */
  PREFETCH,
  /** Low-cost thumbnail path with constrained image cache and no smoothing. */
  THUMBNAIL,
  /** Print-oriented rasterization with halftone enabled. */
  PRINT,
  /** Stable archive-style rasterization, optionally grayscale. */
  ARCHIVE
}
