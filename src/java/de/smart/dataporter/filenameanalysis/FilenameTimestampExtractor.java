
package de.smart.dataporter.filenameanalysis;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Extracts timestamps from filenames
 * 
 * @author Florian
 */
public final class FilenameTimestampExtractor {

    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("'m'yyMMddHHmmssSSS"),
            DateTimeFormatter.BASIC_ISO_DATE // 20200103
    );

    public static Instant extract(String filename) {
        String base = filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        for (DateTimeFormatter fmt : FORMATTERS) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(base, fmt);
                Instant instant = ldt.atZone(ZoneId.systemDefault()).toInstant();
//                System.out.println("[TS] Erfolgreich mit Pattern: " + fmt);
                return instant;
            } catch (Exception e) {
//                System.out.println("[TS] Pattern passt nicht: " + fmt + " für '" + base + "'");
            }
        }

//        System.out.println("[TS] Kein Datum aus Dateiname extrahiert: " + filename);
        return null;
    }
}