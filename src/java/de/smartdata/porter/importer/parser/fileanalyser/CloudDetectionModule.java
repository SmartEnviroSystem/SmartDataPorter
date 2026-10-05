package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.Importer;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 *
 * @author Florian
 */
public class CloudDetectionModule implements MetadataExtractorModule {

    @Override
    public boolean checkApplicability(SourceDescriptor desc, String mime) {
        return mime != null && mime.startsWith("image/");
    }

    @Override
    public void extractMetadata(SourceDescriptor desc, JsonObjectBuilder json, Importer importer) throws Exception {
BufferedImage img = desc.getSharedImage();
byte[] bytes = desc.getFileBytes();
        
        JsonObject snapshot = json.build();

        JsonArray histR = snapshot.getJsonArray("hist_r");
        JsonArray histG = snapshot.getJsonArray("hist_g");
        JsonArray histB = snapshot.getJsonArray("hist_b");
        JsonArray histL = snapshot.getJsonArray("hist_luma");

        JsonObjectBuilder merged = Json.createObjectBuilder(snapshot);
        JsonObjectBuilder target = merged;

        if (img == null) {
            target.add("cloud_detection_error", "image_read_failed");
            target.build().forEach(json::add);
            return;
        }

        final int width = img.getWidth();
        final int height = img.getHeight();

        final int cx = width / 2;
        final int cy = height / 2;

        int radius = importer.getConfig().getInt("clouddetection.fisheye.radius", 820);
        int r2 = radius * radius;

        // Pixel-Raster (schneller als getRGB)
        final int[] pixels;
        if (img.getRaster().getDataBuffer() instanceof DataBufferInt) {
            pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        } else {
            // Fallback, falls kein INT-Raster
            BufferedImage tmp = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = tmp.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            pixels = ((DataBufferInt) tmp.getRaster().getDataBuffer()).getData();
        }

        // dx²-LUT
        final int[] dx2 = new int[width];
        for (int x = 0; x < width; x++) {
            int dx = x - cx;
            dx2[x] = dx * dx;
        }

        // Luma-LUTs
        final double[] lumaR = new double[256];
        final double[] lumaG = new double[256];
        final double[] lumaB = new double[256];
        for (int i = 0; i < 256; i++) {
            lumaR[i] = 0.2126 * i;
            lumaG[i] = 0.7152 * i;
            lumaB[i] = 0.0722 * i;
        }

        String maskFilename = "maskline_" + radius + "px.png";
        File maskFile = new File(desc.getPath(), maskFilename);

        if (!maskFile.exists()) {
            BufferedImage maskLine = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gL = maskLine.createGraphics();

            gL.setComposite(AlphaComposite.Clear);
            gL.fillRect(0, 0, width, height);

            gL.setComposite(AlphaComposite.SrcOver);
            gL.setColor(new Color(0, 255, 255, 255));
            gL.setStroke(new BasicStroke(3f));
            gL.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            gL.dispose();

            ImageIO.write(maskLine, "PNG", maskFile);
        }

        target.add("sky_mask_line", maskFile.toString().replace("..\\docroot\\", ""));

        // ---------------------------------------------------------
        // 2. Sky-Mask + Luma/Saturation (erster Durchlauf, parallel)
        // ---------------------------------------------------------
        boolean[][] skyMask = new boolean[height][width];
        int[][] lumaMap = new int[height][width];
        double[][] satMap = new double[height][width];

        LongAdder skyPixelsAdder = new LongAdder();
        DoubleAdder sumLumaAdder = new DoubleAdder();
        DoubleAdder sumSatAdder = new DoubleAdder();

        IntStream.range(0, height).parallel().forEach(y -> {
            int dy = y - cy;
            int dy2 = dy * dy;

            boolean[] skyRow = skyMask[y];
            int[] lumaRow = lumaMap[y];
            double[] satRow = satMap[y];

            for (int x = 0; x < width; x++) {
                if (dx2[x] + dy2 > r2) {
                    continue;
                }

                int rgb = pixels[y * width + x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));

                int luma = (int) (lumaR[r] + lumaG[g] + lumaB[b]);
                double sat = max == 0 ? 0.0 : (max - min) / (double) max;

                skyRow[x] = true;
                lumaRow[x] = luma;
                satRow[x] = sat;

                skyPixelsAdder.increment();
                sumLumaAdder.add(luma);
                sumSatAdder.add(sat);
            }
        });

