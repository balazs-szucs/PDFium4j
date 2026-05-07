package org.grimmory.pdfium4j.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.grimmory.pdfium4j.PdfiumLibrary;

/**
 * PDF implementation of {@link BookMetadata}. Wraps PDF metadata from Info dictionary and XMP
 * packet to provide unified access to standard metadata fields.
 *
 * @param title the book's primary title
 * @param authors list of all authors/creators
 * @param series the series name (from XMP Calibre namespace)
 * @param seriesNumber the volume number within the series
 * @param isbn the ISBN identifier
 * @param language the language code
 * @param publishedDate the publication date
 * @param subjects list of subjects/genres
 * @param description the book description/abstract
 * @param publisher the publisher name
 * @param rawMetadata the underlying XmpMetadata object
 * @param customFields additional custom metadata fields
 */
public record PdfBookMetadata(
    Optional<String> title,
    List<String> authors,
    Optional<String> series,
    Optional<Float> seriesNumber,
    Optional<String> isbn,
    Optional<String> language,
    Optional<LocalDate> publishedDate,
    List<String> subjects,
    Optional<String> description,
    Optional<String> publisher,
    XmpMetadata rawMetadata,
    Map<String, String> customFields)
    implements BookMetadata {

  private static final Pattern UNIFORM_DIGIT_PATTERN = Pattern.compile("^(\\d)\\1{9,12}$");
  private static final Pattern NON_ISBN_CHARS_PATTERN = Pattern.compile("[^0-9Xx]");

  public PdfBookMetadata {
    authors = Collections.unmodifiableList(authors);
    subjects = Collections.unmodifiableList(subjects);
    customFields = Collections.unmodifiableMap(customFields);
  }

  private static final Pattern PDF_DATE_PATTERN =
      Pattern.compile("^D:(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?");
  private static final Pattern FOUR_DIGIT_YEAR_PATTERN = Pattern.compile("\\b(\\d{4})\\b");

  private static final List<DateTimeFormatter> DATE_FORMATS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE, // 2024-01-15
          DateTimeFormatter.ofPattern("yyyy/MM/dd"), // 2024/01/15
          DateTimeFormatter.ofPattern("yyyy.MM.dd"), // 2024.01.15
          DateTimeFormatter.ofPattern("dd/MM/yyyy"), // 15/01/2024
          DateTimeFormatter.ofPattern("dd-MM-yyyy"), // 15-01-2024
          DateTimeFormatter.ofPattern("MM/dd/yyyy"), // 01/15/2024
          DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US), // Jan 15, 2024
          DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US), // January 15, 2024
          DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US), // 15 Jan 2024
          DateTimeFormatter.ofPattern("yyyy") // 2024 (year only)
          );

  private static Optional<LocalDate> parseDate(String dateStr) {
    if (dateStr == null || dateStr.isEmpty()) return Optional.empty();

    // Try PDF date format: D:YYYYMMDDHHmmSS+TZ
    Matcher pdfMatcher = PDF_DATE_PATTERN.matcher(dateStr);
    if (pdfMatcher.find()) {
      int year = Integer.parseInt(pdfMatcher.group(1));
      int month = pdfMatcher.group(2) != null ? Integer.parseInt(pdfMatcher.group(2)) : 1;
      int day = pdfMatcher.group(3) != null ? Integer.parseInt(pdfMatcher.group(3)) : 1;
      if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
        try {
          return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
          PdfiumLibrary.ignore(e);
        }
      }
    }

    // Strip ISO 8601 time/timezone suffix for date-only parsing
    String dateOnly = dateStr.contains("T") ? dateStr.substring(0, dateStr.indexOf('T')) : dateStr;

    for (DateTimeFormatter fmt : DATE_FORMATS) {
      try {
        return Optional.of(LocalDate.parse(dateOnly, fmt));
      } catch (DateTimeParseException e) {
        PdfiumLibrary.ignore(e);
      }
    }

    // Last resort: extract 4-digit year
    Matcher yearMatcher = FOUR_DIGIT_YEAR_PATTERN.matcher(dateStr);
    if (yearMatcher.find()) {
      int year = Integer.parseInt(yearMatcher.group(1));
      if (year >= 1000 && year <= 9999) {
        return Optional.of(LocalDate.of(year, 1, 1));
      }
    }

    return Optional.empty();
  }

  @CheckForNull
  private static String cleanIsbn(String id) {
    if (id == null) return null;
    String cleaned = NON_ISBN_CHARS_PATTERN.matcher(id).replaceAll("").toUpperCase();

    // Reject uniform sequences like 0000000000 or 1111111111111 (fake ISBNs)
    if (UNIFORM_DIGIT_PATTERN.matcher(cleaned).matches()) return null;

    if (cleaned.length() == 10 && isValidIsbn10(cleaned)) return cleaned;
    if (cleaned.length() == 13 && isValidIsbn13(cleaned)) return cleaned;
    return null;
  }

  private static boolean isValidIsbn10(String isbn) {
    int sum = 0;
    for (int i = 0; i < 10; i++) {
      char c = isbn.charAt(i);
      int digit = (c == 'X') ? 10 : (c - '0');
      if (digit < 0 || (digit > 9 && i < 9)) return false;
      sum += digit * (10 - i);
    }
    return sum % 11 == 0;
  }

  private static boolean isValidIsbn13(String isbn) {
    int sum = 0;
    for (int i = 0; i < 13; i++) {
      int digit = isbn.charAt(i) - '0';
      if (digit < 0 || digit > 9) return false;
      sum += digit * ((i % 2 == 0) ? 1 : 3);
    }
    return sum % 10 == 0;
  }
}
