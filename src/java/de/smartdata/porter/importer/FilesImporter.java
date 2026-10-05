package de.smartdata.porter.importer;

import de.smartfile.filehandler.DirHandler;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.helper.TimestampParser;
import de.smartdata.porter.values.converter.UrlDateFormatter;
import jakarta.json.JsonArray;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jakarta.json.JsonObject;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Importer for importing data from files.
 *
 * @author ffehring
 */
public class FilesImporter extends StreamImporter {

    protected Path source_path;
    protected Path working_path;
    protected Path inspection_path;
    protected Path archive_path;
    protected int max_files;
    private List<Pattern> allowedPatterns;

    private static final Pattern PATTERN_M = Pattern.compile("^m(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{3})");

    public FilesImporter(JsonObject config, SourceDescriptor sd) throws ImporterException {
        super(config, sd);

        String source_dir = this.config.getString("files.sourcedir", "source");
        if (source_dir == null) {
            Message msg = new Message("sourcedir is missing in the configuration, useing >source<", MessageLevel.INFO);
            this.result.addMessage(msg);
        }
        LocalDate today = LocalDate.now();
        source_dir = UrlDateFormatter.formatUrlWithDate(source_dir, today);
        String working_dir = this.config.getString("files.workingdir", null);
        working_dir = UrlDateFormatter.formatUrlWithDate(working_dir, today);
        String inspection_dir = this.config.getString("files.inspectiondir", "inspection");
        inspection_dir = UrlDateFormatter.formatUrlWithDate(inspection_dir, today);
        String archive_dir = this.config.getString("files.archivedir", null);
        archive_dir = UrlDateFormatter.formatUrlWithDate(archive_dir, today);
        this.max_files = this.config.getInt("files.max_files", 0);

        this.source_path = Paths.get(source_dir);
        if (!Files.exists(this.source_path)) {
            Message msg = new Message("sourcedir >" + this.source_path + "< does not exists.", MessageLevel.INFO);
            this.result.addMessage(msg);
        }
        if (working_dir != null) {
            try {
                this.working_path = Paths.get(working_dir);
                Files.createDirectories(this.working_path);
                Message msg = new Message("workingdir is missing in the configuration, useing >working<", MessageLevel.INFO);
                this.result.addMessage(msg);
            } catch (IOException ex) {
                Message msg = new Message("Could not create working dir >" + working_dir + "<: " + ex.getLocalizedMessage(), MessageLevel.ERROR);
                this.result.addMessage(msg);
            }
        }
        if (inspection_dir != null) {
            try {
                this.inspection_path = Paths.get(inspection_dir);
                Files.createDirectories(this.inspection_path);
                Message msg = new Message("Files with error will be moved to >" + this.inspection_path.toAbsolutePath() + "< You can configure this with the option >files.inspection_path<", MessageLevel.INFO);
                this.result.addMessage(msg);
            } catch (IOException ex) {
                Message msg = new Message("Could not create inspection dir >" + archive_dir + "<: " + ex.getLocalizedMessage(), MessageLevel.ERROR);
                this.result.addMessage(msg);
            }
        }

        if (archive_dir != null) {
            try {
                this.archive_path = Paths.get(archive_dir);
                Files.createDirectories(this.archive_path);
                Message msg = new Message("archivedir configured. Sucsessfull processed files will be moved to >" + archive_path.toAbsolutePath() + "<", MessageLevel.INFO);
                this.result.addMessage(msg);
            } catch (IOException ex) {
                Message msg = new Message("Could not create archive dir >" + archive_dir + "<: " + ex.getLocalizedMessage(), MessageLevel.ERROR);
                this.result.addMessage(msg);
            }
        }

        JsonArray extArr = this.config.getJsonArray("files.extensions");
        if (extArr != null) {
            this.allowedPatterns = extArr.stream()
                    .map(v -> v.toString().replace("\"", "").toLowerCase())
                    .map(this::wildcardToRegex)
                    .map(Pattern::compile)
                    .toList();
        } else {
            this.allowedPatterns = null; // keine Einschränkung
        }

    }

