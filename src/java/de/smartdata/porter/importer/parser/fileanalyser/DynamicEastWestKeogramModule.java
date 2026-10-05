package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
public class DynamicEastWestKeogramModule implements MetadataExtractorModule {

    private static final String TMP_SUFFIX = "_keogram_es.tmp";

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

        // Check on missing configuration
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

        double az = Math.toRadians(sun.azimuth);
        double el = Math.toRadians(sun.elevation);

        double cosEl = Math.cos(el);
        double vSunX = cosEl * Math.sin(az);
        double vSunY = cosEl * Math.cos(az);
        double vSunZ = Math.sin(el);

        double[] vCam = rotateZYX(yaw, pitch, roll, vSunX, vSunY, vSunZ);

        int[] uv = projectFisheye(vCam, cx, cy, k1, k2, k3);
        int vSunPix = clamp(uv[1], 0, height - 1);

        // Zeile extrahieren
        int[] rowPixels = extractInterpolatedRow(pixels, width, height, cx, cy, vSunPix);

        // 🔹 Base64-PNG für die aktuelle Zeile
        String base64Png = encodeRowPng(rowPixels, width);
        json.add("dynkeogram_ew_png", base64Png);

        // Zeile in .tmp speichern
        Path srcPath;
        Path dir;
        try {
            String p = desc.getPath();
            if (p == null) {
                throw new Exception();
            }
            srcPath = Paths.get(p);
            dir = srcPath.getParent() != null ? srcPath.getParent() : Paths.get(".");
        } catch (Exception ex) {
            dir = Paths.get(".");
        }

        Path tmpFile = dir.resolve(dateStr + TMP_SUFFIX);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
            for (int rgb : rowPixels) {
                out.writeInt(rgb);
            }
        }

        // Keogramm neu erzeugen (transpose)
//        new Thread(() -> {
//            try {
//                buildKeogramImage(tmpFile, outPng, width);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }).start();
//        json.add("dynkeogram_ew_file", outPng.toString());
        json.add("dynkeo_ew_azimuth", sun.azimuth);
        json.add("dynkeo_ew_elevation", sun.elevation);
        json.add("dynkeo_ew_pixel_v", vSunPix);
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

    private int[] extractInterpolatedRow(int[] pixels, int width, int height, double cx, double cy, int vSunPix) {

        int[] row = new int[width];

        double maxRadius = height * 0.5;
        double maxRadiusSq = maxRadius * maxRadius;

        for (int x = 0; x < width; x++) {

//            double dx = x - cx;
//            double dy = vSunPix - cy;
//            if (dx * dx + dy * dy > maxRadiusSq) {
//                row[x] = 0;
//                continue;
//            }
            int x0 = clamp((int) Math.floor(x), 0, width - 1);
            int x1 = clamp(x0 + 1, 0, width - 1);

            int y0 = clamp((int) Math.floor(vSunPix), 0, height - 1);
            int y1 = clamp(y0 + 1, 0, height - 1);

            double tx = x - x0;
            double ty = vSunPix - y0;

            int rgb00 = pixels[y0 * width + x0];
            int rgb10 = pixels[y0 * width + x1];
            int rgb01 = pixels[y1 * width + x0];
            int rgb11 = pixels[y1 * width + x1];

            int r = bilerp((rgb00 >> 16) & 0xFF, (rgb10 >> 16) & 0xFF,
                    (rgb01 >> 16) & 0xFF, (rgb11 >> 16) & 0xFF, tx, ty);

            int g = bilerp((rgb00 >> 8) & 0xFF, (rgb10 >> 8) & 0xFF,
                    (rgb01 >> 8) & 0xFF, (rgb11 >> 8) & 0xFF, tx, ty);

            int b = bilerp(rgb00 & 0xFF, rgb10 & 0xFF,
                    rgb01 & 0xFF, rgb11 & 0xFF, tx, ty);

            row[x] = (r << 16) | (g << 8) | b;
        }

        return row;
    }

    private static int bilerp(int c00, int c10, int c01, int c11, double tx, double ty) {
        double a = c00 * (1 - tx) + c10 * tx;
        double b = c01 * (1 - tx) + c11 * tx;
        return (int) Math.round(a * (1 - ty) + b * ty);
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

    // 🔹 Base64-PNG aus einer Zeile erzeugen
    private String encodeRowPng(int[] rowPixels, int width) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage rowImg = new BufferedImage(width, 1, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            rowImg.setRGB(x, 0, rowPixels[x]);
        }
        ImageIO.write(rowImg, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
