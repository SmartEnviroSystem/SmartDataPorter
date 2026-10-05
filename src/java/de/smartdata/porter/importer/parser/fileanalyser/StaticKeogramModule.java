package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 *
 * @author Florian
 */
import ar.com.hjg.pngj.*;
import ar.com.hjg.pngj.ImageLineInt;

public class StaticKeogramModule implements MetadataExtractorModule {

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mime) {
        return mime != null && mime.startsWith("image/");
    }

    @Override
    public void extractMetadata(SourceDescriptor desc, JsonObjectBuilder json, Importer importer) throws Exception {
//long t0 = System.nanoTime();
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
        // PNGJ: MITTLERE ZEILE (width × 1)
        // ---------------------------------------------------------
        int midY = height >>> 1;
        int offsetY = midY * width;

        String lineB64 = encodeRowPng(pixels, offsetY, width);
        json.add("keogram_line_png", lineB64);

        // ---------------------------------------------------------
        // PNGJ: MITTLERE SPALTE (1 × height)
        // ---------------------------------------------------------
        int midX = width >>> 1;

        int[] colPixels = new int[height];
        for (int y = 0; y < height; y++) {
            colPixels[y] = pixels[y * width + midX];
        }

        String colB64 = encodeColumnPng(colPixels, height);
        json.add("keogram_column_png", colB64);

        // ---------------------------------------------------------
        // METADATEN
        // ---------------------------------------------------------
        json.add("image_width", width);
        json.add("image_height", height);
//        System.out.println("StaticKeogramMetadataModule: " + (System.nanoTime() - t0)/1_000_000 + " ms");
    }

    // =====================================================================
    // PNGJ ENCODER: ROW (width × 1)
    // =====================================================================
    private String encodeRowPng(int[] pixels, int offset, int width) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        ImageInfo info = new ImageInfo(width, 1, 8, false); // RGB, 8-bit
        PngWriter writer = new PngWriter(baos, info);
        ImageLineInt line = new ImageLineInt(info);

        int[] scan = line.getScanline();

        for (int x = 0; x < width; x++) {
            int rgb = pixels[offset + x];
            scan[x * 3]     = (rgb >> 16) & 0xFF; // R
            scan[x * 3 + 1] = (rgb >> 8) & 0xFF;  // G
            scan[x * 3 + 2] = rgb & 0xFF;         // B
        }

        writer.writeRow(line);
        writer.end();

        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // =====================================================================
    // PNGJ ENCODER: COLUMN (1 × height)
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

