package de.smartdata.porter.importer.parser.csv;

import org.apache.commons.csv.*;
import java.io.InputStream;
import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.ImporterException;
import de.smartdata.porter.importer.parser.Parser;
import de.smartdata.porter.importer.parser.ParserException;
import de.smartdata.porter.importer.parser.ParserResult;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Parser for csv files from labor hellkennlinien messungen.
 *
 * @author iarent
 */
public class CSVParser extends Parser {

    @Override
    public String getDescription() {
        return "Imports csv files";
    }

    @Override
    public boolean isAccepted(InputStream is, String mimetype, String filename) throws ParserException {
        //TODO implement check if parser is applicable
        return true;
    }

    @Override
    public void preParse() throws ParserException {
        // Nothing todo here
    }

    @Override
    public ParserResult parse(InputStream is) throws ParserException {
        if (this.importer.getConfig().containsKey("debugmode") && this.importer.getConfig().getBoolean("debugmode") == true) {
            Message msg = new Message("Start CSV Parser", MessageLevel.INFO);
            Logger.addMessage(msg);
            this.result.addMessage(msg);
        }
        String filename = this.importer.getSourceDescriptor().getName();
        // Get format options from configuration
        String format = this.importer.getConfig().getString("csv.format", "DEFAULT");
        String delimiterStr = this.importer.getConfig().getString("csv.delimiter", ";");
        boolean ignoreEmptySets = this.importer.getConfig().getBoolean("csv.ignoreemptysets", false);
        char delimiter = delimiterStr.charAt(0);

        // Build CSV Format options
        org.apache.commons.csv.CSVFormat.Builder csvBuilder;
        switch (format) {
            case "EXCEL":
                csvBuilder = CSVFormat.EXCEL.builder();
                break;
            default:
                csvBuilder = CSVFormat.DEFAULT.builder();
        }
        csvBuilder.setDelimiter(delimiter);

        // Get header from configuration
        JsonArray jsonHeaders = this.importer.getConfig().getJsonArray("csv.headers");
        List<String> headers = new ArrayList<>();
        if (jsonHeaders != null) {
            for (JsonValue curHeader : jsonHeaders) {
                headers.add(((JsonString) curHeader).getString());
            }
            String[] headersArr = new String[2];
            headersArr = headers.toArray(headersArr);

            csvBuilder.setHeader(headersArr);
            csvBuilder.setIgnoreHeaderCase(true);
        }

        // Create type mapping
        JsonArray csvMapping = this.importer.getConfig().getJsonArray("csv.mapping");

        if (csvMapping == null) {
            Message msg = new Message("No csv mapping given", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
            return result;
        }

        // Check if flatten-mode is active
        boolean flatten = this.importer.getConfig().getBoolean("flatten", false);
        JsonObjectBuilder flattedDataSet = Json.createObjectBuilder();

        // Get lines to skip
        int skipbeginlines = 0;
        if (this.importer.getConfig().containsKey("csv.skipbeginlines")) {
            skipbeginlines = this.importer.getConfig().getInt("csv.skipbeginlines");
        }

        FileWriter fstream;
        BufferedWriter bwriter = null;
        try {
            fstream = new FileWriter("notImported.csv");
            bwriter = new BufferedWriter(fstream);
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(CSVParser.class.getName()).log(Level.SEVERE, null, ex);
        }

        int i = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Remove disturbing chars
                line = line
                        .replaceAll("[\\p{Cntrl}\\u00A0\\u200B\\u200C\\u200D\\uFEFF]", "")
                        .trim();

                // Skip header lines
                if (skipbeginlines > 0) {
                    skipbeginlines--;
                    continue;
                }

                // Autocorrecture missing columns
                int realCols = line.split(delimiterStr).length;
                int colsDiff = headers.size() - realCols;
                if (colsDiff > 0) {
                    for (int d = 0; d < colsDiff; d++) {
                        line = line + delimiterStr;
                    }
                }

                String tempDate = null;
                String tempTime = null;

                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm"); // oder "HH:mm:ss"

                org.apache.commons.csv.CSVParser parser = org.apache.commons.csv.CSVParser.parse(line, csvBuilder.build());
                for (CSVRecord csvRecord : parser) {
                    try {
                        i++;
                        if (!flatten) {
                            this.result.datasetsAvailable++;
                        }
                        // Create record dataset
                        JsonObjectBuilder dataSet = Json.createObjectBuilder();

                        for (JsonValue curMapping : csvMapping) {
                            JsonObject curMappingObj = curMapping.asJsonObject();
                            JsonValue curCSVColumnJson = curMappingObj.get("csv_column");
                            if (curCSVColumnJson == null) {
                                Message msg = new Message(">csv_column< must be given in mapping.", MessageLevel.USER_ERROR);
                                Logger.addMessage(msg);
                                this.result.addMessage(msg);
                                continue;
                            }
                            String curCSVColumn;
                            // Get value of colum (csv contains only strings)
                            String curValueStr;
                            switch (curCSVColumnJson.getValueType()) {
                                case NUMBER:
                                    // Get value if column is identified by number
                                    curCSVColumn = curMappingObj.getInt("csv_column") + "";
                                    curValueStr = csvRecord.get(curMappingObj.getInt("csv_column"));
                                    break;
                                case STRING:
                                    // Get value if column is identified by header name
                                    curCSVColumn = curMappingObj.getString("csv_column");
                                    curValueStr = csvRecord.get(curCSVColumn);
                                    break;
                                default:
                                    curCSVColumn = curMappingObj.getString("csv_column");
                                    curValueStr = csvRecord.get(curCSVColumn);
                                    Message msg = new Message("A value of type >" + curCSVColumnJson.getValueType() + "< is not expected for csv_column.", MessageLevel.USER_ERROR);
                                    Logger.addMessage(msg);
                                    this.result.addMessage(msg);
                            }

                            String dbColumnName = curMappingObj.getString("db_column", null);
                            if (dbColumnName == null) {
                                dbColumnName = curCSVColumn;
                            }
                            String type = curMappingObj.getString("type");
                            switch (type) {
                                case "double":
                                    curValueStr = curValueStr.replace(",", ".");
                                    Double dval = null;
                                    try {
                                        dval = Double.valueOf(curValueStr);
                                    } catch (NumberFormatException ex) {
                                        Message msg = new Message("Value >" + curValueStr + "< for >" + dbColumnName + "< in row " + i + " of file >" + filename + "< is not a valid double.", MessageLevel.ERROR);
                                        Logger.addMessage(msg);
                                        this.result.addMessage(msg);
                                    }
                                    if (dval != null) {
                                        if (!flatten) {
                                            dataSet.add(dbColumnName, dval);
                                        } else {
                                            flattedDataSet.add(dbColumnName + i, dval);
                                        }
                                    }
                                    break;
                                case "int":
                                    Integer ival = null;
                                    try {
                                        ival = Integer.valueOf(curValueStr);
                                    } catch (NumberFormatException ex) {
                                        Message msg = new Message("Value >" + curValueStr + "< for >" + dbColumnName + "< in row " + i + " of file >" + filename + "< is not a valid integer.", MessageLevel.ERROR);
                                        Logger.addMessage(msg);
                                        this.result.addMessage(msg);
                                    }
                                    if (ival != null) {
                                        if (!flatten) {
                                            dataSet.add(dbColumnName, ival);
                                        } else {
                                            flattedDataSet.add(dbColumnName + i, ival);
                                        }
                                    }
                                    break;
                                case "timestamp":
                                    long epochSeconds = 0;

                                    // 1. Versuch: epoch seconds
                                    try {
                                        epochSeconds = Long.parseLong(curValueStr);
                                    } catch (NumberFormatException ex) {
                                        // 2. Versuch: ISO-8601
                                        try {
                                            String normalized = curValueStr.trim();

                                            // Falls kein 'T' vorhanden, aber ein Leerzeichen zwischen Datum und Zeit:
                                            if (normalized.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")) {
                                                normalized = normalized.replaceFirst(" ", "T");
                                            }

                                            Instant instant = Instant.parse(normalized);
                                            epochSeconds = instant.getEpochSecond();
                                        } catch (Exception ex2) {
                                            Message msg = new Message("Value >" + curValueStr + "< for >" + dbColumnName + "< in row " + i + " of file >" + filename + "< is not a valid timestamp.", MessageLevel.ERROR);
                                            Logger.addMessage(msg);
                                            this.result.addMessage(msg);
                                            break;
                                        }
                                    }

                                    // time_correcture
                                    int corHours = curMappingObj.getInt("time_correcture", 0);
                                    Instant instant = Instant.ofEpochSecond(epochSeconds).plus(corHours, ChronoUnit.HOURS);
                                    LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

                                    // Zeitspanne im ParserResult pflegen
                                    if (this.result.datasetFromDate == null || ldt.isBefore(this.result.datasetFromDate)) {
                                        this.result.datasetFromDate = ldt;
                                    }
                                    if (this.result.datasetUntilDate == null || ldt.isAfter(this.result.datasetUntilDate)) {
                                        this.result.datasetUntilDate = ldt;
                                    }

                                    String isoTimestamp = instant.toString();
                                    if (!flatten) {
                                        dataSet.add(dbColumnName, isoTimestamp);
                                    } else {
                                        flattedDataSet.add(dbColumnName + i, isoTimestamp);
                                    }
                                    break;
                                case "date":
                                    tempDate = curValueStr.trim();
                                    break;

                                case "time":
                                    tempTime = curValueStr.trim();
                                    break;

                                default:
                                    Message msg = new Message("Type >" + type + "< ist not supported by CSVParser.", MessageLevel.ERROR);
                                    Logger.addMessage(msg);
                                    this.result.addMessage(msg);
                            }
                            if (tempDate != null && tempTime != null) {
                                try {
                                    LocalDate date = LocalDate.parse(tempDate, dateFormatter);
                                    LocalTime time = LocalTime.parse(tempTime, timeFormatter);
                                    LocalDateTime ldt = LocalDateTime.of(date, time);

                                    int corHours = curMappingObj.getInt("time_correcture", 0);
                                    Instant instant = ldt.toInstant(ZoneOffset.UTC).plus(corHours, ChronoUnit.HOURS);
                                    long epochSeconds = instant.getEpochSecond();

                                    // Zeitspanne pflegen
                                    if (this.result.datasetFromDate == null || ldt.isBefore(this.result.datasetFromDate)) {
                                        this.result.datasetFromDate = ldt;
                                    }
                                    if (this.result.datasetUntilDate == null || ldt.isAfter(this.result.datasetUntilDate)) {
                                        this.result.datasetUntilDate = ldt;
                                    }

                                    String isoTimestamp = instant.toString();
                                    String tsdbColumnName = "ts"; // oder aus Mapping, falls du es dort konfigurierst

                                    if (!flatten) {
                                        dataSet.add(tsdbColumnName, isoTimestamp);
                                    } else {
                                        flattedDataSet.add(tsdbColumnName + i, isoTimestamp);
                                    }

                                } catch (Exception ex) {
                                    Message msg = new Message("Combined date/time >" + tempDate + "T" + tempTime
                                            + "< for >ts< in row " + i + " of file >" + filename
                                            + "< is not a valid timestamp.", MessageLevel.ERROR);
                                    Logger.addMessage(msg);
                                    this.result.addMessage(msg);
                                }

                                // Reset für nächste Zeile
                                tempDate = null;
                                tempTime = null;
                            }

                        }

                        // Prüfen, ob der Datensatz außer Datum/Zeit überhaupt Messwerte enthält
                        JsonObject built = dataSet.build();

                        if (ignoreEmptySets) {
                            // Entferne Zeitfelder aus der Prüfung
                            JsonObjectBuilder checkBuilder = Json.createObjectBuilder();
                            for (String key : built.keySet()) {
                                if (!key.equals("ts") && !key.equals("date") && !key.equals("time")) {
                                    checkBuilder.add(key, built.get(key));
                                }
                            }
                            JsonObject check = checkBuilder.build();
                            // Wenn keine Messwerte vorhanden → Datensatz verwerfen
                            if (check.keySet().isEmpty()) {
                                Message msg = new Message(
                                        "Dataset in row " + i + " of file >" + filename
                                        + "< contains only a timestamp/date but no measurement values and was ignored.",
                                        MessageLevel.WARNING
                                );
                                Logger.addMessage(msg);
                                this.result.addMessage(msg);

                                // Nicht importieren → nächste Zeile
                                continue;
                            }
                        }
                        if (!flatten) {
                            try {
                                this.importer.addDataSet(built);
                                this.result.datasetsParsed++;
                            } catch (ImporterException ex) {
                                Message msg = new Message(ex.getClass().getSimpleName() + ": " + ex.getLocalizedMessage(), MessageLevel.ERROR);
                                Logger.addMessage(msg);
                                this.result.addMessage(msg);
                            }
                        }
                    } catch (IllegalArgumentException ex) {
                        Message msg = new Message("Error occured working on record: " + csvRecord + " Occured error: " + ex.getLocalizedMessage(), MessageLevel.USER_ERROR);
                        Logger.addMessage(msg);
                        this.result.addMessage(msg);
                        this.result.datasetsNotParsed++;
                        try {
                            bwriter.write(csvRecord.toString());
                            bwriter.newLine();
                        } catch (IOException ex1) {
                            java.util.logging.Logger.getLogger(CSVParser.class.getName()).log(Level.SEVERE, null, ex1);
                        }
                    }
                }

            }
        } catch (IOException ex) {
            Message msg = new Message("Error occured while reading input stream: " + ex.getLocalizedMessage(), MessageLevel.USER_ERROR);
            Logger.addMessage(msg);
            this.result.addMessage(msg);
        }

        if (flatten) {
            if (i > -1) {
                this.result.datasetsAvailable++;
            }
            try {
                JsonObject dataset = flattedDataSet.build();
                this.importer.addDataSet(dataset);
                this.result.datasetsParsed++;
            } catch (ImporterException ex) {
                Message msg = new Message(ex.getClass().getSimpleName() + ": " + ex.getLocalizedMessage(), MessageLevel.ERROR);
                Logger.addMessage(msg);
                this.result.addMessage(msg);
            }
        }

        try {
            is.close();
            bwriter.close();
        } catch (IOException ex) {
            Message msg = new Message(ex.getClass().getSimpleName() + ": " + ex.getLocalizedMessage(), MessageLevel.WARNING);
            Logger.addMessage(msg);
            this.result.addMessage(msg);
        }

        return result;
    }
}
