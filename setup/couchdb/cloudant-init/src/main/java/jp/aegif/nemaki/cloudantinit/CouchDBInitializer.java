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
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
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
            this.targetHost = new HttpHost(parsedUrl.getHost(), port, parsedUrl.getProtocol());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
        
        // Create HTTP client with authentication if needed
        HttpClientBuilder builder = HttpClientBuilder.create();
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        
        if (username != null && !username.isEmpty() && password != null) {
            credentialsProvider.setCredentials(
                    new AuthScope(targetHost.getHostName(), targetHost.getPort()),
                    new UsernamePasswordCredentials(username, password));
            builder.setDefaultCredentialsProvider(credentialsProvider);
            
            // Setup preemptive authentication
            AuthCache authCache = new BasicAuthCache();
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(targetHost, basicAuth);
            
            this.context = HttpClientContext.create();
            this.context.setAuthCache(authCache);
        } else {
            this.context = HttpClientContext.create();
        }
        
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
        HttpResponse response = httpClient.execute(targetHost, request, context);
        int statusCode = response.getStatusLine().getStatusCode();
        EntityUtils.consume(response.getEntity());
        return statusCode == 200;
    }
    
    private void deleteDatabase() throws IOException {
        HttpDelete request = new HttpDelete(url + "/" + repository);
        HttpResponse response = httpClient.execute(targetHost, request, context);
        int statusCode = response.getStatusLine().getStatusCode();
        EntityUtils.consume(response.getEntity());
        if (statusCode != 200 && statusCode != 202) {
            throw new IOException("Failed to delete database: " + statusCode);
        }
    }
    
    private void createDatabase() throws IOException {
        HttpPut request = new HttpPut(url + "/" + repository);
        HttpResponse response = httpClient.execute(targetHost, request, context);
        int statusCode = response.getStatusLine().getStatusCode();
        EntityUtils.consume(response.getEntity());
        if (statusCode != 201) {
            throw new IOException("Failed to create database: " + statusCode);
        }
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
                    HttpResponse response = httpClient.execute(targetHost, request, context);
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    if (statusCode == 201 || statusCode == 202) {
                        EntityUtils.consume(response.getEntity());
                        count++;
                    } else if (statusCode == 409) {
                        EntityUtils.consume(response.getEntity());
                        // Document already exists, skip
                        System.out.println("Document " + id + " already exists, skipping.");
                    } else {
                        String responseBody = EntityUtils.toString(response.getEntity());
                        System.err.println("Failed to import document " + id + ": " + statusCode + " - " + responseBody);
                    }
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
                    HttpResponse response = httpClient.execute(targetHost, request, context);
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    if (statusCode == 201 || statusCode == 202) {
                        EntityUtils.consume(response.getEntity());
                        count++;
                    } else if (statusCode == 409) {
                        EntityUtils.consume(response.getEntity());
                        // Document already exists, skip
                        System.out.println("Document " + id + " already exists, skipping.");
                    } else {
                        String responseBody = EntityUtils.toString(response.getEntity());
                        System.err.println("Failed to import document " + id + ": " + statusCode + " - " + responseBody);
                    }
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
