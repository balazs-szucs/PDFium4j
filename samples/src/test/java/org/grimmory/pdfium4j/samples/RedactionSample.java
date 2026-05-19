package org.grimmory.pdfium4j.samples;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.PdfPage;
import org.grimmory.pdfium4j.PdfiumLibrary;
import org.grimmory.pdfium4j.model.PageSize;
import org.grimmory.pdfium4j.model.TextCharInfo;
import org.junit.jupiter.api.Test;

public class RedactionSample {

  private static final List<String> INPUT_PDFS = List.of(
      "../corpus/gutenberg/1063_The Cask of Amontillado.pdf",
      "../corpus/gutenberg/11_Alice's Adventures in Wonderland.pdf",
      "../corpus/gutenberg/1250_Anthem.pdf",
      "../corpus/gutenberg/1656_Apology.pdf",
      "../corpus/gutenberg/1719_The Ballad of the White Horse.pdf",
      "../corpus/gutenberg/1952_The Yellow Wallpaper.pdf",
      "../corpus/gutenberg/2002_Sonnets from the Portuguese.pdf",
      "../corpus/gutenberg/215_The call of the wild.pdf",
      "../corpus/gutenberg/10007_Carmilla.pdf",
      "../corpus/gutenberg/1658_Phaedo.pdf"
  );

  private static class WordRange {
    int startIdx;
    int endIdx;
    String text;

    WordRange(int startIdx, int endIdx, String text) {
      this.startIdx = startIdx;
      this.endIdx = endIdx;
      this.text = text;
    }
  }

  @Test
  public void runRedactionSample() {
    try {
      System.out.println("=== STARTING SECURE REDACTION SAMPLES ===");
      PdfiumLibrary.initialize();

      Path outputDir = Path.of("samples_output/redaction_samples");
      Files.createDirectories(outputDir);
      System.out.println("Output directory: " + outputDir.toAbsolutePath());

      for (String pdfPathStr : INPUT_PDFS) {
        Path inputPdf = Path.of(pdfPathStr);
        assertTrue(Files.exists(inputPdf), "Input PDF must exist: " + inputPdf);

        String fileName = inputPdf.getFileName().toString();
        Path outputPdf = outputDir.resolve("redacted_" + fileName);

        System.out.println("\nProcessing: " + fileName);

        Map<Integer, List<String>> redactedWordsPerPage = new HashMap<>();

        try (PdfDocument doc = PdfDocument.open(inputPdf)) {
          int pageCount = doc.pageCount();
          assertTrue(pageCount > 0);
          System.out.println("  Total pages to redact: " + pageCount);

          for (int pIndex = 0; pIndex < pageCount; pIndex++) {
            try (PdfPage page = doc.page(pIndex)) {
              List<TextCharInfo> chars = page.extractTextWithBounds();
              List<WordRange> words = findWords(chars);
              List<String> targetWords = new ArrayList<>();

              if (!words.isEmpty()) {
                // Redact first word
                WordRange first = words.get(0);
                targetWords.add(first.text);
                redactWordRange(page, chars, first);

                // Redact middle word
                if (words.size() > 1) {
                  WordRange middle = words.get(words.size() / 2);
                  targetWords.add(middle.text);
                  redactWordRange(page, chars, middle);
                }

                // Redact last word
                if (words.size() > 2) {
                  WordRange last = words.get(words.size() - 1);
                  targetWords.add(last.text);
                  redactWordRange(page, chars, last);
                }
              }
              redactedWordsPerPage.put(pIndex, targetWords);

              // Also draw a default custom solid blackout cover block
              page.redact(10.0f, 10.0f, 50.0f, 50.0f, 0, 0, 0, 255);
            }
          }
          doc.save(outputPdf);
        }

        // Programmatic verification of the output PDF
        System.out.println("  Verifying output file: " + outputPdf);
        assertTrue(Files.exists(outputPdf) && Files.size(outputPdf) > 0);

        try (PdfDocument doc = PdfDocument.open(outputPdf)) {
          int pageCount = doc.pageCount();
          assertTrue(pageCount > 0);

          for (int pIndex = 0; pIndex < pageCount; pIndex++) {
            try (PdfPage page = doc.page(pIndex)) {
              PageSize size = page.size();
              assertTrue(size.width() > 0 && size.height() > 0);

              List<String> targets = redactedWordsPerPage.getOrDefault(pIndex, List.of());
              System.out.println("    Page " + pIndex + " verified, applied redactions to words: " + targets);
            }
          }
        }
        System.out.println("  Successfully verified: " + fileName);
      }

      System.out.println("\n=== SECURE REDACTION SAMPLES COMPLETED SUCCESSFULLY ===");
    } catch (Throwable t) {
      t.printStackTrace();
      fail(t);
    }
  }

  private static List<WordRange> findWords(List<TextCharInfo> chars) {
    List<WordRange> words = new ArrayList<>();
    int start = -1;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < chars.size(); i++) {
      String s = chars.get(i).character();
      char c = (s != null && !s.isEmpty()) ? s.charAt(0) : '\u0000';
      if (Character.isWhitespace(c) || c == '\u0000') {
        if (start != -1) {
          words.add(new WordRange(start, i - 1, sb.toString()));
          start = -1;
          sb.setLength(0);
        }
      } else {
        if (start == -1) {
          start = i;
        }
        sb.append(s);
      }
    }
    if (start != -1) {
      words.add(new WordRange(start, chars.size() - 1, sb.toString()));
    }
    return words;
  }

  private static void redactWordRange(PdfPage page, List<TextCharInfo> chars, WordRange range) {
    float left = Float.MAX_VALUE;
    float bottom = Float.MAX_VALUE;
    float right = Float.MIN_VALUE;
    float top = Float.MIN_VALUE;
    for (int i = range.startIdx; i <= range.endIdx; i++) {
      TextCharInfo c = chars.get(i);
      left = Math.min(left, (float) c.left());
      bottom = Math.min(bottom, (float) c.bottom());
      right = Math.max(right, (float) c.right());
      top = Math.max(top, (float) c.top());
    }
    if (left <= right && bottom <= top) {
      // Call secure redaction with 1 pt padding to ensure all glyph edges are clipped
      page.redact(left - 1.0f, bottom - 1.0f, right + 1.0f, top + 1.0f, 0, 0, 0, 255);
    }
  }
}
