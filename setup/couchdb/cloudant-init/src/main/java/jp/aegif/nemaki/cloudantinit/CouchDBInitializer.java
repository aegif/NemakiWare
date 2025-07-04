package jp.aegif.nemaki.cloudantinit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import java.util.Base64;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONObject;

public class CouchDBInitializer {
    private static final String DEFAULT_URL = "http://localhost:5984";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "password";
    private static final String DEFAULT_REPOSITORY = "bedroom";
    private static final String DEFAULT_DUMP_FILE = "bedroom_init.dump";
    private static final boolean DEFAULT_FORCE = false;

    private String url;
    private String username;
    private String password;
    private String repository;
    private String dumpFile;
    private boolean force;
    private HttpClient httpClient;
    private HttpHost targetHost;
    private HttpClientContext context;
    private Header authorizationHeader;

    public CouchDBInitializer(String url, String username, String password, String repository, String dumpFile, boolean force) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.repository = repository;
        this.dumpFile = dumpFile;
        this.force = force;
        
        // Parse URL to get host and port
        try {
            URL parsedUrl = new URL(url);
            int port = parsedUrl.getPort();
            if (port == -1) {
                port = parsedUrl.getProtocol().equals("https") ? 443 : 80;
            }
            this.targetHost = new HttpHost(parsedUrl.getProtocol(), parsedUrl.getHost(), port);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
        
        // Create HTTP client with authentication if needed
        HttpClientBuilder builder = HttpClientBuilder.create();
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        
        if (username != null && !username.isEmpty() && password != null) {
            // Create explicit Authorization header for reliable authentication
            String credentials = username + ":" + password;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
            this.authorizationHeader = new BasicHeader("Authorization", "Basic " + encodedCredentials);
            System.out.println("Authorization header configured for user: " + username);
            
            // Also configure credentials provider as backup
            AuthScope authScope = new AuthScope(null, -1);
            credentialsProvider.setCredentials(
                    authScope,
                    new UsernamePasswordCredentials(username, password.toCharArray()));
            builder.setDefaultCredentialsProvider(credentialsProvider);
        } else {
            System.out.println("No authentication configured");
        }
        
        this.context = HttpClientContext.create();
        this.httpClient = builder.build();
    }

    public void initialize() throws IOException {
        System.out.println("Initializing CouchDB database: " + repository);
        
        // Check if database exists
        boolean dbExists = checkDatabaseExists();
        
        // If database exists and force is true, delete it
        if (dbExists && force) {
            System.out.println("Database exists, deleting it...");
            deleteDatabase();
            dbExists = false;
        }
        
        // Create database if it doesn't exist
        if (!dbExists) {
            System.out.println("Creating database...");
            createDatabase();
        } else {
            System.out.println("Database already exists, skipping creation.");
        }
        
        // Import data from dump file
        System.out.println("Importing data from dump file: " + dumpFile);
        importData();
        
        System.out.println("CouchDB initialization completed successfully!");
    }
    
    private boolean checkDatabaseExists() throws IOException {
        HttpGet request = new HttpGet(url + "/" + repository);
        
        // Add explicit Authorization header
        if (authorizationHeader != null) {
            request.setHeader(authorizationHeader);
        }
        
        return httpClient.execute(request, context, response -> {
            int statusCode = response.getCode();
            EntityUtils.consume(response.getEntity());
            return statusCode == 200;
        });
    }
    
    private void deleteDatabase() throws IOException {
        HttpDelete request = new HttpDelete(url + "/" + repository);
        
        // Add explicit Authorization header
        if (authorizationHeader != null) {
            request.setHeader(authorizationHeader);
        }
        
        httpClient.execute(request, context, response -> {
            int statusCode = response.getCode();
            EntityUtils.consume(response.getEntity());
            if (statusCode != 200 && statusCode != 202) {
                throw new RuntimeException("Failed to delete database: " + statusCode);
            }
            return null;
        });
    }
    
    private void createDatabase() throws IOException {
        String dbUrl = url + "/" + repository;
        System.out.println("Creating database at: " + dbUrl);
        System.out.println("Using credentials: " + username + " / " + (password != null ? "***" : "null"));
        
        HttpPut request = new HttpPut(dbUrl);
        
        // Add explicit Authorization header
        if (authorizationHeader != null) {
            request.setHeader(authorizationHeader);
            System.out.println("Authorization header added to request");
        }
        
        httpClient.execute(request, context, response -> {
            int statusCode = response.getCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            System.out.println("Create database response: " + statusCode + " - " + responseBody);
            
            if (statusCode != 201 && statusCode != 412) { // 412 = database already exists
                throw new RuntimeException("Failed to create database: " + statusCode + " - " + responseBody);
            }
            return null;
        });
    }
    
