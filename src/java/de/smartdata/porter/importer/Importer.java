package de.smartdata.porter.importer;

import de.bytefish.pgbulkinsert.PgBulkInsert;
import de.bytefish.pgbulkinsert.util.PostgreSqlUtils;
import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.fhbielefeld.scl.rest.util.WebTargetCreator;
import de.smart.dataaccessor.SmartDataAccessor;
import de.smart.dataaccessor.SmartDataAccessorException;
import de.smartdata.porter.config.Configuration;
import de.smartdata.porter.importer.dataset.DataSet;
import de.smartdata.porter.importer.descriptors.DataSetMapping;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonReader;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Class for reciving values, order them to the requesting devices and writing
 * the data into database.
 *
 * @author ffehring
 */
public abstract class Importer implements Runnable {

    protected JsonObject config;
    protected SourceDescriptor sourceDescriptor;
    protected String collection;
    protected String storage;
    protected DataSetMapping datasetmapping;
    protected final Collection<DataSet> datasetsbulk = new ArrayList<>();
    protected final List<JsonObject> datasets = new ArrayList<>();
    protected final ImporterResult result = new ImporterResult();
    protected String decisionattr;
    protected String decisionval;
    protected String decisionorder;
    private SmartDataAccessor accessor;
    private long beforeRun;

