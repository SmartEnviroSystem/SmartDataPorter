package de.smartdata.porter.importer;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import de.smartdata.porter.importer.helper.TimestampParser;
import de.smartdata.porter.importer.parser.ParserException;
import de.smartdata.porter.importer.parser.ParserResult;
import de.smartdata.porter.values.converter.UrlDateFormatter;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RESTImporter extends StreamImporter {

    private String baseUrl;
    private final List<StepConfig> steps;

    private final String authType;      // "basic", "bearer", null
    private final String username;
    private final String password;
    private final String bearerToken;
    private final long requestDelayMs;

    public RESTImporter(JsonObject config, SourceDescriptor sd) throws ImporterException {
        super(config, sd);

        this.baseUrl = config.getString("rest.baseurl", null);
        if (this.baseUrl == null) {
            this.result.addMessage(new Message(">baseurl< missing", MessageLevel.USER_ERROR));
            throw new ImporterException("Missing baseurl");
        }

        LocalDate today = LocalDate.now();
        this.baseUrl = UrlDateFormatter.formatUrlWithDate(this.baseUrl, today);

        // Auth
        this.authType = config.getString("rest.auth", null); // "basic" oder "bearer"
        this.username = config.getString("rest.user", null);
        this.password = config.getString("rest.pwd", null);
        this.bearerToken = config.getString("rest.token", null);

        JsonArray stepsArray = config.getJsonArray("rest.steps");
        if (stepsArray == null || stepsArray.isEmpty()) {
            this.result.addMessage(new Message(">steps< missing or empty", MessageLevel.USER_ERROR));
            throw new ImporterException("Missing steps");
        }
        this.steps = parseSteps(stepsArray);

        // Delay
        this.requestDelayMs = config.getJsonNumber("rest.delay") != null
                ? config.getJsonNumber("rest.delay").longValue()
                : 0;
    }

    @Override
    public void run() {
        try {
            List<Map<String, String>> contexts = new ArrayList<>();
            Map<String, String> initial = new HashMap<>();
            initial.put("base", baseUrl);
            contexts.add(initial);

            this.parser.preParse();

            for (StepConfig step : steps) {
                contexts = executeStep(step, contexts);
            }
            this.saveDatasets();
            System.out.println("Result: " + this.result.toString());
            this.sendResultMail();

        } catch (Exception ex) {
            this.result.addMessage(new Message("RESTImporter error: " + ex.getMessage(), MessageLevel.ERROR));
            ex.printStackTrace();
        }
    }

    // ------------------------------------------------------------
    // Step parsing
    // ------------------------------------------------------------
    private List<StepConfig> parseSteps(JsonArray arr) {
        List<StepConfig> list = new ArrayList<>();
        for (JsonValue v : arr) {
            JsonObject o = v.asJsonObject();
            StepConfig sc = new StepConfig();

            // Standard-Felder
            sc.url = o.getString("url", null);

            LocalDate today = LocalDate.now();
            sc.url = UrlDateFormatter.formatUrlWithDate(sc.url, today);
            sc.extract = o.getString("extract", null);
            sc.as = o.getString("as", null);
            sc.download = o.getBoolean("download", false);
            sc.targetdir = o.getString("targetdir", null);

            // Neue Funktion: param.* auslesen
            Map<String, String> params = new HashMap<>();
            for (String key : o.keySet()) {
                if (key.startsWith("param.")) {
                    // Name hinter "param."
                    String paramName = key.substring("param.".length());
                    String value = o.getString(key, null);
                    params.put(paramName, value);
                }
            }
            sc.params = params;

            list.add(sc);
        }
        return list;
    }

    // ------------------------------------------------------------
    // Step execution
    // ------------------------------------------------------------
    private List<Map<String, String>> executeStep(StepConfig step, List<Map<String, String>> contexts) throws Exception {
        Message msg = new Message("executeStep with " + step.url, MessageLevel.INFO);
        Logger.addDebugMessage(msg);

        List<Map<String, String>> next = new ArrayList<>();

        for (Map<String, String> ctx : contexts) {

            // Parameter-Platzhalter anhand des aktuellen Kontextes ersetzen
            Map<String, String> resolvedParams = new HashMap<>();
            for (Map.Entry<String, String> e : step.params.entrySet()) {
                String key = e.getKey();
                String rawValue = e.getValue();

                // Platzhalter ersetzen
                String filledValue = fillTemplate(rawValue, ctx);

                resolvedParams.put(key, filledValue);
            }

            if (this.requestDelayMs > 0) {
                try {
                    Thread.sleep(this.requestDelayMs);
                } catch (InterruptedException ignored) {
                }
            }

            String url = fillTemplate(step.url, ctx);

            // Parameter aus StepConfig anhängen
            url = appendParams(url, resolvedParams);

            // ------------------------------------------------------------
            // DOWNLOAD STEP
            // ------------------------------------------------------------
            if (step.download) {
                String targetDir = step.targetdir != null ? fillTemplate(step.targetdir, ctx) : ".";
                LocalDate today = LocalDate.now();
                targetDir = UrlDateFormatter.formatUrlWithDate(targetDir, today);

                Path file = downloadFile(url, targetDir);

                if (file == null) {
                    continue;
                }

                Message msgDl = new Message("Downloaded from: " + url + " to: " + file.toAbsolutePath(), MessageLevel.INFO);
                Logger.addDebugMessage(msgDl);

                runParser(file, url);
                continue;
            }

            // ------------------------------------------------------------
            // PAGINATION STEP
            // ------------------------------------------------------------
            int offset = 0;
            int limit = 100;
            int maxpages = 0;

            int page = 0;
            while (maxpages == 0 || page < maxpages) {
                page++;

                if (this.requestDelayMs > 0) {
                    try {
                        Thread.sleep(this.requestDelayMs);
                    } catch (InterruptedException ignored) {
                    }
                }

                String pagedUrl = url + (url.contains("?") ? "&" : "?")
                        + "limit=" + limit + "&offset=" + offset;

                JsonValue json = fetchJson(pagedUrl);
                if (json == null) {
                    break;
                }

                if (step.extract == null || step.as == null) {
                    next.add(ctx);
                    break;
                }

                List<String> extracted = extractValues(json, step.extract);

                if (extracted.isEmpty()) {
                    break;
                }

                for (String val : extracted) {
                    Map<String, String> newCtx = new HashMap<>(ctx);
                    newCtx.put(step.as, val);
                    next.add(newCtx);
                }

                offset += limit;
            }
        }

        return next;
    }

    private String appendParams(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }

        StringBuilder sb = new StringBuilder(url);

        boolean hasQuery = url.contains("?");
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();

            sb.append(hasQuery ? "&" : "?");
            hasQuery = true;

            sb.append(key).append("=").append(val);
        }

        return sb.toString();
    }

    // ------------------------------------------------------------
    // Template replacement
    // ------------------------------------------------------------
    private String fillTemplate(String template, Map<String, String> ctx) {
        if (template == null) {
            return null;
        }

        String result = template;

        Matcher m = Pattern.compile("\\{([^}]+)}").matcher(template);
        while (m.find()) {
            String var = m.group(1);

            // Datums-Platzhalter erkennen: yyyy, yyyy-1, MM, MM-4, dd, dd-1 ...
            if (var.matches("(yyyy|MM|dd)(-[0-9]+)?")) {
                // Keine Warnung, einfach überspringen
                continue;
            }

            if (!ctx.containsKey(var)) {
                Message warn = new Message(
                        "Undefined variable {" + var + "} in template: " + template,
                        MessageLevel.WARNING
                );
                this.result.addMessage(warn);
                Logger.addMessage(warn);
                continue;
            }

            result = result.replace("{" + var + "}", ctx.get(var));
        }

        return result;
    }

    // ------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------
    private HttpURLConnection openConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        if ("basic".equalsIgnoreCase(authType) && username != null && password != null) {
            String authString = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(authString.getBytes());
            con.setRequestProperty("Authorization", "Basic " + encoded);
        }

        if ("bearer".equalsIgnoreCase(authType) && bearerToken != null) {
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }

        return con;
    }

    private JsonValue fetchJson(String urlStr) {
//        System.out.println("fetchJson() " + urlStr);
        try {
            HttpURLConnection con = openConnection(urlStr);
            int status = con.getResponseCode();

            if (status >= 400) {
                String msg = "HTTP " + status + " for URL " + urlStr;

                // Fehlerbody ausgeben
                try (InputStream es = con.getErrorStream()) {
                    if (es != null) {
                        String errBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                        System.out.println("Stream error: " + errBody);
                    }
                }

                this.result.addMessage(new Message(msg, MessageLevel.ERROR));
                return null;
            }

            // Body als String lesen
            String body;
            try (InputStream is = con.getInputStream()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            // JSON parsen
            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))) {
                JsonValue val = reader.readValue();
                return val;
            }

        } catch (Exception ex) {
            Message msg = new Message("Exception for URL " + urlStr + ": " + ex.getMessage(), MessageLevel.ERROR);
            this.result.addMessage(msg);
            Logger.addMessage(msg);
            return null;
        }
    }

    private Path downloadFile(String urlStr, String targetDir) {
//        System.out.println("downloadFile " + urlStr);
        try {
            HttpURLConnection con = openConnection(urlStr);
            int status = con.getResponseCode();

            if (status >= 400) {
                Message msg = new Message("HTTP " + status + " for URL " + urlStr, MessageLevel.ERROR);
                this.result.addMessage(msg);
                Logger.addMessage(msg);
                return null;
            }

            Path dir = Paths.get(targetDir);
            Files.createDirectories(dir);

            String rawFilename = Paths.get(new URL(urlStr).getPath()).getFileName().toString();
            String filename = sanitizeFilename(rawFilename);
            Path file = dir.resolve(filename);

            if (Files.exists(file)) {
                this.result.filesAvailable++;
                this.result.filesIgnored++;
                return null;
            }

            try (InputStream is = con.getInputStream()) {
                Files.copy(is, file);
            }

            return file;

        } catch (Exception ex) {
            Message msg = new Message("Exception while downloading " + urlStr + ": " + ex.getMessage(), MessageLevel.ERROR);
            this.result.addMessage(msg);
            Logger.addMessage(msg);
            return null;
        }
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "P");
    }

    // ------------------------------------------------------------
    // JSON extraction (simple JSONPath-like)
    // ------------------------------------------------------------
    private List<String> extractValues(JsonValue json, String expr) {
        List<String> result = new ArrayList<>();

        // 1) Root ist ein Array → "[]"
        if (expr.equals("[]")) {
            JsonArray arr = json.asJsonArray();
            for (JsonValue v : arr) {
                if (v.getValueType() == JsonValue.ValueType.STRING) {
                    result.add(((JsonString) v).getString());
                } else {
                    result.add(v.toString().replace("\"", ""));
                }
            }
            return result;
        }

        // 2) Root-Array mit Feld → "[].field"
        if (expr.startsWith("[].")) {
            String field = expr.substring(3);
            JsonArray arr = json.asJsonArray();
            for (JsonValue v : arr) {
                JsonObject o = v.asJsonObject();
                JsonValue fv = o.get(field);
                if (fv.getValueType() == JsonValue.ValueType.STRING) {
                    result.add(((JsonString) fv).getString());
                } else {
                    result.add(fv.toString().replace("\"", ""));
                }
            }
            return result;
        }

        // 3) Objekt → Array → "field[]"
        if (expr.endsWith("[]") && !expr.contains(".")) {
            String field = expr.substring(0, expr.length() - 2);
            JsonObject obj = json.asJsonObject();
            JsonArray arr = obj.getJsonArray(field);

            for (JsonValue v : arr) {
                if (v.getValueType() == JsonValue.ValueType.STRING) {
                    result.add(((JsonString) v).getString());
                } else {
                    result.add(v.toString().replace("\"", ""));
                }
            }
            return result;
        }

        // 4) Objekt → Array → Feld extrahieren → "field[].subfield"
        if (expr.contains("[].")) {
            String[] parts = expr.split("\\[]\\.");
            String arrayField = parts[0];
            String subField = parts[1];

            JsonObject obj = json.asJsonObject();
            JsonArray arr = obj.getJsonArray(arrayField);

            for (JsonValue v : arr) {
                JsonObject o = v.asJsonObject();
                JsonValue fv = o.get(subField);

                if (fv.getValueType() == JsonValue.ValueType.STRING) {
                    result.add(((JsonString) fv).getString());
                } else {
                    result.add(fv.toString().replace("\"", ""));
                }
            }
            return result;
        }

        throw new IllegalArgumentException("Unsupported extract expression: " + expr);
    }

    // ------------------------------------------------------------
    // Parser integration
    // ------------------------------------------------------------
    private void runParser(Path file, String url) throws IOException, ImporterException {
        this.result.filesAvailable++;

        SourceDescriptor fileDesc = new SourceDescriptor();
        String sanitized = sanitizeFilename(file.getFileName().toString());
        fileDesc.setName(sanitized);
        fileDesc.setPath(file.getParent().toString());
        fileDesc.setOrigin(url);

        TimestampParser.setFilenameZone(this.config.getString("importer.giventime.timezone", "UTC"));
        OffsetDateTime ts = TimestampParser.parse(file);

        if (ts != null) {
            fileDesc.setFileDate(ts);
            fileDesc.setCreateDateTime(ts.toLocalDateTime());
            fileDesc.setModifyDateTime(ts.toLocalDateTime());
        } else {
            // Fallback: Use now
            LocalDateTime fallback = LocalDateTime.now();
            fileDesc.setCreateDateTime(fallback);
            fileDesc.setModifyDateTime(fallback);
        }

        this.sourceDescriptor = fileDesc;

        try (InputStream is = Files.newInputStream(file)) {
            ParserResult presult = this.parser.parse(is);
            this.result.addParserResult(presult);
        } catch (ParserException ex) {
            Message msg = new Message("Exception while parsing " + file.getFileName().toString() + ": " + ex.getMessage(), MessageLevel.ERROR);
            this.result.addMessage(msg);
            Logger.addMessage(msg);
            ex.printStackTrace();
        }

        this.result.filesImported++;
    }

    // ------------------------------------------------------------
    // StepConfig inner class
    // ------------------------------------------------------------
    private static class StepConfig {

        String url;
        Map<String, String> params = new HashMap<>();
        String extract;
        String as;
        boolean download;
        String targetdir;
    }
}
