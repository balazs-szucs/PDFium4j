package org.grimmory.pdfium4j.model;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/** Standard PDF document metadata tags from the Info dictionary. */
public enum MetadataTag {
  TITLE("Title"),
  AUTHOR("Author"),
  SUBJECT("Subject"),
  KEYWORDS("Keywords"),
  CREATOR("Creator"),
  PRODUCER("Producer"),
  CREATION_DATE("CreationDate"),
  MOD_DATE("ModDate"),
  LANGUAGE("Language");

  private final String pdfKey;
  private final byte[] pdfKeyBytes;
  private final MemorySegment keySegment;

  MetadataTag(String pdfKey) {
    this.pdfKey = pdfKey;
    this.pdfKeyBytes = pdfKey.getBytes(StandardCharsets.ISO_8859_1);
    byte[] utf8Bytes = pdfKey.getBytes(StandardCharsets.UTF_8);
    Arena auto = Arena.ofAuto();
    MemorySegment seg = auto.allocate(utf8Bytes.length + 1, 1);
    MemorySegment.copy(MemorySegment.ofArray(utf8Bytes), 0, seg, 0, utf8Bytes.length);
    seg.set(ValueLayout.JAVA_BYTE, utf8Bytes.length, (byte) 0);
    this.keySegment = seg;
  }

  /** Returns the statically pre-allocated null-terminated UTF-8 key segment. */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP")
  public MemorySegment keySegment() {
    return keySegment;
  }

  /** The PDF metadata key string (e.g. "Title", "Author"). */
  public String pdfKey() {
    return pdfKey;
  }

  /**
   * The pre-encoded PDF metadata key bytes.
   *
   * @return the internal byte array (MUST NOT BE MODIFIED)
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("EI_EXPOSE_REP")
  public byte[] pdfKeyBytes() {
    return pdfKeyBytes;
  }

  public static MetadataTag fromPdfKey(String key) {
    for (MetadataTag tag : values()) {
      if (tag.pdfKey.equalsIgnoreCase(key)) {
        return tag;
      }
    }
    return null;
  }
}
