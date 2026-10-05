package de.smartdata.porter.rest;

import de.fhbielefeld.scl.logger.Logger;
import de.fhbielefeld.scl.logger.LoggerException;
import de.fhbielefeld.scl.logger.message.Message;
import de.fhbielefeld.scl.logger.message.MessageLevel;
import de.fhbielefeld.scl.rest.util.ResponseObjectBuilder;
import de.smartdata.porter.config.Configuration;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map.Entry;
import javax.naming.NamingException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST Web Service for accessing system options and informations
 *
 * @author Florian Fehring
 */
@Path("system")
@Tag(name = "System", description = "SmartDataPorter system informations and configuration")
public class SystemResource {

    @PersistenceContext(unitName = "SmartDataPorterPU")
    private EntityManager em;

    @jakarta.annotation.Resource
    private UserTransaction utx;

    /**
     * Creates a new instance of RootResource
     */
    public SystemResource() {
        // Init logging
        try {
            String moduleName = (String) new javax.naming.InitialContext().lookup("java:module/ModuleName");
            Configuration conf = new Configuration();
            Logger.getInstance("SmartDataPorter", moduleName);
            Logger.setDebugMode(Boolean.parseBoolean(conf.getProperty("debugmode")));
        } catch (LoggerException | NamingException ex) {
            System.err.println("Error init logger: " + ex.getLocalizedMessage());
        }
    }

