package de.smartdata.porter.importer.parser.fileanalyser;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.GpsDirectory;
import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Florian
 */
public class ExifMetadataModule implements MetadataExtractorModule {

    // Mapping einmalig cachen
    private volatile Map<String, Map<String, String>> cachedMapping = null;

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mimeType) {
        if (mimeType == null) {
            return false;
        }
        mimeType = mimeType.toLowerCase();

        return mimeType.equals("image/jpeg")
                || mimeType.equals("image/tiff")
                || mimeType.equals("image/heic")
                || mimeType.equals("image/heif")
                || mimeType.equals("image/webp")
                || mimeType.startsWith("image/x-");
    }

    @Override
    public void extractMetadata(SourceDescriptor desc,
            JsonObjectBuilder json,
            Importer importer) throws Exception {

        byte[] bytes = desc.getFileBytes();
        if (bytes == null || bytes.length == 0) {
            return; // nichts zu tun
        }

        // Nur versuchen, wenn MIME-Typ ein Bild ist
        String mime = desc.getMimeType();
        if (mime == null || !mime.startsWith("image/")) {
            return; // kein Bild → kein EXIF
        }

        // Mapping nur einmal erzeugen
        Map<String, Map<String, String>> mapping = cachedMapping;
        if (mapping == null) {
            synchronized (this) {
                if (cachedMapping == null) {
                    cachedMapping = readExifMapping(importer);
                }
                mapping = cachedMapping;
            }
        }

        boolean normalizeGps = importer.getConfig().getBoolean("exif.gps.normalize", false);

        // ---------------------------------------------------------
        // EXIF aus Byte-Array lesen (kein Dateizugriff!)
        // ---------------------------------------------------------
        Metadata metadata;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            metadata = ImageMetadataReader.readMetadata(bis);
        } catch (Exception ex) {
            // Datei ist kein EXIF-Bild oder beschädigt → einfach ignorieren
            return;
        }

        // ---------------------------------------------------------
        // Nur Tags prüfen, die im Mapping vorkommen
        // ---------------------------------------------------------
        for (Directory dir : metadata.getDirectories()) {
            for (Tag tag : dir.getTags()) {

                String tagName = tag.getTagName();
                Map<String, String> subMap = mapping.get(tagName);
                if (subMap == null) {
                    continue;
                }

                String value = tag.getDescription();
                if (value == null) {
                    continue;
                }

                // schneller als contains(";")
                if (value.indexOf(';') >= 0) {
                    splitMappedFast(tagName, value, subMap, json);
                }
            }
        }

        // ---------------------------------------------------------
        // GPS schneller extrahieren
        // ---------------------------------------------------------
        if (normalizeGps) {
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null) {
                GeoLocation loc = gps.getGeoLocation();
                if (loc != null) {
                    json.add("gps_lat", loc.getLatitude());
                    json.add("gps_lon", loc.getLongitude());
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Mapping aus Config
    // ---------------------------------------------------------
    private Map<String, Map<String, String>> readExifMapping(Importer importer) {
        Map<String, Map<String, String>> map = new HashMap<>();

        for (String key : importer.getConfig().keySet()) {
            if (!key.startsWith("exif.")) {
                continue;
            }

            String rest = key.substring(5);
            int idx = rest.indexOf('.');
            if (idx == -1) {
                continue;
            }

            String tag = rest.substring(0, idx);
            String subKey = rest.substring(idx + 1).toLowerCase();

            String targetName = importer.getConfig().getString(key);

            map.computeIfAbsent(tag, k -> new HashMap<>())
                    .put(subKey, targetName);
        }

        return map;
    }

    // ---------------------------------------------------------
    // Schnelleres Splitten ohne regex
    // ---------------------------------------------------------
    private void splitMappedFast(String tagName,
            String value,
            Map<String, String> mapping,
            JsonObjectBuilder json) {

        int start = 0;
        int len = value.length();

        while (start < len) {
            int sep = value.indexOf(';', start);
            if (sep == -1) {
                sep = len;
            }

            int eq = value.indexOf('=', start);
            if (eq > start && eq < sep) {
                String key = value.substring(start, eq).trim().toLowerCase();
                String val = value.substring(eq + 1, sep).trim();

                String mapped = mapping.get(key);
                if (mapped != null) {
                    json.add(mapped, val);
                }
            }

            start = sep + 1;
        }
    }
}