        long skyPixels = skyPixelsAdder.sum();
        if (skyPixels == 0) {
            target.add("cloud_detection_error", "sky_mask_empty");
            target.build().forEach(json::add);
            return;
        }

        double avgLuma = sumLumaAdder.sum() / skyPixels;
        double avgSat = sumSatAdder.sum() / skyPixels;

        // ---------------------------------------------------------
        // 3. Cloud + Blue + Edge + Gradient + Kontrast (zweiter Durchlauf, parallel)
        // ---------------------------------------------------------
        LongAdder cloudPixelsAdder = new LongAdder();
        LongAdder bluePixelsAdder = new LongAdder();

        DoubleAdder sumContrastAdder = new DoubleAdder();
        DoubleAdder sumGradAdder = new DoubleAdder();
        LongAdder gradCountAdder = new LongAdder();

        IntStream.range(1, height - 1).parallel().forEach(y -> {
            for (int x = 1; x < width - 1; x++) {
                if (!skyMask[y][x]) {
                    continue;
                }

                int rgb = pixels[y * width + x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int luma = lumaMap[y][x];
                double sat = satMap[y][x];

                int edge =
                        Math.abs(luma - lumaMap[y - 1][x]) +
                        Math.abs(luma - lumaMap[y + 1][x]) +
                        Math.abs(luma - lumaMap[y][x - 1]) +
                        Math.abs(luma - lumaMap[y][x + 1]);

                int gradLocal =
                        Math.abs(lumaMap[y][x + 1] - lumaMap[y][x - 1]) +
                        Math.abs(lumaMap[y + 1][x] - lumaMap[y - 1][x]);

                boolean isCloudBase =
                        (sat < 0.40) &&
                        (luma > avgLuma + 2 || luma < avgLuma - 10);

                boolean isCloudEdges =
                        (sat < 0.45) &&
                        (edge > 25);

                boolean isCloudGrad =
                        (sat < 0.45) &&
                        (gradLocal > 25);

                boolean isCloud = isCloudBase || isCloudEdges || isCloudGrad;

                if (isCloud) {
                    cloudPixelsAdder.increment();
                }

                boolean isBlueSky =
                        (b > r + 15) &&
                        (b > g + 15) &&
                        (sat > 0.20) &&
                        (luma > 60) &&
                        !(r > 200 && g > 200 && b < 150);

                if (isBlueSky) {
                    bluePixelsAdder.increment();
                }

                int c = lumaMap[y][x];

                int diff =
                        Math.abs(c - lumaMap[y - 1][x]) +
                        Math.abs(c - lumaMap[y + 1][x]) +
                        Math.abs(c - lumaMap[y][x - 1]) +
                        Math.abs(c - lumaMap[y][x + 1]);

                sumContrastAdder.add(diff / 4.0);

                int gx = Math.abs(lumaMap[y][x + 1] - lumaMap[y][x - 1]);
                int gy = Math.abs(lumaMap[y + 1][x] - lumaMap[y - 1][x]);
                sumGradAdder.add((gx + gy) / 2.0);

                gradCountAdder.increment();
            }
        });

        int cloudPixels = cloudPixelsAdder.intValue();
        int bluePixels = bluePixelsAdder.intValue();

        double sumContrast = sumContrastAdder.sum();
        double sumGrad = sumGradAdder.sum();
        long gradCount = gradCountAdder.sum();

        double avgContrast = sumContrast / skyPixels;
        double brightnessGradientMean = gradCount == 0 ? 0.0 : (sumGrad / gradCount);

        // ---------------------------------------------------------
        // 4. Pixel-basierte Finalwerte
        // ---------------------------------------------------------
        double coverageAnalytic = cloudPixels / (double) skyPixels;
        double blueRatio = bluePixels / (double) skyPixels;

        double normLuma = avgLuma / 255.0;
        double normContrast = Math.max(avgContrast / 255.0, 0.01);
        double fogIndex = normLuma / normContrast;

        double cloudOpacity = (normContrast * 0.6) + ((1.0 - normLuma) * 0.4);
        double fragmentation = normContrast * (1.0 - coverageAnalytic) * (1.0 - blueRatio);
        double hazeIndex = (normLuma * 0.5) + ((1.0 - normContrast) * 0.5);

        double skyClarity = blueRatio * (1.0 - coverageAnalytic) * (1.0 - hazeIndex);
        double cloudTempProxy = 1.0 - normLuma;

        // ---------------------------------------------------------
        // 5. Histogramm-Analyse (unverändert)
        // ---------------------------------------------------------
        double lumaWidth = computeHistogramWidth(histL);
        double bluePeakStrength = computeBluePeakStrength(histB);
        double saturationWidth = computeSaturationWidth(histR, histG, histB);

        double cloudHistogramScore =
                (1 - bluePeakStrength) * 0.3 +
                lumaWidth * 0.2 +
                saturationWidth * 0.1;

        cloudHistogramScore = Math.min(1.0, Math.max(0.0, cloudHistogramScore));

        // ---------------------------------------------------------
        // 6. Visuelle Bewölkung
        // ---------------------------------------------------------
        double improvedVisualCoverage =
                0.40 * coverageAnalytic +
                0.40 * (1.0 - blueRatio) +
                0.20 * cloudHistogramScore;

        improvedVisualCoverage = Math.min(1.0, Math.max(0.0, improvedVisualCoverage));

        String cloudClassAnalytic;
        if (coverageAnalytic < 0.15) {
            cloudClassAnalytic = "clear";
        } else if (coverageAnalytic < 0.35) {
            cloudClassAnalytic = "partly_cloudy";
        } else if (coverageAnalytic < 0.55) {
            cloudClassAnalytic = "mostly_cloudy";
        } else if (coverageAnalytic < 0.80) {
            cloudClassAnalytic = "cloudy";
        } else {
            cloudClassAnalytic = "overcast";
        }

        target.add("interpreted_cloud_class_analytic", cloudClassAnalytic);

        String cloudClassVisual;
        if (improvedVisualCoverage < 0.15) {
            cloudClassVisual = "clear";
        } else if (improvedVisualCoverage < 0.35) {
            cloudClassVisual = "partly_cloudy";
        } else if (improvedVisualCoverage < 0.55) {
            cloudClassVisual = "mostly_cloudy";
        } else if (improvedVisualCoverage < 0.80) {
            cloudClassVisual = "cloudy";
        } else {
            cloudClassVisual = "overcast";
        }

        target.add("interpreted_cloud_class_visual", cloudClassVisual);

        // ---------------------------------------------------------
        // 7. RAW / DERIVED / INTERPRETED JSON
        // ---------------------------------------------------------
        target.add("raw_brightness", normLuma);
        target.add("raw_saturation", avgSat);
        target.add("raw_contrast", normContrast);
        target.add("raw_gradient", brightnessGradientMean / 255.0);
        target.add("raw_blue_ratio", blueRatio);
        target.add("raw_horizon_radius", radius);

        // DERIVED
        target.add("derived_cloud_coverage_analytic", coverageAnalytic);
        target.add("derived_cloud_coverage_visual", improvedVisualCoverage);
        target.add("derived_fog_index", fogIndex);
        target.add("derived_cloud_opacity", cloudOpacity);
        target.add("derived_fragmentation", fragmentation);
        target.add("derived_haze_index", hazeIndex);

        target.add("derived_luma_hist_width", lumaWidth);
        target.add("derived_blue_peak_strength", bluePeakStrength);
        target.add("derived_saturation_hist_width", saturationWidth);
        target.add("derived_cloud_histogram_score", cloudHistogramScore);

        // INTERPRETED
        String cloudType;
        if (coverageAnalytic < 0.05 && blueRatio > 0.55 && normContrast > 0.12) {
            cloudType = "Clear Sky";
        } else if (fogIndex > 1.2 && normContrast < 0.10 && blueRatio < 0.20) {
            cloudType = "Fog";
        } else if (coverageAnalytic < 0.30 && normLuma > 0.60 && normContrast < 0.18) {
            cloudType = "Cirrus";
        } else if (coverageAnalytic < 0.70 && normContrast > 0.22) {
            cloudType = "Cumulus";
        } else if (coverageAnalytic > 0.80 && normLuma < 0.45) {
            cloudType = "Nimbostratus";
        } else if (coverageAnalytic > 0.60 && normContrast < 0.18) {
            cloudType = "Stratus";
        } else {
            cloudType = "Unknown";
        }

        String cloudHeight;
        if (fogIndex > 1.2 && normContrast < 0.10 && blueRatio < 0.20) {
            cloudHeight = "sehr niedrig (0–300 m)";
        } else if (coverageAnalytic > 0.6 && normLuma < 0.5) {
            cloudHeight = "niedrig (300–1500 m)";
        } else if (normContrast > 0.25 && coverageAnalytic < 0.6) {
            cloudHeight = "mittel (1500–3000 m)";
        } else if (normLuma > 0.6 && normContrast < 0.15 && coverageAnalytic < 0.3) {
            cloudHeight = "hoch (6000–12000 m)";
        } else {
            cloudHeight = "unbekannt";
        }

        target.add("interpreted_cloud_type", cloudType);
        target.add("interpreted_cloud_height", cloudHeight);
        target.add("interpreted_sky_clarity", skyClarity);
        target.add("interpreted_cloud_temp_proxy", cloudTempProxy);

        target.build().forEach(json::add);
    }

