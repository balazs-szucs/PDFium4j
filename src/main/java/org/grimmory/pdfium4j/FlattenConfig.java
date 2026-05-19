package org.grimmory.pdfium4j;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

public class FlattenConfig {
  public enum FlattenMode {
    RASTER,
    LOGICAL,
    BOTH
  }

  public enum ImageEncoding {
    JPEG,
    JPEG2000,
    PNG,
    JBIG2,
    FLATE
  }

  private FlattenMode mode = FlattenMode.RASTER;
  private int dpi = 150;
  private float scale = 1.0f;
  private ImageEncoding encoding = ImageEncoding.JPEG;
  private int jpegQuality = 85;
  private int pngCompressionLevel = 6;
  private boolean grayscale = false;
  private boolean alphaMaskEnabled = false;

  private boolean renderAnnotations = true;
  private boolean renderForms = true;
  private boolean renderLCDText = false;
  private boolean noNativeText = false;
  private boolean printingMode = false;
  private boolean reverseByteOrder = false;

  private int pageFrom = 0;
  private int pageTo = -1;

  private boolean linearize = false;
  private boolean compressStreams = true;
  private boolean preserveMetadata = true;

  private long tileSizeBytes = 64L * 1024L * 1024L;
  private boolean tileEnabled = true;

  private boolean flattenAnnotations = true;
  private boolean flattenFormFields = true;
  private boolean flattenForPrint = false;
  private boolean generateAppearances = false;

  private static final StructLayout LAYOUT =
      MemoryLayout.structLayout(
              ValueLayout.JAVA_INT.withName("mode"),
              ValueLayout.JAVA_INT.withName("dpi"),
              ValueLayout.JAVA_FLOAT.withName("scale"),
              ValueLayout.JAVA_INT.withName("encoding"),
              ValueLayout.JAVA_INT.withName("jpegQuality"),
              ValueLayout.JAVA_INT.withName("pngCompressionLevel"),
              ValueLayout.JAVA_INT.withName("grayscale"),
              ValueLayout.JAVA_INT.withName("alphaMaskEnabled"),
              ValueLayout.JAVA_INT.withName("renderAnnotations"),
              ValueLayout.JAVA_INT.withName("renderForms"),
              ValueLayout.JAVA_INT.withName("renderLCDText"),
              ValueLayout.JAVA_INT.withName("noNativeText"),
              ValueLayout.JAVA_INT.withName("printingMode"),
              ValueLayout.JAVA_INT.withName("reverseByteOrder"),
              ValueLayout.JAVA_INT.withName("pageFrom"),
              ValueLayout.JAVA_INT.withName("pageTo"),
              ValueLayout.JAVA_INT.withName("linearize"),
              ValueLayout.JAVA_INT.withName("compressStreams"),
              ValueLayout.JAVA_INT.withName("preserveMetadata"),
              MemoryLayout.paddingLayout(4), // explicit 4-byte pad to 8-align size_t
              ValueLayout.JAVA_LONG.withName("tileSizeBytes"),
              ValueLayout.JAVA_INT.withName("tileEnabled"),
              ValueLayout.JAVA_INT.withName("flattenAnnotations"),
              ValueLayout.JAVA_INT.withName("flattenFormFields"),
              ValueLayout.JAVA_INT.withName("flattenForPrint"),
              ValueLayout.JAVA_INT.withName("generateAppearances"))
          .withName("pdfium4j_flatten_config_t");