    private void importData() throws IOException {
        // Read dump file
        File file = new File(dumpFile);
        if (!file.exists()) {
            throw new IOException("Dump file not found: " + dumpFile);
        }
        
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        
        // Parse JSON - handle both array and object formats
        String jsonStr = content.toString().trim();
        int count = 0;
        
        if (jsonStr.startsWith("[")) {
            // Array format (NemakiWare dump format)
            JSONArray jsonArray = new JSONArray(jsonStr);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                if (item.has("document")) {
                    JSONObject docJson = item.getJSONObject("document");
                    String id = docJson.getString("_id");
                    
                    // Remove _rev field for fresh imports
                    if (docJson.has("_rev")) {
                        docJson.remove("_rev");
                    }
                    
                    // Put document
                    HttpPut request = new HttpPut(url + "/" + repository + "/" + id);
                    request.setEntity(new StringEntity(docJson.toString(), ContentType.APPLICATION_JSON));
                    
                    // Add explicit Authorization header for document creation
                    if (authorizationHeader != null) {
                        request.setHeader(authorizationHeader);
                    }
                    
                    Integer result = httpClient.execute(request, context, response -> {
                        int statusCode = response.getCode();
                        
                        if (statusCode == 201 || statusCode == 202) {
                            EntityUtils.consume(response.getEntity());
                            return 1;  // count increment
                        } else if (statusCode == 409) {
                            EntityUtils.consume(response.getEntity());
                            // Document already exists, skip
                            System.out.println("Document " + id + " already exists, skipping.");
                            return 0;
                        } else {
                            String responseBody = EntityUtils.toString(response.getEntity());
                            System.err.println("Failed to import document " + id + ": " + statusCode + " - " + responseBody);
                            return 0;
                        }
                    });
                    count += result;
                }
            }
        } else if (jsonStr.startsWith("{")) {
            // Object format (CouchDB bulk_docs format)
            JSONObject json = new JSONObject(jsonStr);
            if (json.has("docs")) {
                for (Object doc : json.getJSONArray("docs")) {
                    JSONObject docJson = (JSONObject) doc;
                    String id = docJson.getString("_id");
                    
                    // Remove _rev field for fresh imports
                    if (docJson.has("_rev")) {
                        docJson.remove("_rev");
                    }
                    
                    // Put document
                    HttpPut request = new HttpPut(url + "/" + repository + "/" + id);
                    request.setEntity(new StringEntity(docJson.toString(), ContentType.APPLICATION_JSON));
                    
                    // Add explicit Authorization header for document creation
                    if (authorizationHeader != null) {
                        request.setHeader(authorizationHeader);
                    }
                    
                    Integer result = httpClient.execute(request, context, response -> {
                        int statusCode = response.getCode();
                        
                        if (statusCode == 201 || statusCode == 202) {
                            EntityUtils.consume(response.getEntity());
                            return 1;  // count increment
                        } else if (statusCode == 409) {
                            EntityUtils.consume(response.getEntity());
                            // Document already exists, skip
                            System.out.println("Document " + id + " already exists, skipping.");
                            return 0;
                        } else {
                            String responseBody = EntityUtils.toString(response.getEntity());
                            System.err.println("Failed to import document " + id + ": " + statusCode + " - " + responseBody);
                            return 0;
                        }
                    });
                    count += result;
                }
            }
        } else {
            throw new IOException("Invalid JSON format in dump file");
        }
        
        System.out.println("Imported " + count + " documents.");
    }
    
    public static void main(String[] args) {
        // Parse command line arguments
        Options options = new Options();
        
        options.addOption(Option.builder("u").longOpt("url")
                .desc("CouchDB URL (default: " + DEFAULT_URL + ")")
                .hasArg().argName("URL")
                .build());
        
        options.addOption(Option.builder("n").longOpt("username")
                .desc("CouchDB username (default: " + DEFAULT_USERNAME + ")")
                .hasArg().argName("USERNAME")
                .build());
        
        options.addOption(Option.builder("p").longOpt("password")
                .desc("CouchDB password")
                .hasArg().argName("PASSWORD")
                .build());
        
        options.addOption(Option.builder("r").longOpt("repository")
                .desc("Repository name (default: " + DEFAULT_REPOSITORY + ")")
                .hasArg().argName("REPOSITORY")
                .build());
        
        options.addOption(Option.builder("d").longOpt("dump")
                .desc("Dump file path (default: " + DEFAULT_DUMP_FILE + ")")
                .hasArg().argName("DUMP_FILE")
                .build());
        
        options.addOption(Option.builder("f").longOpt("force")
                .desc("Force database recreation (default: " + DEFAULT_FORCE + ")")
                .hasArg().argName("FORCE")
                .build());
        
        options.addOption(Option.builder("h").longOpt("help")
                .desc("Print this help message")
                .build());
        
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            
            if (cmd.hasOption("h")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("CouchDBInitializer", options);
                return;
            }
            
            String url = cmd.getOptionValue("u", DEFAULT_URL);
            String username = cmd.getOptionValue("n", DEFAULT_USERNAME);
            String password = cmd.getOptionValue("p", DEFAULT_PASSWORD);
            String repository = cmd.getOptionValue("r", DEFAULT_REPOSITORY);
            String dumpFile = cmd.getOptionValue("d", DEFAULT_DUMP_FILE);
            boolean force = Boolean.parseBoolean(cmd.getOptionValue("f", String.valueOf(DEFAULT_FORCE)));
            
            CouchDBInitializer initializer = new CouchDBInitializer(url, username, password, repository, dumpFile, force);
            initializer.initialize();
            
        } catch (ParseException e) {
            System.err.println("Error parsing command line arguments: " + e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("CouchDBInitializer", options);
        } catch (Exception e) {
            System.err.println("Error initializing CouchDB: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