    // ---------------------------------------------------------
    // HISTOGRAMM-FUNKTIONEN
    // ---------------------------------------------------------
    private double computeHistogramWidth(JsonArray hist) {
        int total = 0;
        for (int i = 0; i < hist.size(); i++) {
            total += hist.getInt(i);
        }

        if (total == 0) {
            return 0.0;
        }

        int low = 0;
        int high = 255;
        int threshold = (int) (total * 0.01);

        int sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += hist.getInt(i);
            if (sum > threshold) {
                low = i;
                break;
            }
        }

        sum = 0;
        for (int i = 255; i >= 0; i--) {
            sum += hist.getInt(i);
            if (sum > threshold) {
                high = i;
                break;
            }
        }

        double width = (high - low) / 255.0;

        if (width < 0.0) width = 0.0;
        if (width > 1.0) width = 1.0;

        return width;
    }

    private double computeBluePeakStrength(JsonArray histB) {
        int max = 0;
        for (int i = 0; i < histB.size(); i++) {
            max = Math.max(max, histB.getInt(i));
        }

        double v = max / 50000.0;
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;

        return v;
    }

    private double computeSaturationWidth(JsonArray histR, JsonArray histG, JsonArray histB) {
        int[] satHist = new int[256];

        for (int i = 0; i < 256; i++) {
            int rCount = histR.getInt(i);
            int gCount = histG.getInt(i);
            int bCount = histB.getInt(i);

            int maxCount = Math.max(rCount, Math.max(gCount, bCount));
            int minCount = Math.min(rCount, Math.min(gCount, bCount));

            double sat = maxCount == 0 ? 0.0 : (maxCount - minCount) / (double) maxCount;

            int bin = (int) (sat * 255);
            if (bin < 0) bin = 0;
            if (bin > 255) bin = 255;

            satHist[bin] += (rCount + gCount + bCount);
        }

        int total = 0;
        for (int v : satHist) {
            total += v;
        }

        if (total == 0) {
            return 0.0;
        }

        int low = 0;
        int high = 255;
        int threshold = (int) (total * 0.01);

        int sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += satHist[i];
            if (sum > threshold) {
                low = i;
                break;
            }
        }

        sum = 0;
        for (int i = 255; i >= 0; i--) {
            sum += satHist[i];
            if (sum > threshold) {
                high = i;
                break;
            }
        }

        double width = (high - low) / 255.0;

        if (width < 0.0) width = 0.0;
        if (width > 1.0) width = 1.0;

        return width;
    }

    private String encodeMaskRLE(boolean[][] mask) {
        StringBuilder sb = new StringBuilder();
        for (boolean[] row : mask) {
            int count = 0;
            boolean last = row[0];
            for (boolean b : row) {
                if (b == last) {
                    count++;
                } else {
                    sb.append(last ? "1:" : "0:").append(count).append(",");
                    last = b;
                    count = 1;
                }
            }
            sb.append(last ? "1:" : "0:").append(count).append(";");
        }
        return sb.toString();
    }
}