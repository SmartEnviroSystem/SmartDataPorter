package de.smartdata.porter.importer;

import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import de.smartdata.porter.importer.helper.TimestampParser;
import de.smartdata.porter.importer.parser.ParserException;
import de.smartdata.porter.importer.parser.ParserResult;
import de.smartdata.porter.values.converter.UrlDateFormatter;
import jakarta.json.JsonObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * WebCrawlerImporter for importing data found on webpages.
 *
 * @author Florian
 */
public class WebCrawlerImporter extends FilesImporter {

    private Path targetFolder;
    private Set<String> allowedExtensions;
    private Set<String> allowedMimeTypes;
    private int maxDepth;
    private boolean followParameterized;
    private String startPath;
    private final Set<String> processed = new HashSet<>();

    public WebCrawlerImporter(JsonObject config, SourceDescriptor sd) throws ImporterException {
        super(config, sd);

        try {
            this.targetFolder = Paths.get(config.getString("crawler.targetFolder"));
            Files.createDirectories(targetFolder);

            this.allowedExtensions = Set.of(config.getString("crawler.allowedExtensions").split(","));
            this.allowedMimeTypes = Set.of(config.getString("crawler.allowedMimeTypes").split(","));
            this.maxDepth = config.getInt("crawler.maxDepth");
            this.followParameterized = config.getBoolean("crawler.followParameterizedLinks", false);

        } catch (Exception e) {
            throw new ImporterException("Could not initialize WebCrawlerImporter", e);
        }
    }

    @Override
    public void run() {
        try {
            String baseUrl = config.getString("crawler.baseurl");

            // Datum in URL einsetzen
            LocalDate today = LocalDate.now();
            baseUrl = UrlDateFormatter.formatUrlWithDate(baseUrl, today);

            String dateConfig = config.getString("crawler.datepath", null);
            Document doc;

            if (dateConfig != null) {
                LocalDate date = resolveDate(dateConfig);
                String datePath = String.format("/%04d/%02d/%02d/",
                        date.getYear(), date.getMonthValue(), date.getDayOfMonth());

                while (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                while (datePath.startsWith("/")) {
                    datePath = datePath.substring(1);
                }

                baseUrl = baseUrl + "/" + datePath;
                doc = fetchWithRetry(baseUrl, 3, 2000);
            } else {
                doc = Jsoup.connect(baseUrl)
                        .timeout(180_000)
                        .maxBodySize(0)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .get();
            }

            this.startPath = new URI(baseUrl).getPath();

            this.parser.preParse();
            crawl(doc, baseUrl, 0);

            this.saveDatasets();
            this.sendResultMail();

        } catch (Exception ex) {
            this.result.addMessage(new Message("WebCrawlerImporter error: " + ex.getMessage(), MessageLevel.ERROR));
            ex.printStackTrace();
        }
    }

    // -------------------- Crawling --------------------
    private void crawl(Document doc, String baseUrl, int depth) throws Exception {
        if (depth > maxDepth) {
            return;
        }

        for (Element link : doc.select("a[href], img[src]")) {
            String url = link.hasAttr("href") ? link.absUrl("href") : link.absUrl("src");

            if (isFileUrl(url)) {
                processFile(url);
            } else if (shouldFollow(url)) {
                Document next = fetchWithRetry(url, 3, 2000);
                crawl(next, url, depth + 1);
                processed.add(url);
            }
        }
    }

    private boolean isFileUrl(String url) {
        int idx = url.lastIndexOf('.');
        if (idx < 0 || idx == url.length() - 1) {
            return false;
        }
        String ext = url.substring(idx + 1).toLowerCase();
        return allowedExtensions.contains(ext);
    }

    private boolean shouldFollow(String url) {
        if (!this.followParameterized && url.contains("?")) {
            return false;
        }

        if (!isHtmlOrDirectory(url)) {
            return false;
        }

        try {
            String path = new URI(url).getPath();
            if (!path.startsWith(startPath)) {
                return false;
            }
        } catch (Exception ex) {
            System.out.println("Could not create path from >" + url + "<: " + ex.getLocalizedMessage());
            return false;
        }

        return !processed.contains(url);
    }

    private boolean isHtmlOrDirectory(String url) {
        String lower = url.toLowerCase();

        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return true;
        }
        if (lower.endsWith("/")) {
            return true;
        }

        String path = lower.replaceAll("\\?.*$", "");
        int lastSlash = path.lastIndexOf('/');
        int lastDot = path.lastIndexOf('.');
        return lastDot < lastSlash;
    }