    @GET
    @Path("config")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get configuration",
            description = "Lists all configuration options.")
    @APIResponse(
            responseCode = "200",
            description = "Objects with configuration informations",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"list\" : [ { \"name\" : \"value\"} ]}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not load configuration: Because of ... \"]}"))
    public Response getConfig() {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        // Init config
        Configuration conf = new Configuration();

        rob.add("modulname", conf.getModuleName());
        rob.add("filename", conf.getFileName());
        rob.add("propsloaded", conf.isPropsloaded());
        for (Entry<Object, Object> curEntry : conf.getAllProperties()) {
            String curKey = curEntry.getKey().toString();
            if (!curKey.startsWith("ldap_") && !curKey.equals("passwordsalt")
                    && !curKey.equals("authtokensalt")) {
                rob.add(curKey, curEntry.getValue().toString());
            }
        }
        rob.setStatus(Response.Status.OK);

        return rob.toResponse();
    }

    @GET
    @Path("info")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get system information",
            description = "Gets information about the SmartDataPorter system and environment.")
    @APIResponse(
            responseCode = "200",
            description = "Objects with system informations",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"list\" : [ { \"name\" : \"value\"} ]}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not get system informations: Because of ... \"]}"))
    public Response info() {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        // Init config
        Configuration conf = new Configuration();

        rob.add("version", "16.01.2026");
        // IP adress
        try {
            rob.add("publicip", InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException ex) {
            rob.add("publicip", "UnknwonIP Adress" + ex.getLocalizedMessage());
        }
        // MAC address
        String MAC = null;
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            NetworkInterface ntInterface = NetworkInterface.getByInetAddress(localhost);
            if (ntInterface == null) {//Raspberry
                ntInterface = NetworkInterface.getByName("eth0");
            }
            if (ntInterface == null) {//Linux Server
                ntInterface = NetworkInterface.getByName("venet0");
            }
            if (ntInterface == null) {//Raspberry-WLAN
                ntInterface = NetworkInterface.getByName("wlan0");
            }
            if (ntInterface == null) {//No HW Address Found
                throw new UnknownHostException();
            }
            byte[] adr = ntInterface.getHardwareAddress();
            MAC = String.format("%02X:%02X:%02X:%02X:%02X:%02X", adr[0], adr[1], adr[2], adr[3], adr[4], adr[5]);
            rob.add("mac", MAC);
        } catch (UnknownHostException | SocketException | NullPointerException ex) {
            rob.add("mac", "Unknon MAC: " + ex.getLocalizedMessage());
        }
        //AppID
        if (conf.getProperty("appid") != null) {
            rob.add("appid", conf.getProperty("appid"));
        } else {
            rob.add("appid", MAC);
        }
        // HTTPS support check
        try {
            URL masterURL = new URL("https://www.hsbi.de");
            final URLConnection conn = masterURL.openConnection();
            conn.setConnectTimeout(1000);
            conn.connect();
            rob.add("httpsSupported", true);
        } catch (IOException ex) {
            rob.add("httpsSupported", false);
            System.err.println("DEBUG (SystemBean): There is no https support, because of: " + ex.getLocalizedMessage());
        } catch (NoSuchMethodError ex) {
            rob.add("httpsSupported", false);
            System.err.println("DEBUG (SystemBean): There is no https support, because of missing method: " + ex.getLocalizedMessage());
        }

        // Internet conectivity check
        try {
            final URL url = new URL("http://www.hsbi.de");
            final URLConnection conn = url.openConnection();
            conn.setConnectTimeout(1000);
            conn.connect();
            rob.add("internetConnectivity", true);
        } catch (IOException ex) {
            rob.add("internetConnectivity", false);
            System.err.println("Could not connect to google.de " + ex.getLocalizedMessage());
        }

        // Check database conectivity
        if (this.em == null) {
            rob.add("databaseConnectivity", false);
        } else {
            rob.add("databaseConnectivity", true);
        }

        rob.add("systemJavaVendor", System.getProperty("java.vendor"));
        rob.add("systemJavaVersion", System.getProperty("java.version"));
        rob.add("systemName", System.getProperty("os.name"));
        String OS = System.getProperty("os.name").toLowerCase();
        if (OS.startsWith("win")) {
            rob.add("systemBase", "Windows");
        } else {
            rob.add("systemBase", "Linux");
        }
        rob.add("systemVersion", System.getProperty("os.version"));
        rob.add("systemArchitecture", System.getProperty("os.arch"));
        rob.setStatus(Response.Status.OK);

        return rob.toResponse();
    }

    @GET
    @Path("memorytest")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get heap usage",
            description = "Returns current heap usage of the JVM.")
    @APIResponse(
            responseCode = "200",
            description = "Heap usage information",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"usedHeapMB\" : 123, \"maxHeapMB\" : 512}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error message",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \"Could not retrieve heap info: ...\"]}"))
    public Response memoryTest(@Parameter(description = "If true provoke an OutOfMemoryError") @QueryParam("provokeOutOfMemory") boolean provokeOutOfMemory) {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();

            rob.add("usedHeapMB", usedMemory / (1024 * 1024));
            rob.add("maxHeapMB", maxMemory / (1024 * 1024));
            rob.setStatus(Response.Status.OK);
        } catch (Throwable t) {
            rob.addErrorMessage("Could not retrieve heap info: " + t.getLocalizedMessage());
            rob.setStatus(Response.Status.INTERNAL_SERVER_ERROR);
        }

        try {
            if (provokeOutOfMemory == true) {
                List<byte[]> memoryHog = new ArrayList<>();
                while (true) {
                    memoryHog.add(new byte[1024 * 1024]); // 1 MB
                }
            }
        } catch (Throwable t) {
            Message msg = new Message("Unkown error occured: " + t.getLocalizedMessage(), MessageLevel.ERROR);
            Logger.addMessage(msg);
            throw t;
        }

        return rob.toResponse();
    }

    @GET
    @Path("setup")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Setup the SmartDataPorter system",
            description = "Setup the SmartDataPorter system.")
    @APIResponse(
            responseCode = "200",
            description = "Objects with system informations",
            content = @Content(
                    mediaType = "application/json",
                    example = "{\"list\" : [ { \"name\" : \"value\"} ]}"
            ))
    @APIResponse(
            responseCode = "500",
            description = "Error mesage",
            content = @Content(mediaType = "application/json",
                    example = "{\"errors\" : [ \" Could not get system informations: Because of ... \"]}"))
    public Response setup() {
        ResponseObjectBuilder rob = new ResponseObjectBuilder();

        rob.setStatus(Response.Status.OK);
        return rob.toResponse();
    }

    @GET
    @Path("jdbcresources")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJdbcResources() {

        try {
            // URL zur Payara Management API
            String url = "http://localhost:4848/management/domain/resources/jdbc-resource";

            // Open connection
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");

            // Set header
            conn.setRequestProperty("X-Requested-By", "GlassFish REST HTML interface");
            conn.setRequestProperty("Accept", "application/json");

            // Basic Auth
            Configuration conf = new Configuration();
            String adminUser = conf.getProperty("payara_admin_user");
            if (adminUser != null) {
                String user = adminUser;
                String pass = conf.getProperty("payara_admin_pwd");

                String basicAuth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
                conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            }

            int status = conn.getResponseCode();

            // If managemant api is not available
            if (status != 200) {
                JsonObject msg = Json.createObjectBuilder()
                        .add("message", "No Management api available. Could not get jdbc resources")
                        .add("resources", Json.createArrayBuilder().build())
                        .build();

                return Response.ok(msg.toString()).build();
            }

            // Get response
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            // Parse JSON
            JsonObject json = Json.createReader(new StringReader(response.toString())).readObject();

            // Navigate to extraProperties.childResources
            JsonObject extraProps = json.getJsonObject("extraProperties");
            JsonObject childResources = extraProps.getJsonObject("childResources");

            // Extract keys (JNDI names)
            JsonArrayBuilder arr = Json.createArrayBuilder();
            for (String key : childResources.keySet()) {
                JsonObjectBuilder obj = Json.createObjectBuilder();
                obj.add("name", key);
                obj.add("value", key);
                arr.add(obj);
            }
            return Response.ok(arr.build().toString()).build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @Path("apps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApps() {

        try {
            String mgmtUrl = "http://localhost:4848/management/domain/applications/application";

            // Konfiguration einmal laden
            Configuration conf = new Configuration();
            String adminUser = conf.getProperty("payara_admin_user");
            String adminPwd = conf.getProperty("payara_admin_pwd");

            JsonObject mgmtJson = tryFetchJsonWithAuth(mgmtUrl, adminUser, adminPwd);
            if (mgmtJson != null) {
                return Response.ok(processManagementJson(mgmtJson).toString()).build();
            }

            // Fallback: SmartData Root prüfen (ohne Auth)
            if (isReachable("http://localhost:8080/SmartData/")) {
                JsonArray arr = Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("name", "SmartData")
                                .add("value", "SmartData")
                                .add("type", "SmartData"))
                        .build();

                return Response.ok(arr.toString()).build();
            }

            return Response.ok("[]").build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    private JsonObject tryFetchJsonWithAuth(String url, String user, String pass) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            // Pflicht-Header für Payara/GlassFish Management API
            conn.setRequestProperty("X-Requested-By", "GlassFish REST HTML interface");
            conn.setRequestProperty("Accept", "application/json");

            // Basic Auth nur setzen, wenn User vorhanden
            if (user != null && pass != null) {
                String basicAuth = Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            }

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                return Json.createReader(reader).readObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if the given url is reachable
     *
     * @param url URL to check reachability
     * @return true if url is reachable, false otherwise
     */
    private boolean isReachable(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int code = conn.getResponseCode();
            return code == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonArray processManagementJson(JsonObject json) {
        JsonObject extraProps = json.getJsonObject("extraProperties");
        JsonObject childResources = extraProps.getJsonObject("childResources");

        JsonArrayBuilder arr = Json.createArrayBuilder();

        for (String key : childResources.keySet()) {
            JsonObjectBuilder obj = Json.createObjectBuilder();
            obj.add("name", key);
            obj.add("value", key);

            String smartdataUrl = "http://localhost:8080/" + key + "/smartdata/system/info";

            if (isReachable(smartdataUrl)) {
                obj.add("type", "SmartData");
            } else {
                obj.add("type", "Unknown");
            }

            arr.add(obj);
        }

        return arr.build();
    }
}