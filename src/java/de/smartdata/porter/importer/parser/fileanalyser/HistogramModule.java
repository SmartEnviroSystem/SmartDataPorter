package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 *
 * @author Florian
 */
public class HistogramModule implements MetadataExtractorModule {

    private static volatile boolean pluginsLoaded = false;

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mime) {
        return mime != null && mime.startsWith("image/");
    }

    @Override
    public void extractMetadata(SourceDescriptor desc, JsonObjectBuilder json, Importer importer) throws Exception {
        BufferedImage img = desc.getSharedImage();
        byte[] bytes = desc.getFileBytes();
        
        // WEBP-Plugin einmalig laden
        if (!pluginsLoaded) {
            synchronized (HistogramModule.class) {
                if (!pluginsLoaded) {
                    ImageIO.scanForPlugins();
                    pluginsLoaded = true;
                }
            }
        }

        if (img == null) return;

        final int width = img.getWidth();
        final int height = img.getHeight();

        // Schneller Zugriff auf Pixel
        final int[] pixels;
        if (img.getRaster().getDataBuffer() instanceof DataBufferInt) {
            pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        } else {
            BufferedImage tmp = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = tmp.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            pixels = ((DataBufferInt) tmp.getRaster().getDataBuffer()).getData();
        }

        // Luma-LUT
        final double[] lumaR = new double[256];
        final double[] lumaG = new double[256];
        final double[] lumaB = new double[256];
        for (int i = 0; i < 256; i++) {
            lumaR[i] = 0.2126 * i;
            lumaG[i] = 0.7152 * i;
            lumaB[i] = 0.0722 * i;
        }

        // Histogramm parallel berechnen
        Hist totalHist = IntStream.range(0, height).parallel()
                .mapToObj(y -> {
                    Hist h = new Hist();
                    int base = y * width;
                    for (int x = 0; x < width; x++) {
                        int rgb = pixels[base + x];

                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;

                        int luma = (int) (lumaR[r] + lumaG[g] + lumaB[b]);

                        h.histR[r]++;
                        h.histG[g]++;
                        h.histB[b]++;
                        h.histLuma[luma]++;
                    }
                    return h;
                })
                .reduce(new Hist(), Hist::merge);

        // JSON-Ausgabe
        JsonArrayBuilder rArr = Json.createArrayBuilder();
        JsonArrayBuilder gArr = Json.createArrayBuilder();
        JsonArrayBuilder bArr = Json.createArrayBuilder();
        JsonArrayBuilder lArr = Json.createArrayBuilder();

        for (int i = 0; i < 256; i++) {
            rArr.add(totalHist.histR[i]);
            gArr.add(totalHist.histG[i]);
            bArr.add(totalHist.histB[i]);
            lArr.add(totalHist.histLuma[i]);
        }

        json.add("hist_r", rArr);
        json.add("hist_g", gArr);
        json.add("hist_b", bArr);
        json.add("hist_luma", lArr);
    }

    private static class Hist {
        final int[] histR = new int[256];
        final int[] histG = new int[256];
        final int[] histB = new int[256];
        final int[] histLuma = new int[256];

        Hist merge(Hist other) {
            for (int i = 0; i < 256; i++) {
                this.histR[i] += other.histR[i];
                this.histG[i] += other.histG[i];
                this.histB[i] += other.histB[i];
                this.histLuma[i] += other.histLuma[i];
            }
            return this;
        }
    }
}