    // -------------------- Datei-Verarbeitung --------------------
    private void processFile(String url) throws Exception {
        if (fileAlreadyDownloaded(url)) {
            return;
        }

        URL u = new URL(url);
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        con.setConnectTimeout(180_000);
        con.setReadTimeout(180_000);

        int status = con.getResponseCode();
        if (status >= 400) {
            Message msg = new Message("HTTP " + status + " for URL " + url, MessageLevel.ERROR);
            this.result.addMessage(msg);
            return;
        }

        String mime = con.getContentType();
        if (!isMimeAllowed(mime)) {
            result.datasetsNotParsed++;
            result.addMessage(new Message("Skipped (MIME >" + mime + "< not allowed): " + url, MessageLevel.INFO));
            return;
        }

        Path target = resolveTargetPath(url);
        Files.createDirectories(target.getParent());

        try (InputStream is = con.getInputStream()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }

        // Parser starten
        try (InputStream is = Files.newInputStream(target)) {

            // Get date from filename
            TimestampParser.setFilenameZone(this.config.getString("importer.giventime.timezone", "UTC"));
            OffsetDateTime dt = TimestampParser.parse(target);

            if (dt == null) {
                BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class);

                FileTime ft = attrs.creationTime();
                if (ft == null || ft.toMillis() == 0) {
                    // Fallback: lastModifiedTime
                    ft = attrs.lastModifiedTime();
                }

                dt = OffsetDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault());
            }

            this.sourceDescriptor = new SourceDescriptor();
            this.sourceDescriptor.setName(target.getFileName().toString());
            this.sourceDescriptor.setFileDate(dt);
            this.sourceDescriptor.setPath(target.getParent().toString());
            this.sourceDescriptor.setOrigin(url);
            this.sourceDescriptor.setCreateDateTime(LocalDateTime.now());
            this.sourceDescriptor.setModifyDateTime(LocalDateTime.now());

            ParserResult presult = this.parser.parse(is);
            this.result.addParserResult(presult);
            this.result.filesImported++;

        } catch (ParserException ex) {
            Message msg = new Message("Exception while parsing " + target.getFileName() + ": " + ex.getMessage(), MessageLevel.ERROR);
            this.result.addMessage(msg);
        }
    }

    private Path resolveTargetPath(String url) throws Exception {
        String urlPath = new URI(url).getPath();
        String relative = urlPath.replaceFirst("^/", "");
        return targetFolder.resolve(relative);
    }

    private boolean fileAlreadyDownloaded(String url) throws Exception {
        Path target = resolveTargetPath(url);
        return Files.exists(target);
    }

    private boolean isMimeAllowed(String mime) {
        if (mime == null) {
            return false;
        }
        for (String allowed : allowedMimeTypes) {
            if (allowed.equals("*")) {
                return true;
            }
            if (allowed.endsWith("/*")) {
                String prefix = allowed.substring(0, allowed.indexOf('/'));
                if (mime.startsWith(prefix)) {
                    return true;
                }
            } else if (mime.equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        return false;
    }

    // -------------------- Hilfsfunktionen aus deinem Parser --------------------
    private LocalDate resolveDate(String config) {
        config = config.trim().toLowerCase();

        if (config.startsWith("today")) {
            if (config.equals("today")) {
                return LocalDate.now();
            }
            if (config.matches("today-\\d+")) {
                int days = Integer.parseInt(config.substring("today-".length()));
                return LocalDate.now().minusDays(days);
            }
            throw new IllegalArgumentException("Invalid datepath format: " + config);
        }

        config = config.replace("/", "-");
        return LocalDate.parse(config);
    }

    private Document fetchWithRetry(String url, int retries, long delayMillis) throws Exception {
        for (int i = 0; i < retries; i++) {
            try {
                return Jsoup.connect(url)
                        .timeout(180_000)
                        .maxBodySize(0)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .get();
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (i == retries - 1) {
                    throw ex;
                }
                System.out.println("Error loading " + url + " – retry in " + delayMillis + "ms");
                Thread.sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
