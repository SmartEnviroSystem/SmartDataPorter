package de.smartdata.porter.importer.parser.fileanalyser;

import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import de.smartdata.porter.importer.parser.Parser;
import de.smartdata.porter.importer.parser.ParserException;
import de.smartdata.porter.importer.parser.ParserResult;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;

/**
 *
 * @author Florian
 */
public class FileAnalyserParser extends Parser {

    private final List<MetadataExtractorModule> modules;
    private static volatile boolean pluginsLoaded = false;

    public FileAnalyserParser() {
        super();
        // ---------------------------------------------------------
        // MODULE INITIALISIEREN (alle möglichen Module)
        // ---------------------------------------------------------
        List<MetadataExtractorModule> list = new ArrayList<>();

        list.add(new ExifMetadataModule());
        list.add(new StaticKeogramModule());
        list.add(new DynamicEastWestKeogramModule());
        list.add(new DynamicNorthSouthKeogramModule());
        list.add(new DynamicAzElKeogramModule());
//        list.add(new DynamicKeogramMetadataModule());
//        list.add(new HistogramMetadataModule());
//        list.add(new CloudDetectionMetadataModule());
        // später: VideoMetadataModule, PdfMetadataModule, ...

        this.modules = List.copyOf(list);
    }

    @Override
    public String getDescription() {
        return "Analyses file contents and stores metadata in database.";
    }

    @Override
    public boolean isAccepted(InputStream is, String mimetype, String filename) {
        return true;
    }

    @Override
    public void preParse() {
        // nothing to do
    }

    @Override
    public ParserResult parse(InputStream is) throws ParserException {
        try {
            SourceDescriptor desc = importer.getSourceDescriptor();
            if (desc.getFileDate() == null) {
                throw new ParserException("File >" + desc.getName() + "< has no date.");
            }

            Path file = Paths.get(desc.getPath(), desc.getName());
            long size = Files.size(file);

            // --- Schneller MIME-Typ über Dateiendung ---
            String mime = fastMimeFromExtension(desc.getName());
            desc.setMimeType(mime);

            if ("image/webp".equals(mime)) {
                if (!pluginsLoaded) {
                    synchronized (FileAnalyserParser.class) {
                        if (!pluginsLoaded) {
                            ImageIO.scanForPlugins();
                            pluginsLoaded = true;
                        }
                    }
                }
            }

            String path = normalizePath(desc.getPath(), desc.getName());

            

            // Basis-JSON (immer im Haupt-Thread)
            JsonObjectBuilder baseBuilder = Json.createObjectBuilder()
                    .add("ts", desc.getFileDate().toString())
                    .add("name", desc.getName())
                    .add("path", path)
                    .add("size", size)
                    .add("mimetype", mime != null ? mime : "unknown");

            if (desc.getOrigin() != null) {
                baseBuilder.add("origin_url", safe(desc.getOrigin()));
            }
            if (desc.getCreateDateTime() != null) {
                baseBuilder.add("origin_created", safe(desc.getCreateDateTime()));
            }
            if (desc.getModifyDateTime() != null) {
                baseBuilder.add("origin_modified", safe(desc.getModifyDateTime()));
            }

            // --- Datei einmal lesen ---
            byte[] fileBytes = is.readAllBytes();
            desc.setFileBytes(fileBytes);

            // --- Bild einmal decodieren ---
            BufferedImage sharedImage = null;
            if (mime != null && mime.startsWith("image/")) {
                try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes)) {
                    sharedImage = ImageIO.read(bis);

                    int[] pixels = extractPixels(sharedImage);
                    desc.setPixels(pixels);
                } catch (Exception ignored) {
                    System.out.println("Could not read image: " + ignored.getLocalizedMessage());
                }
            }
            desc.setSharedImage(sharedImage);

            // --- Module parallel oder seriell ausführen ---
            boolean parallel = importer.getConfig().getBoolean("fileanalyser.parallel.modules", false);

            if (!parallel) {
                // Seriell: Module direkt in den Basis-Builder schreiben
                for (MetadataExtractorModule module : modules) {
                    String key = "fileanalyser.module." + module.getClass().getSimpleName();
                    if (!importer.getConfig().getBoolean(key, true)) {
                        continue;
                    }
                    if (!module.checkApplicability(desc, mime)) {
                        continue;
                    }
                    module.extractMetadata(desc, baseBuilder, importer);
                }
            } else {
                // Parallel: jedes Modul bekommt eigenen Builder, danach Merge
                int threads = Math.min(modules.size(), Runtime.getRuntime().availableProcessors());
                ExecutorService pool = Executors.newFixedThreadPool(threads);

                List<Callable<JsonObject>> tasks = new ArrayList<>();

                for (MetadataExtractorModule module : modules) {
                    String key = "fileanalyser.module." + module.getClass().getSimpleName();
                    boolean enabled = importer.getConfig().getBoolean(key, true);

                    if (!enabled) {
                        continue;
                    }
                    if (!module.checkApplicability(desc, mime)) {
                        continue;
                    }

                    tasks.add(() -> {
                        JsonObjectBuilder mb = Json.createObjectBuilder();
                        module.extractMetadata(desc, mb, importer);
                        return mb.build();
                    });
                }

                try {
                    List<Future<JsonObject>> results = pool.invokeAll(tasks);

                    // Modul-Ergebnisse in baseBuilder mergen
                    for (Future<JsonObject> f : results) {
                        try {
                            JsonObject o = f.get();
                            for (String k : o.keySet()) {
                                baseBuilder.add(k, o.get(k));
                            }
                        } catch (Exception e) {
                            System.out.println("Module execution failed: " + e.getMessage());
                        }
                    }
                } finally {
                    pool.shutdown();
                }
            }

            JsonObject json = baseBuilder.build();
            importer.addDataSet(json);

            this.result.datasetFromDate = desc.getFileDate().toLocalDateTime();
            this.result.datasetUntilDate = desc.getFileDate().toLocalDateTime();
            this.result.datasetsAvailable = 1;
            this.result.datasetsParsed = 1;

            return result;

        } catch (Exception ex) {
            ParserException pe = new ParserException("Could not analyse file: " + ex.getMessage());
            pe.addSuppressed(ex);
            throw pe;
        }
    }

    private static String fastMimeFromExtension(String filename) {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            return "image/tiff";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".heic")) {
            return "image/heic";
        }
        if (lower.endsWith(".heif")) {
            return "image/heif";
        }

        return "application/octet-stream";
    }

    // ---------------------------------------------------------
    // Hilfsmethoden
    // ---------------------------------------------------------
    private String normalizePath(String path, String filename) {
        int idx = path.indexOf("docroot");
        if (idx != -1) {
            path = path.substring(idx + "docroot".length());
            if (path.startsWith("/") || path.startsWith("\\")) {
                path = path.substring(1);
            }
        }
        return path + File.separator + filename;
    }

    private String safe(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int[] extractPixels(BufferedImage img) {
        if (img == null) {
            return null;
        }

        final int width = img.getWidth();
        final int height = img.getHeight();

        DataBuffer db = img.getRaster().getDataBuffer();

        if (db instanceof DataBufferInt dbi) {
            return dbi.getData();
        }

        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);
        return pixels;
    }
}
