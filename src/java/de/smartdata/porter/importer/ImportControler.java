package de.smartdata.porter.importer;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;
import jakarta.json.stream.JsonParser;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the import based on the given configuration
 *
 * @author Florian Fehring
 */
public class ImportControler {

    private List<JsonObject> importTasks = new ArrayList<>();

    public ImportControler(String configuration) {

        JsonParser parser = Json.createParser(new StringReader(configuration));
        parser.next();

        JsonValue confdata = parser.getValue();
        if (confdata != null) {
            switch (confdata.getValueType()) {
                case OBJECT:
                    this.importTasks.add(confdata.asJsonObject());
                    break;
                case ARRAY:
                    for (JsonValue curValue : confdata.asJsonArray()) {
                        if (curValue.getValueType() == ValueType.OBJECT) {
                            this.importTasks.add(curValue.asJsonObject());
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    // ---------------------------------------------------------
    // CPU MODEL DETECTION
    // ---------------------------------------------------------
    private String detectCpuModel() {
        try {
            // Windows
            String win = System.getenv("PROCESSOR_IDENTIFIER");
            if (win != null && !win.isBlank()) {
                return win;
            }
        } catch (Exception ignored) {
        }

        try {
            // Linux
            List<String> lines = Files.readAllLines(Path.of("/proc/cpuinfo"));
            for (String line : lines) {
                if (line.startsWith("model name")) {
                    return line.split(":", 2)[1].trim();
                }
            }
        } catch (Exception ignored) {
        }

        return "Unknown CPU";
    }

    // ---------------------------------------------------------
    // CPU TDP DATABASE
    // ---------------------------------------------------------
    private static final Map<String, Double> CPU_TDP_DB = Map.ofEntries(
            Map.entry("i7-8650U", 15.0),
            Map.entry("i7-8550U", 15.0),
            Map.entry("i7-9700K", 95.0),
            Map.entry("i9-9900K", 95.0),
            Map.entry("i5-8250U", 15.0),
            Map.entry("Ryzen 7 5800X", 105.0),
            Map.entry("Ryzen 5 5600X", 65.0)
    );

    private double detectCpuTdp(String cpuModel) {
        String lower = cpuModel.toLowerCase();
        for (String key : CPU_TDP_DB.keySet()) {
            if (lower.contains(key.toLowerCase())) {
                return CPU_TDP_DB.get(key);
            }
        }
        return 65.0; // fallback
    }

    // ---------------------------------------------------------
    // RUN IMPORT
    // ---------------------------------------------------------
    public ImporterResult run() throws ImportControlerException {

        // CPU TIME START
//        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
//        boolean cpuTimeSupported = bean.isCurrentThreadCpuTimeSupported();
//        long cpuStart = cpuTimeSupported ? bean.getCurrentThreadCpuTime() : 0;
//        long wallStart = System.nanoTime();
//
//        // CPU MODEL + TDP
//        String cpuModel = detectCpuModel();
//        double cpuTdpWatt = detectCpuTdp(cpuModel);
//        Logger.addMessage(new Message(
//                "Detected CPU: " + cpuModel + " (TDP ≈ " + cpuTdpWatt + " W)",
//                MessageLevel.INFO
//        ));
        ImporterResult ir = new ImporterResult();

        for (JsonObject curConf : this.importTasks) {
            try {
                if (!curConf.containsKey("importer")) {
                    Message msg = new Message("There is no importer defined in import configuration.",
                            MessageLevel.USER_ERROR);
                    Logger.addMessage(msg);
                    ir.addMessage(msg);
                    continue;
                }

                String importer = curConf.getString("importer");
                SourceDescriptor sd = new SourceDescriptor();
                Importer imp = null;

                switch (importer) {
                    case "FTPImporter":
                        sd.setName("FTPImporter");
                        imp = new FTPImporter(curConf, sd);
                        break;
                    case "HTTPImporter":
                        sd.setName("HTTPImporter");
                        imp = new HTTPImporter(curConf, sd);
                        break;
                    case "WebCrawlerImporter":
                        sd.setName("WebCrawlerImporter");
                        imp = new WebCrawlerImporter(curConf, sd);
                        break;
                    case "RESTImporter":
                        sd.setName("RESTImporter");
                        imp = new RESTImporter(curConf, sd);
                        break;
                    case "FilesImporter":
                        sd.setName("FilesImporter");
                        imp = new FilesImporter(curConf, sd);
                        break;
                    case "RequestImporter":
                        sd.setName("RequestImporter");
                        imp = new RequestImporter(curConf, sd);
                        break;
                    case "DatabaseImporter":
                        sd.setName("DatabaseImporter");
                        imp = new DatabaseImporter(curConf, sd);
                        break;
                }

                if (imp == null) {
                    Message msg = new Message("Importer >" + importer + "< does not exists.",
                            MessageLevel.USER_ERROR);
                    Logger.addMessage(msg);
                    ir.addMessage(msg);
                    continue;
                }

                if (curConf.getBoolean("usethreading", false)) {
                    Thread importthread = new Thread(imp);
                    importthread.start();
                    Message msg = new Message("Importer >" + importer + "< started as background thread. Result will be send per mail.",
                            MessageLevel.INFO);
                    ir.addMessage(msg);
                } else {
                    imp.run();
                    ir.merge(imp.getResult());
                }

            } catch (ImporterException ex) {
                throw new ImportControlerException("Import could not be startet: " + ex.getLocalizedMessage());
            }
        }

//        // CPU TIME END
//        long cpuEnd = cpuTimeSupported ? bean.getCurrentThreadCpuTime() : 0;
//
        //// CPU-Zeit in Sekunden
//        double cpuSeconds = cpuTimeSupported
//                ? (cpuEnd - cpuStart) / 1_000_000_000.0
//                : 0;
//
//// Energieverbrauch (Joule)
//        double usedEnergyJoule = cpuSeconds * cpuTdpWatt;
//
//// Reale Zeit messen (für Watt)
//        double wallSeconds = (System.nanoTime() - wallStart) / 1_000_000_000.0;
//
//// Durchschnittliche Leistung (Watt)
//        double usedPowerWatt = wallSeconds > 0
//                ? usedEnergyJoule / wallSeconds
//                : 0;
//
//// Ergebnis speichern
//        ir.usedPowerWatt = usedPowerWatt;
//        ir.usedEnergyJoule = usedEnergyJoule;

        return ir;
    }
}