    @Override
    public void run() {
        try {
            Map<Path, Path> zipFilesMap = null;

            if (this.working_path != null) {
                DirHandler.clean(working_path, 0);
                List<String> errors = DirHandler.move(source_path, working_path, 5);
                if (!errors.isEmpty()) {
                    for (String error : errors) {
                        Message msg = new Message(error, MessageLevel.ERROR);
                        Logger.addDebugMessage(msg);
                        this.result.addMessage(msg);
                    }
                    return;
                }
                zipFilesMap = DirHandler.unzipFiles(working_path);
            }

            Path findpath = (this.working_path != null) ? this.working_path : this.source_path;
            if (!Files.exists(findpath)) {
                Message msg = new Message("Path to files does NOT exist: " + findpath.toAbsolutePath(), MessageLevel.ERROR);
                Logger.addDebugMessage(msg);
                return;
            }

            // ---------------------------------------------------------
            // Dateien einlesen und mit Timestamp versehen
            // ---------------------------------------------------------
            record FileEntry(Path path, OffsetDateTime time) {

            }

            List<FileEntry> entries = new ArrayList<>();
            try (Stream<Path> stream = Files.list(findpath)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(this::isAllowedExtension)
                        .forEach(p -> {

                            try {
                                TimestampParser.setFilenameZone(this.config.getString("importer.giventime.timezone", "UTC"));
                                OffsetDateTime odt;

                                try {
                                    odt = TimestampParser.parse(p);
                                    if (odt == null) {
                                        odt = Files.getLastModifiedTime(p)
                                                .toInstant()
                                                .atZone(ZoneId.systemDefault())
                                                .toOffsetDateTime();
                                    }
                                } catch (Exception ex) {
                                    Logger.addDebugMessage(new Message(
                                            "TimestampParser failed for " + p + ", using lastModified instead. Reason: " + ex.getMessage(),
                                            MessageLevel.WARNING
                                    ));
                                    odt = Files.getLastModifiedTime(p)
                                            .toInstant()
                                            .atZone(ZoneId.systemDefault())
                                            .toOffsetDateTime();
                                }

                                entries.add(new FileEntry(p, odt));
                            } catch (Exception e) {
                                Logger.addDebugMessage(new Message(
                                        "Error reading file time for " + p + ": " + e.getMessage(),
                                        MessageLevel.ERROR
                                ));
                            }
                        });
            }

            // Sortieren nach Timestamp
            long sortStart = System.nanoTime();
            entries.sort(Comparator.comparing(FileEntry::time));

            long sortDuration = System.nanoTime() - sortStart;
            Logger.addMessage(new Message(
                    "Sorting files took " + (sortDuration / 1_000_000) + " ms",
                    MessageLevel.INFO
            ));

            if (entries.isEmpty()) {
                Message msg = new Message("There are no files to import in >" + source_path.toAbsolutePath() + "<", MessageLevel.WARNING);
                Logger.addDebugMessage(msg);
                this.result.addMessage(msg);
                return;
            }

            if (this.config.getBoolean("simulation", false)) {
                return;
            }

            int lastparsed = 0;
            int lastimported = 0;
            int lastignored = 0;
            int lastupdated = 0;
            int lastnotimported = 0;

            int filesmovedForInspection = 0;

            // ---------------------------------------------------------
            // Dateien in sortierter Reihenfolge importieren
            // ---------------------------------------------------------
            for (FileEntry entry : entries) {
                Path file = entry.path();
                long fileStart = System.nanoTime();

                String fileerror = "";
                this.result.filesAvailable++;
                String filename = file.getFileName().toString();

                if (this.sourceDescriptor == null) {
                    this.sourceDescriptor = new SourceDescriptor();
                }
                this.sourceDescriptor.setName(filename);
                this.sourceDescriptor.setPath(file.getParent().toString());
                this.sourceDescriptor.setFileDate(entry.time());

                Logger.addDebugMessage(new Message("Import file >" + filename + "<", MessageLevel.INFO));

                try (InputStream is = new FileInputStream(file.toFile())) {
                    ImporterResult streamImporterResult = this.importStream(is);
                    this.saveDatasets();
                    streamImporterResult.filesImported++;

                    int sumParsed = streamImporterResult.datasetsParsed - lastparsed;
                    int sumSavehandled = (streamImporterResult.datasetsImported - lastimported
                            + streamImporterResult.datasetsIgnored - lastignored
                            + streamImporterResult.datasetsUpdated - lastupdated);

                    if (sumParsed > sumSavehandled) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Error importing >").append(filename).append("<: Only ")
                                .append(streamImporterResult.datasetsImported - lastimported)
                                .append(" datasets from ").append(sumParsed).append(" where imported.")
                                .append(System.lineSeparator())
                                .append(" Parsed               : ").append(sumParsed).append(System.lineSeparator())
                                .append(" Ignored  (by Parser) : ").append(streamImporterResult.datasetsIgnored - lastignored).append(System.lineSeparator())
                                .append(" Imported             : ").append(streamImporterResult.datasetsImported - lastimported).append(System.lineSeparator())
                                .append(" Not imported         : ").append(streamImporterResult.datasetsNotImported - lastnotimported).append(System.lineSeparator())
                                .append(" updated: ").append(streamImporterResult.datasetsUpdated - lastupdated).append(System.lineSeparator())
                                .append(" ------------------------------").append(System.lineSeparator())
                                .append(" sumSavehandled: ").append(sumSavehandled).append(System.lineSeparator())
                                .append(System.lineSeparator())
                                .append(" Messages: ");

                        for (Message curMsg : streamImporterResult.getMessages()) {
                            sb.append(System.lineSeparator()).append(curMsg.getMessage());
                        }
                        fileerror = sb.toString();
                        streamImporterResult.setMessages(new ArrayList<>());
                    }

                    lastparsed = streamImporterResult.datasetsParsed;
                    lastimported = streamImporterResult.datasetsImported;
                    lastnotimported = streamImporterResult.datasetsNotImported;
                    lastignored = streamImporterResult.datasetsIgnored;
                    lastupdated = streamImporterResult.datasetsUpdated;

                } catch (Exception ex) {
                    fileerror = "Error importing >" + filename + "<: " + ex.getMessage();
                    System.out.println(fileerror);
                    ex.printStackTrace();
                } finally {
                    long fileDuration = System.nanoTime() - fileStart;
                    Logger.addMessage(new Message(
                            "Import duration for " + filename + ": " + (fileDuration / 1_000_000) + " ms",
                            MessageLevel.INFO
                    ));
                }

                // Datei nach erfolgreichem Import ins Archiv verschieben
                if (this.archive_path != null) {
                    try {
                        Path target = this.archive_path.resolve(file.getFileName());
                        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);

                        Logger.addMessage(new Message(
                                "Moved file to archive: " + target.toAbsolutePath(),
                                MessageLevel.INFO
                        ));
                    } catch (IOException ex) {
                        Logger.addMessage(new Message(
                                "Could not move file to archive: " + file + " -> " + ex.getMessage(),
                                MessageLevel.ERROR
                        ));
                    }
                }

                // max_files optional berücksichtigen
                if (this.max_files > 0 && this.result.filesAvailable >= this.max_files) {
                    Logger.addMessage(new Message(
                            "Max files limit reached: " + this.max_files,
                            MessageLevel.INFO
                    ));
                    break;
                }
            }

            if (filesmovedForInspection > 0) {
                this.result.addMessage(
                        new Message(
                                "There where " + filesmovedForInspection + " files moved for inspection to >" + inspection_path.toAbsolutePath() + "<",
                                MessageLevel.ERROR)
                );
            }

        } catch (Exception ex) {
            System.out.println("Exception: " + ex.getLocalizedMessage());
            ex.printStackTrace();
        } finally {
            this.sendResultMail();
        }
    }

    private String wildcardToRegex(String wildcard) {
        StringBuilder sb = new StringBuilder();
        sb.append("^");

        for (char c : wildcard.toCharArray()) {
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append(".");
                    break;
                case '.':
                    sb.append("\\.");
                    break;
                default:
                    sb.append(Pattern.quote(String.valueOf(c)));
            }
        }

        sb.append("$");
        return sb.toString();
    }

    private boolean isAllowedExtension(Path p) {
        if (allowedPatterns == null) {
            return true; // keine Einschränkung
        }

        String name = p.getFileName().toString().toLowerCase();

        for (Pattern pat : allowedPatterns) {
            if (pat.matcher(name).matches()) {
                return true;
            }
        }

        return false;
    }

}