    public Importer(JsonObject config) throws ImporterException {
        this.beforeRun = System.nanoTime();
        this.config = config;
        this.collection = this.config.getString("collection", null);
        this.storage = this.config.getString("storage", null);
        this.result.setImporter(this);
        this.result.setTarget(storage + "/" + collection);
        if (collection == null) {
            Message msg = new Message(">collection< is missing in configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
            this.result.datasetsImported = 0;
        }
        if (storage == null) {
            Message msg = new Message(">storage< is missing in configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
            this.result.datasetsImported = 0;
        }

        if (collection != null && storage != null) {
            datasetmapping = new DataSetMapping<DataSet>(storage, collection);
        }

        this.decisionval = this.getDecisionValue();

        String serverpath = this.config.getString("server", "http://127.0.0.1:8080/");
        String smartdata = this.config.getString("smartdata", "SmartData");
        this.accessor = new SmartDataAccessor(serverpath + "/" + smartdata);

    }

    /**
     * Returns the configuration of this import
     *
     * @return
     */
    public JsonObject getConfig() {
        return this.config;
    }

    /**
     * Returns the descriptor of the source that should be imported. This usualy
     * contains informations about filename, date, etc.
     *
     * @return SourceDescriptor with source information
     */
    public SourceDescriptor getSourceDescriptor() {
        return sourceDescriptor;
    }

    public void setDatasetMapping(DataSetMapping mapping) {
        this.datasetmapping = mapping;
    }

    /**
     * Returns the datasetmapping of dataset attributes to columns
     *
     * @return
     */
    public DataSetMapping getDatasetMapping() {
        return datasetmapping;
    }

    /**
     * Returns the collections name where the data should be imported to
     *
     * @param sourcename Source of data
     * @return collection name
     */
    public String getDatasourceMapping(String sourcename) {
        // Start count time
//        long st = System.currentTimeMillis();

        JsonArray filemap = this.config.getJsonArray("sourcemapping");
        // If there is no sourcemapping specified
        if (filemap == null) {
            return collection;
        }
        // Search collection for file
        for (JsonValue curMappingJV : filemap) {
            JsonObject curMapping = curMappingJV.asJsonObject();
            String namepattern = curMapping.getString("namepattern", null);
            if (namepattern == null) {
                continue;
            }
            if (Pattern.matches(namepattern, sourcename)) {
                String col = curMapping.getString("collection", null);
                if (col != null) {
                    return col;
                }
            }
        }
//        // Stop count time
//        long et = System.currentTimeMillis();
//        long elapsedTime = et - st;
//        System.out.println("needet time: " + (elapsedTime/1000));

        return this.collection;
    }

    /**
     * Add Collection of DataSets Use JSON based datasets instead
     *
     * @param datasts Collection of datasets
     * @throws ImporterException
     * @deprecated
     */
    @Deprecated
    public void addDataSets(Collection<DataSet> datasts) throws ImporterException {
        this.datasetsbulk.addAll(datasts);
    }

    /**
     * Imports an single dataset. If there is an open import process, this will
     * import useing that open process. If there is no open import process, it
     * will create an process for this one dataset only.
     *
     * @param dataset Dataset to save
     * @throws ImporterException
     */
    public void addDataSet(JsonObject dataset) throws ImporterException {
        this.datasets.add(dataset);
        //Check maximum hold and than send data if exceded
        if (this.datasets.size() >= 100) {
            this.saveDatasets();
        }
    }

    /**
     * Imports an list of datasets. If there is an open import process, this
     * will import useing that open process. If there is no open import process,
     * it will create an process for all of the given datasets.
     *
     * @param datasets
     * @throws ImporterException
     */
    public void addDataSets(List<JsonObject> datasets) throws ImporterException {
        for (JsonObject curSet : datasets) {
            this.addDataSet(curSet);
        }
    }

    /**
     * Adds an datatable in form of a list (table) of maps (dataset) with
     * strings (columnnames) and objects (values)
     *
     * @param datatable Table of data to add
     * @throws ImporterException
     */
    public void addDataTable(List<Map<String, Object>> datatable) throws ImporterException {
        if (datatable.isEmpty()) {
            return;
        }
        for (Map<String, Object> curDatamap : datatable) {
            //TODO implement
            //this.addDataSet(dataset);
        }

    }

    /**
     * Saves the datasts useing the bulk mechanism Directly inserts data into
     * the database useing performant COPY-Statement
     *
     * @return
     * @throws ImporterException
     */
    protected void saveDatasetsBulk() throws ImporterException {
        String jndi = this.config.getString("jndi", null);

        try {
            InitialContext ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup(jndi);
            Connection con = ds.getConnection();

            PgBulkInsert<DataSet> bulkInsert = new PgBulkInsert<>(datasetmapping);
            bulkInsert.saveAll(PostgreSqlUtils.getPGConnection(con), datasetsbulk);
            con.commit();
            con.close();

            // Update the ONE ImporterResult
            this.result.datasetsImported += datasetsbulk.size();

        } catch (NamingException | SQLException ex) {
            Message msg = new Message("", MessageLevel.ERROR, "Bulk insert failed: " + ex.getLocalizedMessage());
            Logger.addMessage(msg);
            ImporterException dex = new ImporterException("Bulk insert failed: " + ex.getLocalizedMessage());
            dex.addSuppressed(ex);
            throw dex;
        }
    }

    public String getDecisionValue() {
        String serverpath = this.config.getString("server", "http://127.0.0.1:8080/");
        String smartdata = this.config.getString("smartdata", "SmartData");
        boolean ignoreSSL = this.config.getBoolean("ignoreSSL", false);
        this.decisionattr = this.config.getString("decisionattr", null);
        this.decisionorder = this.config.getString("decisionorder", "DESC");
        if (this.collection == null || this.decisionattr == null) {
            return null;
        }

        WebTarget target = WebTargetCreator
                .createWebTarget(serverpath, smartdata + "/smartdata/records", ignoreSSL)
                .path(collection)
                // Get only desision attribute
                .queryParam("includes", this.decisionattr)
                // Do not look at datasets where value is missing
                .queryParam("filter", this.decisionattr + ",nis")
                // Order for getting highest or lowest
                .queryParam("order", this.decisionattr + "," + this.decisionorder)
                // Need only the last value
                .queryParam("size", 1);
        if (this.storage != null) {
            target = target.queryParam("storage", this.storage);
        }

        Message getDecisionInfo = new Message("Try getting decisionvalue with: " + target.getUri(), MessageLevel.INFO);
        this.result.addMessage(getDecisionInfo);

        try (Response response = target.request(MediaType.APPLICATION_JSON).get()) {

            if (response.getStatus() != 200) {
                this.result.addMessage(new Message(
                        "Error getting decision value. HTTP Response: " + response.getStatusInfo(),
                        MessageLevel.ERROR
                ));
                return null;
            }

            String json = response.readEntity(String.class);
            JsonReader reader = Json.createReader(new StringReader(json));
            JsonObject root = reader.readObject();

            // records[] extrahieren
            JsonArray records = root.getJsonArray("records");
            if (records == null || records.isEmpty()) {
                this.result.addMessage(new Message("No records found.", MessageLevel.ERROR));
                return null;
            }
            // erstes Objekt aus records[]
            JsonObject obj = records.getJsonObject(0);
            // decisionattr auslesen
            this.decisionval = obj.getString(this.decisionattr, null);

            return this.decisionval;

        }
    }

    private <T> List<List<T>> chunk(List<T> list, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            chunks.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return chunks;
    }

    /**
     * Saves the datasets into database. Chooses the save strategy (SmartData or
     * bulk) depending on the options
     *
     * @return
     * @throws ImporterException
     */
    protected void saveDatasets() throws ImporterException {
        long beforeSave = System.nanoTime();

        String jndi = this.config.getString("jndi", null);

        if (jndi != null) {
            this.saveDatasetsBulk();
        } else {
            if (this.datasets.isEmpty() && !this.datasetsbulk.isEmpty()) {
                for (DataSet curSet : this.datasetsbulk) {
                    this.datasets.add(curSet.toJson());
                }
            }

            if (!this.datasets.isEmpty()) {
                this.saveDatasetsViaAccessor();
            } else {
                Message msg = new Message("There was no data to save.", MessageLevel.WARNING);
                this.result.addMessage(msg);
            }
        }

        long afterSave = System.nanoTime();
        this.result.saveTime += (afterSave - beforeSave) / 1_000_000;
    }

    protected void saveDatasetsViaAccessor() throws ImporterException {
        try {
            JsonObjectBuilder params = Json.createObjectBuilder()
                    .add("storage", this.storage)
                    .add("collection", this.collection);

            JsonArrayBuilder arr = Json.createArrayBuilder();
            for (JsonObject ds : this.datasets) {
                arr.add(ds);
            }

            JsonArray data = arr.build();

            JsonObjectBuilder resultBuilder = Json.createObjectBuilder();

            this.accessor.saveData(this.storage, this.collection, data, resultBuilder);

            // Update the ONE ImporterResult
            this.result.datasetsImported += data.size();
            this.datasets.clear();

        } catch (SmartDataAccessorException ex) {

            Throwable t = ex;
            while (t != null) {
                System.out.println("Cause: {}" + t.getMessage());
                t = t.getCause();
            }
            throw new ImporterException("SmartDataAccessor error: " + ex.getMessage());
        }
    }

    public ImporterResult getResult() {
        return result;
    }

    public void sendResultMail() {
        long afterRun = System.nanoTime();
        this.getResult().importTime = (afterRun - beforeRun) / 1_000_000;
        String result = this.getResult().toString();
        String recipients = this.getConfig().getString("resultReciptants", null);
        if (recipients != null) {
            List<String> recipientList = Arrays.stream(recipients.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            try {
                Configuration conf = new Configuration();
                // Get configuration
                String host = conf.getProperty("resultmail.host");
                String port = conf.getProperty("resultmail.port", "587");
                String auth = conf.getProperty("resultmail.auth", "false");
                String starttls = conf.getProperty("resultmail.starttls", "false");
                String username = conf.getProperty("resultmail.username");
                String password = conf.getProperty("resultmail.password");

                // Check configuration
                if (host == null) {
                    throw new ImportNotificationException("Cannot send mail parameter >host< is missing.");
                }
                if (username == null || password == null) {
                    throw new ImportNotificationException("Sending mails over senders without username and password is not supported.");
                }

                Properties props = new Properties();
                props.put("mail.smtp.host", host); // "smtp.example.com"
                props.put("mail.smtp.port", port); //"587"
                props.put("mail.smtp.auth", auth); // "true"
                props.put("mail.smtp.starttls.enable", starttls); // "true"

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });

                jakarta.mail.Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(conf.getProperty("resultmail.sender")));

                for (String r : recipientList) {
                    msg.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(r));
                }

                msg.setSubject(this.sourceDescriptor.getName() + " import result");
                msg.setText(result);

                Transport.send(msg);
                Message imsg = new Message("Send mail to >" + recipientList + "< done.",
                        MessageLevel.USER_ERROR);
                Logger.addMessage(imsg);
            } catch (Exception ex) {
                Message msg = new Message("Send mail to >" + recipientList + "< failed with " + ex.getClass().getSimpleName() + " Error: " + ex.getLocalizedMessage(),
                        MessageLevel.USER_ERROR);
                Logger.addMessage(msg);
                ex.printStackTrace();
            }
        } else {
            System.out.println("No mail recipients configured. Result is:");
            System.out.println(result);
        }
    }
}
