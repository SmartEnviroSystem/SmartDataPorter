package de.smartdata.porter.importer;

import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.smartdata.porter.importer.descriptors.SourceDescriptor;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Base64;

/**
 * Importer for importing data from Databases. Connects to a database that is
 * connectable via JDBC driver.
 *
 * @author ffehring
 */
public class DatabaseImporter extends Importer {

    private final String db_driver;
    private final String db_dbms;
    private final String db_host;
    private final String db_port;
    private final String db_user;
    private final String db_pwd;
    private final String db_name;
    private final String db_table;
    private final String sql_query;
    private final Integer query_blocksize;
    private final Integer query_maxblocks;

    public DatabaseImporter(JsonObject config, SourceDescriptor sd) throws ImporterException {
        super(config);
        this.sourceDescriptor = sd;
        // Get data from configuration
        db_driver = this.config.getString("db.driver", null);
        db_dbms = this.config.getString("db.dbms", null);
        db_host = this.config.getString("db.host", null);
        db_port = this.config.getString("db.port", null);
        db_user = this.config.getString("db.user", null);
        db_pwd = this.config.getString("db.pwd", null);
        db_name = this.config.getString("db.name", null);
        db_table = this.config.getString("db.table", null);
        sql_query = this.config.getString("db.sql_query", null);
        query_blocksize = this.config.getInt("db.query_blocksize", 1000);
        query_maxblocks = this.config.getInt("db.query_maxblocks", Integer.MAX_VALUE);

        // Check if each needed data is there
        if (db_driver == null || db_driver.isBlank()) {
            Message msg = new Message(">db_driver< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        if (db_host == null || db_host.isBlank()) {
            Message msg = new Message(">db_host< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        if (db_port == null || db_port.isBlank()) {
            Message msg = new Message(">db_port< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        if (db_user == null || db_user.isBlank()) {
            Message msg = new Message(">db_user< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        if (db_pwd == null || db_pwd.isBlank()) {
            Message msg = new Message(">db_pwd< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        if (db_name == null || db_name.isBlank()) {
            Message msg = new Message(">db_name< is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        if ((db_table == null || db_table.isBlank()) && (sql_query == null || sql_query.isEmpty())) {
            Message msg = new Message(">db_table< (or sql_query) is missing in the configuration", MessageLevel.USER_ERROR);
            this.result.addMessage(msg);
        }
        // Optional: Hinweis, dass der Treiber benötigt wird
        // Hinweis: In Ihrem Build benötigen Sie den MariaDB JDBC Treiber (z.B. mariadb-java-client).
    }

    /**
     * Imports files over FTP connection as configured in the import
     * configuration
     *
     */
    @Override
    public void run() {
        // Check if something is missing
        if (db_host == null || db_host.isBlank() || db_port == null || db_port.isBlank()
                || db_user == null || db_user.isBlank() || db_pwd == null || db_pwd.isBlank()
                || db_name == null || db_name.isBlank()
                || ((db_table == null || db_table.isBlank()) && (sql_query == null || sql_query.isEmpty()))) {
            return;
        }

        java.sql.Connection conn = null;
        long totalRows = 0;

        try {
            // Treiber laden (MariaDB)
            try {
                Class.forName(this.db_driver);
            } catch (ClassNotFoundException e) {
                // Treiber nicht gefunden, Fehlermeldung kann folgen
                Message driverErr = new Message("JDBC driver >" + this.db_driver + "< not found. Install JDBC driver into your payara/domain/libs directory and reboot.", MessageLevel.ERROR);
                this.result.addMessage(driverErr);
            }

            // JDBC-URL
            String url = "jdbc:" + db_dbms + "://" + db_host + ":" + db_port + "/" + db_name
                    + "?useSSL=false&serverTimezone=UTC";

            System.out.println("Start importing from >" + url + "<");

            conn = java.sql.DriverManager.getConnection(url, db_user, db_pwd);

            // BasissQL
            String baseQuery = (sql_query != null && !sql_query.isEmpty())
                    ? sql_query
                    : ("SELECT * FROM " + db_table);

            // Falls kein decisionAttr gesetzt ist → Query unverändert lassen
            boolean applyDecisionFilter = this.decisionattr != null && !this.decisionattr.isBlank();

            String filteredQuery = baseQuery;

            if (applyDecisionFilter) {
                // Operator bestimmen
                String op = "DESC".equalsIgnoreCase(this.decisionorder) ? ">" : "<";

                // Prüfen, ob WHERE existiert
                String lower = baseQuery.toLowerCase();
                boolean hasWhere = lower.contains(" where ");

                filteredQuery = baseQuery
                        + (hasWhere ? " AND " : " WHERE ")
                        + this.decisionattr + " " + op + " ?";
            }

            // Pagination nur anhängen, wenn nicht vorhanden
            String lowerFiltered = filteredQuery.toLowerCase();
            boolean paginate = !lowerFiltered.contains(" limit ");

            String paginatedQuery = paginate
                    ? filteredQuery + " LIMIT ? OFFSET ?"
                    : filteredQuery;

            int currentOffset = 0;
            int i = 0;
            while (i < this.query_maxblocks) {
                i++;
                System.out.println("Try importing data from: >" + paginatedQuery + "< with:");
                try (java.sql.PreparedStatement ps = conn.prepareStatement(paginatedQuery)) {
                    int paramIndex = 1;

                    if (applyDecisionFilter) {
                        ps.setString(paramIndex++, this.decisionval);
                        System.out.println("- decisionval:" + this.decisionval);
                    }

                    if (paginate) {
                        ps.setInt(paramIndex++, query_blocksize);
                        System.out.println("- fetch.blocksize: " + query_blocksize);
                        ps.setInt(paramIndex++, currentOffset);
                        System.out.println("- currentOffset: " + currentOffset);
                    }

                    try (ResultSet rs = ps.executeQuery()) {

                        int rowsInBlock = 0;

                        while (rs.next()) {
                            rowsInBlock++;

                            JsonObjectBuilder dataSet = Json.createObjectBuilder();
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();

                            for (int c = 1; c <= colCount; c++) {
                                String colName = meta.getColumnName(c).toLowerCase();
                                Object val = rs.getObject(c);

                                if (val == null) {
                                    dataSet.addNull(colName);

                                } else if (val instanceof Number) {
                                    dataSet.add(colName, ((Number) val).doubleValue());

                                } else if (val instanceof byte[]) {
                                    // BLOB → Base64
                                    byte[] blob = (byte[]) val;
                                    String base64 = Base64.getEncoder().encodeToString(blob);
                                    dataSet.add(colName, base64);

                                } else {
                                    // normaler Text
                                    dataSet.add(colName, val.toString());
                                }

                            }
                            JsonObject ds = dataSet.build();
                            this.addDataSet(ds);
                            this.result.datasetsAvailable++;
                        }

                        if (rowsInBlock == 0) {
                            break;
                        }

                        totalRows += rowsInBlock;
                        currentOffset += query_blocksize;

                        Thread.sleep(1000);
                    }
                }
            }
            this.saveDatasets();
            System.out.println(this.result.toString());
        } catch (Exception ex) {
            Message msg = new Message("Could not import from DB >" + db_host + "<: " + ex.getLocalizedMessage(), MessageLevel.ERROR);
            this.result.addMessage(msg);
            ex.printStackTrace();
        } finally {
            // Ressourcen schließen
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                }
            }
            this.sendResultMail();
        }
    }
}
