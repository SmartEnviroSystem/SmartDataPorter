package de.smartdata.porter.importer.parser.fileanalyser;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;
import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

/**
 *
 * @author Florian
 */
public class DynamicNorthSouthKeogramModule implements MetadataExtractorModule {

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mime) {
        return mime != null && mime.startsWith("image/");
    }

    @Override
    public void extractMetadata(SourceDescriptor desc, JsonObjectBuilder json, Importer importer) throws Exception {

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

        // ---------------------------------------------------------
        // CONFIG
        // ---------------------------------------------------------
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

        // ---------------------------------------------------------
        // SONNENPOSITION (Python-kompatibel)
        // ---------------------------------------------------------
        SunPos sun = computeSunPositionPython(time, lat, lon);

        double az = Math.toRadians(sun.azimuth);
        double el = Math.toRadians(sun.elevation);

        double cosEl = Math.cos(el);
        double vSunX = cosEl * Math.sin(az);
        double vSunY = cosEl * Math.cos(az);
        double vSunZ = Math.sin(el);

        // ---------------------------------------------------------
        // ROTATION (ZYX wie scipy)
        // ---------------------------------------------------------
        double[] vCam = rotateZYX(yaw, pitch, roll, vSunX, vSunY, vSunZ);

        // ---------------------------------------------------------
        // FISHEYE-PROJEKTION
        // ---------------------------------------------------------
        int[] uv = projectFisheye(vCam, cx, cy, k1, k2, k3);
        int uSun = clamp(uv[0], 0, width - 1);
        int vSun = clamp(uv[1], 0, height - 1);

        // ---------------------------------------------------------
        // NORTH–SOUTH KEOGRAMM (vertikale Spalte)
        // ---------------------------------------------------------
        int[] colPixels = new int[height];
        for (int y = 0; y < height; y++) {
            colPixels[y] = pixels[y * width + uSun];
        }

        json.add("dynkeogram_ns_png", encodeColumnPng(colPixels, height));

        // ---------------------------------------------------------
        // METADATEN
        // ---------------------------------------------------------
        json.add("dynkeo_ns_azimuth", sun.azimuth);
        json.add("dynkeo_ns_elevation", sun.elevation);
        json.add("dynkeo_ns_pixel_u", uSun);
        json.add("dynkeo_ns_pixel_v", vSun);
        json.add("image_width", width);
        json.add("image_height", height);
    }

    // =====================================================================
    // PYTHON-KOMPATIBLE SONNENPOSITION
    // =====================================================================
    private SunPos computeSunPositionPython(LocalDateTime time, double lat, double lon) {

        // Julian Date wie pandas Timestamp.to_julian_date()
        double jd = (time.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli() / 86400000.0)
                + 2440587.5;

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

    // =====================================================================
    // ROTATION ZYX (Python-kompatibel)
    // =====================================================================
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

    // =====================================================================
    // FISHEYE-PROJEKTION
    // =====================================================================
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

    // =====================================================================
    // PNG ENCODER
    // =====================================================================
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