  private static final VarHandle VH_MODE =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("mode"));
  private static final VarHandle VH_DPI =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dpi"));
  private static final VarHandle VH_SCALE =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("scale"));
  private static final VarHandle VH_ENCODING =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("encoding"));
  private static final VarHandle VH_JPEG_QUALITY =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("jpegQuality"));
  private static final VarHandle VH_PNG_LEVEL =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("pngCompressionLevel"));
  private static final VarHandle VH_GRAYSCALE =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("grayscale"));
  private static final VarHandle VH_ALPHA =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("alphaMaskEnabled"));
  private static final VarHandle VH_RENDER_ANNOTS =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("renderAnnotations"));
  private static final VarHandle VH_RENDER_FORMS =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("renderForms"));
  private static final VarHandle VH_RENDER_LCD =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("renderLCDText"));
  private static final VarHandle VH_NO_NATIVE =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("noNativeText"));
  private static final VarHandle VH_PRINTING =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("printingMode"));
  private static final VarHandle VH_REVERSE_BYTE =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("reverseByteOrder"));
  private static final VarHandle VH_PAGE_FROM =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("pageFrom"));
  private static final VarHandle VH_PAGE_TO =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("pageTo"));
  private static final VarHandle VH_LINEARIZE =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("linearize"));
  private static final VarHandle VH_COMPRESS =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("compressStreams"));
  private static final VarHandle VH_METADATA =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("preserveMetadata"));
  private static final VarHandle VH_TILE_BYTES =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("tileSizeBytes"));
  private static final VarHandle VH_TILE_ENABLED =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("tileEnabled"));
  private static final VarHandle VH_FLATTEN_ANNOTS =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flattenAnnotations"));
  private static final VarHandle VH_FLATTEN_FORMS =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flattenFormFields"));
  private static final VarHandle VH_FLATTEN_PRINT =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flattenForPrint"));
  private static final VarHandle VH_GEN_APPEARANCES =
      LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("generateAppearances"));

  public FlattenConfig mode(FlattenMode mode) {
    this.mode = mode;
    return this;
  }

  public FlattenConfig dpi(int dpi) {
    this.dpi = dpi;
    return this;
  }

  public FlattenConfig scale(float scale) {
    this.scale = scale;
    return this;
  }

  public FlattenConfig encoding(ImageEncoding encoding) {
    this.encoding = encoding;
    return this;
  }

  public FlattenConfig jpegQuality(int jpegQuality) {
    this.jpegQuality = jpegQuality;
    return this;
  }

  public FlattenConfig pngCompressionLevel(int pngCompressionLevel) {
    this.pngCompressionLevel = pngCompressionLevel;
    return this;
  }

  public FlattenConfig grayscale(boolean grayscale) {
    this.grayscale = grayscale;
    return this;
  }

  public FlattenConfig alphaMaskEnabled(boolean alphaMaskEnabled) {
    this.alphaMaskEnabled = alphaMaskEnabled;
    return this;
  }

  public FlattenConfig renderAnnotations(boolean renderAnnotations) {
    this.renderAnnotations = renderAnnotations;
    return this;
  }

  public FlattenConfig renderForms(boolean renderForms) {
    this.renderForms = renderForms;
    return this;
  }

  public FlattenConfig renderLCDText(boolean renderLCDText) {
    this.renderLCDText = renderLCDText;
    return this;
  }

  public FlattenConfig noNativeText(boolean noNativeText) {
    this.noNativeText = noNativeText;
    return this;
  }

  public FlattenConfig printingMode(boolean printingMode) {
    this.printingMode = printingMode;
    return this;
  }

  public FlattenConfig reverseByteOrder(boolean reverseByteOrder) {
    this.reverseByteOrder = reverseByteOrder;
    return this;
  }

  public FlattenConfig pageFrom(int pageFrom) {
    this.pageFrom = pageFrom;
    return this;
  }

  public FlattenConfig pageTo(int pageTo) {
    this.pageTo = pageTo;
    return this;
  }

  public FlattenConfig linearize(boolean linearize) {
    this.linearize = linearize;
    return this;
  }

  public FlattenConfig compressStreams(boolean compressStreams) {
    this.compressStreams = compressStreams;
    return this;
  }

  public FlattenConfig preserveMetadata(boolean preserveMetadata) {
    this.preserveMetadata = preserveMetadata;
    return this;
  }

  public FlattenConfig tileSizeBytes(long tileSizeBytes) {
    this.tileSizeBytes = tileSizeBytes;
    return this;
  }

  public FlattenConfig tileEnabled(boolean tileEnabled) {
    this.tileEnabled = tileEnabled;
    return this;
  }

  public FlattenConfig flattenAnnotations(boolean flattenAnnotations) {
    this.flattenAnnotations = flattenAnnotations;
    return this;
  }

  public FlattenConfig flattenFormFields(boolean flattenFormFields) {
    this.flattenFormFields = flattenFormFields;
    return this;
  }

  public FlattenConfig flattenForPrint(boolean flattenForPrint) {
    this.flattenForPrint = flattenForPrint;
    return this;
  }

  public FlattenConfig generateAppearances(boolean generateAppearances) {
    this.generateAppearances = generateAppearances;
    return this;
  }

  // FFM layout serialization
  public MemorySegment serialize(Arena arena) {
    MemorySegment segment = arena.allocate(LAYOUT);
    VH_MODE.set(segment, 0L, mode.ordinal());
    VH_DPI.set(segment, 0L, dpi);
    VH_SCALE.set(segment, 0L, scale);
    VH_ENCODING.set(segment, 0L, encoding.ordinal());
    VH_JPEG_QUALITY.set(segment, 0L, jpegQuality);
    VH_PNG_LEVEL.set(segment, 0L, pngCompressionLevel);
    VH_GRAYSCALE.set(segment, 0L, grayscale ? 1 : 0);
    VH_ALPHA.set(segment, 0L, alphaMaskEnabled ? 1 : 0);
    VH_RENDER_ANNOTS.set(segment, 0L, renderAnnotations ? 1 : 0);
    VH_RENDER_FORMS.set(segment, 0L, renderForms ? 1 : 0);
    VH_RENDER_LCD.set(segment, 0L, renderLCDText ? 1 : 0);
    VH_NO_NATIVE.set(segment, 0L, noNativeText ? 1 : 0);
    VH_PRINTING.set(segment, 0L, printingMode ? 1 : 0);
    VH_REVERSE_BYTE.set(segment, 0L, reverseByteOrder ? 1 : 0);
    VH_PAGE_FROM.set(segment, 0L, pageFrom);
    VH_PAGE_TO.set(segment, 0L, pageTo);
    VH_LINEARIZE.set(segment, 0L, linearize ? 1 : 0);
    VH_COMPRESS.set(segment, 0L, compressStreams ? 1 : 0);
    VH_METADATA.set(segment, 0L, preserveMetadata ? 1 : 0);
    VH_TILE_BYTES.set(segment, 0L, tileSizeBytes);
    VH_TILE_ENABLED.set(segment, 0L, tileEnabled ? 1 : 0);
    VH_FLATTEN_ANNOTS.set(segment, 0L, flattenAnnotations ? 1 : 0);
    VH_FLATTEN_FORMS.set(segment, 0L, flattenFormFields ? 1 : 0);
    VH_FLATTEN_PRINT.set(segment, 0L, flattenForPrint ? 1 : 0);
    VH_GEN_APPEARANCES.set(segment, 0L, generateAppearances ? 1 : 0);
    return segment;
  }
}
