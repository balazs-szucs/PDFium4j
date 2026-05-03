package org.grimmory.pdfium4j.util;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for parsing and formatting PDF date strings.
 *
 * <p>PDF dates follow the format {@code D:YYYYMMDDHHmmSSOHH'mm'}, where:
 *
 * <ul>
 *   <li>{@code YYYY} is the year.
 *   <li>{@code MM} is the month (01-12).
 *   <li>{@code DD} is the day (01-31).
 *   <li>{@code HH} is the hour (00-23).
 *   <li>{@code mm} is the minute (00-59).
 *   <li>{@code SS} is the second (00-59).
 *   <li>{@code O} is the relationship of local time to UT (Universal Time): '+', '-', or 'Z'.
 *   <li>{@code HH'} is the absolute value of the offset from UT in hours.
 *   <li>{@code mm'} is the absolute value of the offset from UT in minutes.
 * </ul>
 *
 * <p>All fields after the year are optional. The default for month and day is 01, and for other
 * fields is 00. If no timezone offset is specified, UT is assumed.
 */
public final class PdfDateUtils {

  private PdfDateUtils() {}

  private static final String YEAR_GRP = "(?<year>\\d{4})";
  private static final String MONTH_GRP = "(?<month>\\d{2})?";
  private static final String DAY_GRP = "(?<day>\\d{2})?";
  private static final String HOUR_GRP = "(?<hour>\\d{2})?";
  private static final String MINUTE_GRP = "(?<minute>\\d{2})?";
  private static final String SECOND_GRP = "(?<second>\\d{2})?";
  private static final String OFFSET_GRP = "(?<offset>[+\\-Z])?";
  private static final String OFF_HOUR_GRP = "(?<offsetHour>\\d{2})?";
  private static final String OFF_MIN_GRP = "(?<offsetMinute>\\d{2})?";

  private static final Pattern PDF_DATE_PATTERN =
      Pattern.compile(
          "^D:"
              + YEAR_GRP
              + MONTH_GRP
              + DAY_GRP
              + HOUR_GRP
              + MINUTE_GRP
              + SECOND_GRP
              + OFFSET_GRP
              + OFF_HOUR_GRP
              + "'?"
              + OFF_MIN_GRP
              + "'?$");

  private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

  /**
   * Parse a PDF date string into an {@link OffsetDateTime}.
   *
   * @param pdfDate the PDF date string (e.g. "D:20201231235959+05'30'" or "D:20201231235959Z")
   * @return the parsed date, or empty if the format is invalid
   */
  public static Optional<OffsetDateTime> parse(String pdfDate) {
    if (pdfDate == null || pdfDate.isBlank()) {
      return Optional.empty();
    }

    Matcher m = PDF_DATE_PATTERN.matcher(pdfDate);
    if (!m.matches()) {
      return tryParseIsoFallback(pdfDate);
    }

    try {
      int year = Integer.parseInt(m.group("year"));
      int month = getGroupAsInt(m, "month", 1);
      int day = getGroupAsInt(m, "day", 1);
      int hour = getGroupAsInt(m, "hour", 0);
      int minute = getGroupAsInt(m, "minute", 0);
      int second = getGroupAsInt(m, "second", 0);

      ZoneOffset offset = parseZoneOffset(m);
      if (offset == null) {
        return Optional.empty();
      }

      return Optional.of(OffsetDateTime.of(year, month, day, hour, minute, second, 0, offset));
    } catch (Exception _) {
      return Optional.empty();
    }
  }

  private static Optional<OffsetDateTime> tryParseIsoFallback(String dateStr) {
    if (ISO_DATE_PATTERN.matcher(dateStr).matches()) {
      try {
        return Optional.of(LocalDate.parse(dateStr).atStartOfDay().atOffset(ZoneOffset.UTC));
      } catch (java.time.format.DateTimeParseException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static int getGroupAsInt(Matcher m, String groupName, int defaultValue) {
    String val = m.group(groupName);
    return val != null ? Integer.parseInt(val) : defaultValue;
  }

  private static ZoneOffset parseZoneOffset(Matcher m) {
    String offsetSign = m.group("offset");
    String offsetHourRaw = m.group("offsetHour");
    String offsetMinuteRaw = m.group("offsetMinute");

    if (offsetSign == null || "Z".equalsIgnoreCase(offsetSign)) {
      if (offsetHourRaw != null || offsetMinuteRaw != null) {
        return null;
      }
      return ZoneOffset.UTC;
    }

    if (offsetHourRaw == null) {
      return null;
    }

    try {
      int offsetHour = Integer.parseInt(offsetHourRaw);
      int offsetMinute = offsetMinuteRaw != null ? Integer.parseInt(offsetMinuteRaw) : 0;
      int totalOffsetMinutes = offsetHour * 60 + offsetMinute;
      if ("-".equals(offsetSign)) {
        totalOffsetMinutes = -totalOffsetMinutes;
      }
      return ZoneOffset.ofTotalSeconds(totalOffsetMinutes * 60);
    } catch (Exception _) {
      return null;
    }
  }

  /**
   * Format an {@link OffsetDateTime} into a PDF date string.
   *
   * @param dateTime the date and time to format
   * @return the PDF date string (e.g. "D:20201231235959Z")
   */
  public static String format(OffsetDateTime dateTime) {
    StringBuilder sb = new StringBuilder(24);
    sb.append("D:");
    int year = dateTime.getYear();
    if (year < 0 || year > 9999) {
      throw new IllegalArgumentException("PDF dates only support 4-digit years");
    }
    appendPadded(sb, year, 4);
    appendPadded(sb, dateTime.getMonthValue(), 2);
    appendPadded(sb, dateTime.getDayOfMonth(), 2);
    appendPadded(sb, dateTime.getHour(), 2);
    appendPadded(sb, dateTime.getMinute(), 2);
    appendPadded(sb, dateTime.getSecond(), 2);

    ZoneOffset offset = dateTime.getOffset();
    int totalSeconds = offset.getTotalSeconds();
    if (totalSeconds % 60 != 0) {
      throw new IllegalArgumentException("PDF dates only support minute-precision offsets");
    }

    if (totalSeconds == 0) {
      sb.append("Z");
    } else {
      appendOffset(sb, totalSeconds);
    }
    return sb.toString();
  }

  private static void appendOffset(StringBuilder sb, int totalSeconds) {
    int absSeconds = Math.abs(totalSeconds);
    int hours = absSeconds / 3600;
    int minutes = (absSeconds % 3600) / 60;
    sb.append(totalSeconds >= 0 ? "+" : "-");
    appendPadded(sb, hours, 2);
    sb.append("'");
    appendPadded(sb, minutes, 2);
    sb.append("'");
  }

  private static void appendPadded(StringBuilder sb, int value, int width) {
    long v = Math.abs((long) value);
    if (width == 2) {
      sb.append((char) ('0' + (v / 10 % 10)));
      sb.append((char) ('0' + (v % 10)));
    } else if (width == 4) {
      sb.append((char) ('0' + (v / 1000 % 10)));
      sb.append((char) ('0' + (v / 100 % 10)));
      sb.append((char) ('0' + (v / 10 % 10)));
      sb.append((char) ('0' + (v % 10)));
    } else {
      int digits = 1;
      long div = 1;
      while (v >= div * 10) {
        div *= 10;
        digits++;
      }
      int pad = width - digits;
      for (int i = 0; i < pad; i++) {
        sb.append('0');
      }
      while (div > 0) {
        sb.append((char) ('0' + ((v / div) % 10)));
        div /= 10;
      }
    }
  }
}
