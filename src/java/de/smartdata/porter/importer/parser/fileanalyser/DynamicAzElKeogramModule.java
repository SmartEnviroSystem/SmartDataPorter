package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.Year;
import java.time.ZoneId;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 *
 * @author Florian
 */
public class DynamicAzElKeogramModule implements MetadataExtractorModule {

    private static final String TMP_SUFFIX = "_keogram_azel.tmp";

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mime) {
        return mime != null && mime.startsWith("image/");
    }

    @Override
    public synchronized void extractMetadata(SourceDescriptor desc, JsonObjectBuilder json, Importer importer) throws Exception {

        BufferedImage img = desc.getSharedImage();
        if (img == null) {
            return;
        }

        final int width = img.getWidth();
        final int height = img.getHeight();
        final int[] pixels = desc.getPixels();
        if (pixels == null) {
            return;
        }

        JsonObject cfg = importer.getConfig();

        String[] required = {
            "dynkeogram.fisheye.cx",
            "dynkeogram.fisheye.cy",
            "dynkeogram.fisheye.k1",
            "dynkeogram.fisheye.k2",
            "dynkeogram.fisheye.k3",
            "dynkeogram.camera.roll",
            "dynkeogram.camera.pitch",
            "dynkeogram.camera.yaw",
            "dynkeogram.location.lat",
            "dynkeogram.location.lon"
        };

        for (String key : required) {
            if (!cfg.containsKey(key) || cfg.isNull(key)) {
                return;
            }
        }

        double cx = cfg.getJsonNumber("dynkeogram.fisheye.cx").doubleValue();
        double cy = cfg.getJsonNumber("dynkeogram.fisheye.cy").doubleValue();

        double k1 = cfg.getJsonNumber("dynkeogram.fisheye.k1").doubleValue();
        double k2 = cfg.getJsonNumber("dynkeogram.fisheye.k2").doubleValue();
        double k3 = cfg.getJsonNumber("dynkeogram.fisheye.k3").doubleValue();

        double roll = Math.toRadians(cfg.getJsonNumber("dynkeogram.camera.roll").doubleValue());
        double pitch = Math.toRadians(cfg.getJsonNumber("dynkeogram.camera.pitch").doubleValue());
        double yaw = Math.toRadians(cfg.getJsonNumber("dynkeogram.camera.yaw").doubleValue());

        double lat = cfg.getJsonNumber("dynkeogram.location.lat").doubleValue();
        double lon = cfg.getJsonNumber("dynkeogram.location.lon").doubleValue();

        LocalDateTime time = desc.getFileDate().toLocalDateTime();
        String dateStr = time.toLocalDate().toString().replace("-", "");
        int zeitzone = germanTimezoneOffset(time);
        SunPos sun = computeSunPositionPython(time, lat, lon, zeitzone);

        // Azimut/Elevation des Sonnenstrahls
        double az = Math.toRadians(sun.azimuth);
        // Wir nehmen den Sonnen-Azimut als feste Richtung,
        // und scannen alle Elevationswinkel entlang dieser Richtung.

        // Parameter für Az/El-Keogramm
        int elevBins = 256;          // Anzahl Elevations-Schritte (Keogramm-Höhe)
        double elevMinDeg = 0.0;     // minimaler Elevationswinkel
        double elevMaxDeg = 90.0;    // maximaler Elevationswinkel
        double elevStepDeg = (elevMaxDeg - elevMinDeg) / (elevBins - 1);

        int[] azElColumn = extractAzElColumn(
                pixels, width, height,
                cx, cy, k1, k2, k3,
                yaw, pitch, roll,
                az,
                elevBins, elevMinDeg, elevStepDeg
        );

        // Base64-PNG für diese Az/El-Spalte (Höhe = elevBins, Breite = 1)
        String base64Png = encodeColumnPng(azElColumn, elevBins);
        json.add("dynkeogram_azel_png", base64Png);

        // Keogramm später aus tmpFile bauen (ähnlich wie bisher, aber mit anderer Geometrie)
        // buildKeogramImage(tmpFile, outPng, elevBins);
        json.add("dynkeo_azel_azimuth", sun.azimuth);
        json.add("dynkeo_azel_elevation", sun.elevation);
    }

    private String encodeColumnPng(int[] columnPixels, int height) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage colImg = new BufferedImage(1, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            colImg.setRGB(0, y, columnPixels[y]);
        }
        ImageIO.write(colImg, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private int[] extractAzElColumn(
            int[] pixels, int width, int height,
            double cx, double cy,
            double k1, double k2, double k3,
            double yaw, double pitch, double roll,
            double azimuthRad,
            int elevBins, double elevMinDeg, double elevStepDeg) {

        int[] column = new int[elevBins];

        for (int i = 0; i < elevBins; i++) {
            double elevDeg = elevMinDeg + i * elevStepDeg;
            double elevRad = Math.toRadians(elevDeg);

            // Richtungsvektor im Weltkoordinatensystem (Az/El)
            double cosEl = Math.cos(elevRad);
            double vx = cosEl * Math.sin(azimuthRad);
            double vy = cosEl * Math.cos(azimuthRad);
            double vz = Math.sin(elevRad);

            // In Kamerakoordinaten rotieren
            double[] vCam = rotateZYX(yaw, pitch, roll, vx, vy, vz);

            // Auf Fisheye projizieren
            int[] uv = projectFisheye(vCam, cx, cy, k1, k2, k3);
            int u = clamp(uv[0], 0, width - 1);
            int v = clamp(uv[1], 0, height - 1);

            // Pixel holen
            column[i] = pixels[v * width + u];
        }

        return column;
    }

    private int germanTimezoneOffset(LocalDateTime dateTime) {
        int year = dateTime.getYear();

        LocalDateTime sommerStart = lastSundayOfMonth(year, 3).atTime(2, 0);
        LocalDateTime sommerEnde = lastSundayOfMonth(year, 10).atTime(3, 0);

        return (dateTime.isEqual(sommerStart) || dateTime.isAfter(sommerStart))
                && dateTime.isBefore(sommerEnde) ? 2 : 1;
    }

    private LocalDate lastSundayOfMonth(int year, int month) {
        LocalDate d = LocalDate.of(year, month, Month.of(month).length(Year.isLeap(year)));
        while (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private SunPos computeSunPositionPython(LocalDateTime time, double lat, double lon, int tz) {

        double jd = (time.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli() / 86400000.0)
                + 2440587.5
                - tz / 24.0;

        double n = jd - 2451545.0;

        double g = Math.toRadians((357.529 + 0.98560028 * n) % 360);
        double q = Math.toRadians((280.459 + 0.98564736 * n) % 360);

        double L = q
                + Math.toRadians(1.915) * Math.sin(g)
                + Math.toRadians(0.020) * Math.sin(2 * g);

        double e = Math.toRadians(23.439 - 0.00000036 * n);

        double RA = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L));
        double decl = Math.asin(Math.sin(e) * Math.sin(L));

        double GMST = Math.toRadians(
                (280.46061837 + 360.98564736629 * (jd - 2451545) + lon) % 360
        );

        double H = GMST - RA;

        double latRad = Math.toRadians(lat);

        double elev = Math.asin(
                Math.sin(latRad) * Math.sin(decl)
                + Math.cos(latRad) * Math.cos(decl) * Math.cos(H)
        );

        double azi = Math.atan2(
                -Math.sin(H),
                Math.tan(decl) * Math.cos(latRad) - Math.sin(latRad) * Math.cos(H)
        );

        return new SunPos(
                Math.toDegrees((azi + 2 * Math.PI) % (2 * Math.PI)),
                Math.toDegrees(elev)
        );
    }

    private static double[] rotateZYX(double yaw, double pitch, double roll,
            double x, double y, double z) {

        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double cr = Math.cos(roll), sr = Math.sin(roll);

        double m00 = cy * cp;
        double m01 = cy * sp * sr - sy * cr;
        double m02 = cy * sp * cr + sy * sr;

        double m10 = sy * cp;
        double m11 = sy * sp * sr + cy * cr;
        double m12 = sy * sp * cr - cy * sr;

        double m20 = -sp;
        double m21 = cp * sr;
        double m22 = cp * cr;

        return new double[]{
            m00 * x + m01 * y + m02 * z,
            m10 * x + m11 * y + m12 * z,
            m20 * x + m21 * y + m22 * z
        };
    }

    private static int[] projectFisheye(double[] v, double cx, double cy,
            double k1, double k2, double k3) {

        double phi = Math.atan2(v[1], v[0]);
        double theta = Math.acos(v[2]);

        double t2 = theta * theta;
        double r = k1 * theta + k2 * t2 * theta + k3 * t2 * t2 * theta;

        return new int[]{
            (int) Math.round(cx + r * Math.cos(phi)),
            (int) Math.round(cy + r * Math.sin(phi))
        };
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static class SunPos {

        final double azimuth;
        final double elevation;

        SunPos(double az, double el) {
            this.azimuth = az;
            this.elevation = el;
        }
    }
}
