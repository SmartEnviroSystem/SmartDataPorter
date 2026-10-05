package de.smartdata.porter.values.converter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlDateFormatter {

    private static final Pattern DATE_PATTERN
            = Pattern.compile("\\{(yyyy|MM|dd)([+-]\\d+)?}");

    private static class DateToken {

        String field;
        int offset;
        int start;
        int end;
    }

    public static String formatUrlWithDate(String urlTemplate, LocalDate baseDate) {
        if (urlTemplate == null) {
            return null;
        }

        Matcher m = DATE_PATTERN.matcher(urlTemplate);
        List<DateToken> tokens = new ArrayList<>();

        while (m.find()) {
            DateToken t = new DateToken();
            t.field = m.group(1);
            String offsetStr = m.group(2);
            t.offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;
            t.start = m.start();
            t.end = m.end();
            tokens.add(t);
        }

        StringBuilder result = new StringBuilder();
        int pos = 0;

        int i = 0;
        while (i < tokens.size()) {
            // neuen Cluster starten
            int clusterStart = tokens.get(i).start;
            int clusterEnd = tokens.get(i).end;
            Map<String, Integer> offsets = new HashMap<>();
            Set<String> fields = new HashSet<>();

            offsets.put(tokens.get(i).field, tokens.get(i).offset);
            fields.add(tokens.get(i).field);

            int j = i + 1;
            while (j < tokens.size()) {
                DateToken prev = tokens.get(j - 1);
                DateToken cur = tokens.get(j);

                boolean newCluster = false;

                // Regel 1: Abstand > 1 Zeichen → neuer Cluster
                if (cur.start - prev.end > 1) {
                    newCluster = true;
                }

                // Regel 2: Feld schon im Cluster → neuer Cluster
                if (fields.contains(cur.field)) {
                    newCluster = true;
                }

                if (newCluster) {
                    break;
                }

                // gehört zum selben Cluster
                offsets.merge(cur.field, cur.offset, Integer::sum);
                fields.add(cur.field);
                clusterEnd = cur.end;
                j++;
            }

            // Literal vor dem Cluster anhängen
            result.append(urlTemplate, pos, clusterStart);

            // gemeinsames Datum berechnen
            int yOff = offsets.getOrDefault("yyyy", 0);
            int mOff = offsets.getOrDefault("MM", 0);
            int dOff = offsets.getOrDefault("dd", 0);

            LocalDate adjusted = baseDate
                    .plusYears(yOff)
                    .plusMonths(mOff)
                    .plusDays(dOff);

            // innerhalb des Clusters alle Tokens ersetzen
            int kPos = clusterStart;
            for (int k = i; k < j; k++) {
                DateToken t = tokens.get(k);
                // Literal zwischen Tokens
                result.append(urlTemplate, kPos, t.start);

                String formatted = DateTimeFormatter.ofPattern(t.field).format(adjusted);

                result.append(formatted);
                kPos = t.end;
            }

            pos = clusterEnd;
            i = j;
        }

        // Rest anhängen
        result.append(urlTemplate, pos, urlTemplate.length());
        return result.toString();
    }

}
