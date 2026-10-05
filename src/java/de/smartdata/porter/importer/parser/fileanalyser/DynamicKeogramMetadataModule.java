package de.smartdata.porter.importer.parser.fileanalyser;

/**
 *
 * @author Florian
 */
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;
import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.*;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.Base64;
public class DynamicKeogramMetadataModule implements MetadataExtractorModule {

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mime) {
        return mime != null && mime.startsWith("image/");
    }

    @Override
    public void extractMetadata(SourceDescriptor desc, JsonObjectBuilder json, Importer importer) throws Exception {

        BufferedImage img = desc.getSharedImage();
        if (img == null) return;

        final int width = img.getWidth();
        final int height = img.getHeight();
        final int[] pixels = desc.getPixels();
        if (pixels == null) return;

        // ---------------------------------------------------------
        // CONFIG
        // ---------------------------------------------------------
        JsonObject cfg = importer.getConfig();

        double cx = cfg.getJsonNumber("dynkeogram.fisheye.cx") != null
                ? cfg.getJsonNumber("dynkeogram.fisheye.cx").doubleValue()
                : width * 0.5;

        double cy = cfg.getJsonNumber("dynkeogram.fisheye.cy") != null
                ? cfg.getJsonNumber("dynkeogram.fisheye.cy").doubleValue()
                : height * 0.5;

        double k1 = get(cfg, "dynkeogram.fisheye.k1");
        double k2 = get(cfg, "dynkeogram.fisheye.k2");
        double k3 = get(cfg, "dynkeogram.fisheye.k3");

        double roll  = Math.toRadians(get(cfg, "dynkeogram.camera.roll"));
        double pitch = Math.toRadians(get(cfg, "dynkeogram.camera.pitch"));
        double yaw   = Math.toRadians(get(cfg, "dynkeogram.camera.yaw"));

        double lat = get(cfg, "dynkeogram.location.lat");
        double lon = get(cfg, "dynkeogram.location.lon");

        LocalDateTime time = desc.getFileDate().toLocalDateTime();

        // ---------------------------------------------------------
        // SONNENPOSITION
        // ---------------------------------------------------------
        SunPos sun = computeSunPositionFast(time, lat, lon);

        double az = Math.toRadians(sun.azimuth);
        double el = Math.toRadians(sun.elevation);

        double cosEl = Math.cos(el);
        double vSunX = cosEl * Math.sin(az);
        double vSunY = cosEl * Math.cos(az);
        double vSunZ = Math.sin(el);

        // ---------------------------------------------------------
        // ROTATION
        // ---------------------------------------------------------
        double[] vCam = rotateVector(yaw, pitch, roll, vSunX, vSunY, vSunZ);

        // ---------------------------------------------------------
        // FISHEYE-PROJEKTION
        // ---------------------------------------------------------
        int[] uv = projectFisheyeFast(vCam, cx, cy, k1, k2, k3);
        int uSun = clamp(uv[0], 0, width - 1);
        int vSun = clamp(uv[1], 0, height - 1);

        // ---------------------------------------------------------
        // FISHEYE-MASKE
        // ---------------------------------------------------------
        double maxRadius = Math.min(width, height) * 0.5;
        double maxRadiusSq = maxRadius * maxRadius;

        // ---------------------------------------------------------
        // NORTH–SOUTH KEOGRAMM (vertikale Spalte)
        // ---------------------------------------------------------
        int[] colPixels = new int[height];
        for (int y = 0; y < height; y++) {
            int rgb = pixels[y * width + uSun];

            double dx = uSun - cx;
            double dy = y - cy;
            if (dx * dx + dy * dy > maxRadiusSq) rgb = 0;

            colPixels[y] = rgb;
        }

        json.add("dynkeogram_ns_png", encodeColumnPng(colPixels, height));

        // ---------------------------------------------------------
        // EAST–WEST KEOGRAMM (horizontale Zeile)
        // ---------------------------------------------------------
        int[] rowPixels = new int[width];
        for (int x = 0; x < width; x++) {
            int rgb = pixels[vSun * width + x];

            double dx = x - cx;
            double dy = vSun - cy;
            if (dx * dx + dy * dy > maxRadiusSq) rgb = 0;

            rowPixels[x] = rgb;
        }

        json.add("dynkeogram_ew_png", encodeRowPng(rowPixels, width));

        // ---------------------------------------------------------
        // METADATEN
        // ---------------------------------------------------------
        json.add("sun_azimuth", sun.azimuth);
        json.add("sun_elevation", sun.elevation);
        json.add("sun_pixel_u", uSun);
        json.add("sun_pixel_v", vSun);
        json.add("image_width", width);
        json.add("image_height", height);
    }

    // =====================================================================
    // HILFSFUNKTIONEN
    // =====================================================================

    private static double get(JsonObject cfg, String key) {
        JsonNumber n = cfg.getJsonNumber(key);
        return n != null ? n.doubleValue() : 0.0;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private SunPos computeSunPositionFast(LocalDateTime time, double lat, double lon) {
        double jd = time.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli() * 1.1574074074074E-8 + 2440587.5;
        double n = jd - 2451545.0;

        double g = Math.toRadians(357.529 + 0.98560028 * n);
        double q = Math.toRadians(280.459 + 0.98564736 * n);

        double L = q + Math.toRadians(1.915) * Math.sin(g) + Math.toRadians(0.020) * Math.sin(2 * g);
        double e = Math.toRadians(23.439 - 0.00000036 * n);

        double RA = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L));
        double decl = Math.asin(Math.sin(e) * Math.sin(L));

        double GMST = Math.toRadians((280.46061837 + 360.98564736629 * (jd - 2451545) + lon) % 360);
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

    private static double[] rotateVector(double yaw, double pitch, double roll,
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

    private static int[] projectFisheyeFast(double[] v, double cx, double cy,
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

    private static class SunPos {
        final double azimuth;
        final double elevation;

        SunPos(double az, double el) {
            this.azimuth = az;
            this.elevation = el;
        }
    }

    private String encodeRowPng(int[] rowPixels, int width) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        ImageInfo info = new ImageInfo(width, 1, 8, false);
        PngWriter writer = new PngWriter(baos, info);
        ImageLineInt line = new ImageLineInt(info);

        int[] scan = line.getScanline();

        for (int x = 0; x < width; x++) {
            int rgb = rowPixels[x];
            scan[x * 3]     = (rgb >> 16) & 0xFF;
            scan[x * 3 + 1] = (rgb >> 8) & 0xFF;
            scan[x * 3 + 2] = rgb & 0xFF;
        }

        writer.writeRow(line);
        writer.end();

        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private String encodeColumnPng(int[] colPixels, int height) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        ImageInfo info = new ImageInfo(1, height, 8, false);
        PngWriter writer = new PngWriter(baos, info);
        ImageLineInt line = new ImageLineInt(info);

        int[] scan = line.getScanline();

        for (int y = 0; y < height; y++) {
            int rgb = colPixels[y];
            scan[0] = (rgb >> 16) & 0xFF;
            scan[1] = (rgb >> 8) & 0xFF;
            scan[2] = rgb & 0xFF;

            writer.writeRow(line);
        }

        writer.end();

        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
