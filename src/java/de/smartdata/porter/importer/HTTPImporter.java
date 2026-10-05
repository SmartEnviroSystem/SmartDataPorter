package de.smartdata.porter.importer;

import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import de.smartdata.porter.values.converter.UrlDateFormatter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jakarta.json.JsonObject;
import java.net.HttpURLConnection;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Importer for importing files that are stored on a HTTP server. Downloads the
 * HTTP files to the working directory and than delivers them to the
 * super.runImport() method of FilesImporter.
 *
 * Expectations to the configuration file: http_url = URL of the file to
 * download, may include parameters
 *
 * @author ffehring
 */
public class HTTPImporter extends StreamImporter {

    private String http_url;

    public HTTPImporter(JsonObject config, SourceDescriptor sd) throws ImporterException {
        super(config, sd);
        // Get data from configuration
        this.http_url = this.config.getString("url", null);

        // Check if each needed data is there
        if (http_url == null) {
            Message msg = new Message(">url< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        } else {
            LocalDate today = LocalDate.now();
            this.http_url = UrlDateFormatter.formatUrlWithDate(this.http_url, today);
        }
    }

    @Override
    public void run() {
        if (http_url == null) {
            return;
        }
        // Fetching document from http server
        HttpURLConnection con = null;
        try {
            String urlStr = this.http_url;

            URL url = new URL(urlStr);
            con = (HttpURLConnection) url.openConnection();

            // If authentication is configured
            if (this.config.getString("password", null) != null && this.config.getString("username", null) != null) {
                String authString = this.config.getString("username") + ":" + this.config.getString("password");
                String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                con.setRequestProperty("Authorization", "Basic " + encodedAuth);
            }

            try (InputStream is = con.getInputStream()) {
                // Check if file is compressed
                if (this.config.getBoolean("download", false) || this.http_url.endsWith(".zip") || this.http_url.endsWith(".gz")) {
                    String source_dir = this.config.getString("sourcedir", "source");

                    Message msg = new Message("file under >" + http_url
                            + "< downloading to >"
                            + source_dir + "<.", MessageLevel.INFO);
                    this.result.addMessage(msg);

                    // Save stream to file
                    Path outputDir = Paths.get(source_dir);
                    Path outputFile = Paths.get(url.getFile()).getFileName();
                    Path downloadFile = outputDir.resolve(outputFile);
                    // Create directories
                    Files.createDirectories(downloadFile.getParent());
                    // Copy stream into file
                    Files.copy(is, downloadFile, StandardCopyOption.REPLACE_EXISTING);
                    // Wait 5 seconds before call FilesImporter because Filesimporter takes only files that are min 5 seconds old to avoid write-in-progress errors
                    try {
                        Thread.sleep(2 * 5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    String filename = outputFile.toString().replace("/", "");
                    this.sourceDescriptor.setName(filename);
                    this.sourceDescriptor.setPath("/" + downloadFile.getParent().toString());
                    this.sourceDescriptor.setCreateDateTime(LocalDateTime.ofEpochSecond(con.getDate(), 0, ZoneOffset.UTC));
                    this.sourceDescriptor.setModifyDateTime(LocalDateTime.ofEpochSecond(con.getLastModified(), 0, ZoneOffset.UTC));
                    // Delegate to files importer
                    FilesImporter fi = new FilesImporter(this.config, this.sourceDescriptor);
                    fi.run();
                    this.result.merge(fi.result);
                } else {
                    this.result.filesAvailable++;
                    this.sourceDescriptor.setName(url.getFile());
                    this.sourceDescriptor.setPath(url.getPath());
                    this.sourceDescriptor.setCreateDateTime(LocalDateTime.ofEpochSecond(con.getDate(), 0, ZoneOffset.UTC));
                    this.sourceDescriptor.setModifyDateTime(LocalDateTime.ofEpochSecond(con.getLastModified(), 0, ZoneOffset.UTC));
                    super.importStream(is);
                    this.result.filesImported++;
                }
            }
        } catch (MalformedURLException ex) {
            Message msg = new Message("url >" + http_url + "< is not correct.", MessageLevel.ERROR);
            this.result.addMessage(msg);
        } catch (IOException ex) {
            Message msg = new Message("Could not import from url >" + http_url + "< IOException: " + ex.getLocalizedMessage(), MessageLevel.ERROR);
            ex.printStackTrace();
            this.result.addMessage(msg);
        } catch (ImporterException ex) {
            Message msg = new Message("Could not import from url >" + http_url + "< ImporterException: " + ex.getLocalizedMessage(), MessageLevel.ERROR);
            this.result.addMessage(msg);
            ex.printStackTrace();
        } finally {
            // StreamImporter sends mail
        }
    }
}
