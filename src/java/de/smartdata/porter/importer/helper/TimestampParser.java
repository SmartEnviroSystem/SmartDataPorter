package de.smartdata.porter.importer.helper;

import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Florian
 */
public final class TimestampParser {

    // Optionaler Cache – kann auch entfernt werden
    private static final Map<String, OffsetDateTime> CACHE = new ConcurrentHashMap<>();

    // Dein PATTERN_M aus dem Importer
    private static final Pattern PATTERN_M
            = Pattern.compile("^m(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{3})$");

    private static ZoneId filenameZone = ZoneId.of("UTC");

    public static void setFilenameZone(String tzId) {
        filenameZone = ZoneId.of(tzId);
    }

    private TimestampParser() {
        // Utility class
    }

    public static OffsetDateTime parse(Path file) {
        String filename = file.getFileName().toString();
        // ---------- CACHE CHECK ----------
        OffsetDateTime cached = CACHE.get(filename);
        if (cached != null) {
            return cached;
        }

        String base = filename;

        // Extension entfernen
        int dot = base.indexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        // Erstes Segment vor "_"
        int idx = base.indexOf('_');
        String first = (idx != -1) ? base.substring(0, idx) : base;

        // -----------------------------------------
        // NORMALISIERUNG
        // -----------------------------------------
        // Unicode-Minus → ASCII "-"
        first = first.replaceAll("[\u2010\u2011\u2012\u2013\u2014]", "-");

        // P → + (Offset)
        first = first.replace("P", "+");

        // Kleinbuchstaben für t
        first = first.toLowerCase();

        OffsetDateTime result = null;

        // -----------------------------------------
        // PATTERN 1: yyyy-MM-ddtHH-mm-ss+HH-mm
        // -----------------------------------------
        if (first.matches("\\d{4}-\\d{2}-\\d{2}t\\d{2}-\\d{2}-\\d{2}[+-]\\d{2}-\\d{2}")) {
            String normalized = first
                    .replace('t', 'T')
                    .replaceFirst("T(\\d{2})-(\\d{2})-(\\d{2})", "T$1:$2:$3")
                    .replaceFirst("([+-]\\d{2})-(\\d{2})$", "$1:$2");

            result = OffsetDateTime.parse(normalized);
        } // -----------------------------------------
        // PATTERN 2: yyyy-MM-ddtHH-mm-ss + Offset im zweiten Segment
        // -----------------------------------------
        else if (first.matches("\\d{4}-\\d{2}-\\d{2}t\\d{2}-\\d{2}-\\d{2}")) {
            if (idx != -1) {
                String off = base.substring(idx + 1);
                if (off.matches("\\d{2}-\\d{2}")) {

                    String normalized = first
                            .replace('t', 'T')
                            .replaceFirst("T(\\d{2})-(\\d{2})-(\\d{2})", "T$1:$2:$3")
                            + "+" + off.replace("-", ":");

                    result = OffsetDateTime.parse(normalized);
                }
            }
        } // -----------------------------------------
        // PATTERN 3: yyyyMMddHHmmss (14 digits)
        // -----------------------------------------
        else if (first.matches("\\d{14}")) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            LocalDateTime ldt = LocalDateTime.parse(first, f);
            ZonedDateTime zdt = ldt.atZone(filenameZone);
            result = zdt.toOffsetDateTime();

        } // -----------------------------------------
        // PATTERN 4: m260511000729151
        // -----------------------------------------
        else {
            Matcher m = PATTERN_M.matcher(first);
            if (m.find()) {
                int year = 2000 + Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                int hour = Integer.parseInt(m.group(4));
                int minute = Integer.parseInt(m.group(5));
                int second = Integer.parseInt(m.group(6));
                int millis = Integer.parseInt(m.group(7));

                LocalDateTime ldt = LocalDateTime.of(
                        year, month, day, hour, minute, second, millis * 1_000_000
                );
                result = ldt.atZone(filenameZone).toOffsetDateTime();
            }
        }

        // -----------------------------------------
        // PATTERN 5: TotSMS1.12_03_2014-15-00.xml
        // -----------------------------------------
        if (result == null) {
            try {
                int firstDot = filename.indexOf(".");
                int firstUnder = filename.indexOf("_");

                if (firstDot < filename.length() - 4 && firstUnder > firstDot) {
                    String ds = filename.substring(firstDot + 1, filename.length() - 4);
                    int year = Integer.parseInt(ds.substring(6, 10));
                    int month = Integer.parseInt(ds.substring(0, 2));
                    int day = Integer.parseInt(ds.substring(3, 5));
                    int hour = Integer.parseInt(ds.substring(11, 13));
                    int minute = Integer.parseInt(ds.substring(14, 16));

                    LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute);
                    result = ldt.atZone(filenameZone).toOffsetDateTime();
                }
            } catch (Exception ignored) {
            }
        }

        // -----------------------------------------
        // PATTERN 6: TotSMS1.2016-01-13-09-00-01.xml
        // -----------------------------------------
        if (result == null) {
            try {
                int firstDot = filename.indexOf(".");
                int firstUnder = filename.indexOf("_");

                if (firstDot < filename.length() - 4 && firstUnder == -1) {
                    String ds = filename.substring(firstDot + 1, filename.length() - 4);
                    int year = Integer.parseInt(ds.substring(0, 4));
                    int month = Integer.parseInt(ds.substring(5, 7));
                    int day = Integer.parseInt(ds.substring(8, 10));
                    int hour = Integer.parseInt(ds.substring(11, 13));
                    int minute = Integer.parseInt(ds.substring(14, 16));
                    int second = Integer.parseInt(ds.substring(17, 19));

                    LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
                    result = ldt.atZone(filenameZone).toOffsetDateTime();
                }
            } catch (Exception ignored) {
            }
        }

        // -----------------------------------------
        // PATTERN 7: 20120619030535.xml
        // -----------------------------------------
        if (result == null) {
            try {
                String ds = filename.substring(0, 14);
                int year = Integer.parseInt(ds.substring(0, 4));
                int month = Integer.parseInt(ds.substring(4, 6));
                int day = Integer.parseInt(ds.substring(6, 8));
                int hour = Integer.parseInt(ds.substring(8, 10));
                int minute = Integer.parseInt(ds.substring(10, 12));
                int second = Integer.parseInt(ds.substring(12, 14));

                LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
                result = ldt.atZone(filenameZone).toOffsetDateTime();
            } catch (Exception ignored) {
            }
        }

        // -----------------------------------------
        // PATTERN 2b: yyyyMMddHHmmss + Anhang
        // -----------------------------------------
        if (result == null && base.matches("^\\d{14}.*")) {
            String ds = base.substring(0, 14);
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            LocalDateTime ldt = LocalDateTime.parse(ds, f);
            result = ldt.atZone(filenameZone).toOffsetDateTime();
        }

        // ---------- CACHE STORE ----------
        if (result != null) {
            CACHE.put(filename, result);
        }
        return result;
    }
}
