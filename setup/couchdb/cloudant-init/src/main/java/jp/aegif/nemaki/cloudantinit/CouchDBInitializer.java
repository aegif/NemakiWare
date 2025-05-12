package jp.aegif.nemaki.cloudantinit;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * CouchDB 3.x initializer using direct RESTful API calls
 */
public class CouchDBInitializer {
    private final String url;
    private final String username;
    private final String password;
    private final String repositoryId;
    private final File file;
    private final boolean force;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CouchDBInitializer(String url, String username, String password, String repositoryId, File file, boolean force) {
        this.url = sanitizeUrl(url);
        this.username = username;
        this.password = password;
        this.repositoryId = repositoryId;
        this.file = file;
        this.force = force;
        this.objectMapper = new ObjectMapper();
        
        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            System.out.println("Using authenticated connection for CouchDB");
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(username, password)
            );
            
            this.httpClient = HttpClients.custom()
                .setDefaultCredentialsProvider(credentialsProvider)
                .build();
        } else {
            System.out.println("Using non-authenticated connection for CouchDB");
            this.httpClient = HttpClients.createDefault();
        }
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("Wrong number of arguments: url, username, password, repositoryId, filePath, force");
            return;
        }

        String url = args[0];
        String username = args[1];
        String password = args[2];
        String repositoryId = args[3];
        String filePath = args[4];
        File file = new File(filePath);

        boolean force = false;
        try {
            String _force = args[5];
            force = "true".equals(_force);
        } catch (Exception e) {
        }

        CouchDBInitializer initializer = new CouchDBInitializer(url, username, password, repositoryId, file, force);
        boolean success = initializer.load();

        if (success) {
            System.out.println(repositoryId + ": Data imported successfully");
        } else {
            System.err.println(repositoryId + ": Data import failed");
        }
    }

    /**
     * Load data into CouchDB
     */
    public boolean load() {
        boolean initResult = initRepository();
        if (!initResult) {
            if (force) {
                System.out.println("Database already exists, continuing with import");
            } else {
                System.out.println("Database already exists, skipping import");
                return false;
            }
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        Map<String, Map<String, JsonNode>> payloads = new HashMap<>();

        try {
            JsonNode _dump = objectMapper.readTree(file);
            ArrayNode dump = (ArrayNode) _dump;

            Iterator<JsonNode> iterator = dump.iterator();
            while (iterator.hasNext()) {
                JsonNode _entry = iterator.next();
                ObjectNode documentNode = (ObjectNode) _entry.get("document");
                processDocument(documentNode); // remove some fields
                
                Map<String, Object> document = objectMapper.convertValue(documentNode, new TypeReference<Map<String, Object>>() {});
                documents.add(document);

                JsonNode attachments = _entry.get("attachments");
                String docId = documentNode.get("_id").textValue();
                
                if (attachments != null && !attachments.isEmpty()) {
                    Map<String, JsonNode> attachmentMap = new HashMap<>();
                    Iterator<String> fieldNames = attachments.fieldNames();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        attachmentMap.put(fieldName, attachments.get(fieldName));
                    }
                    payloads.put(docId, attachmentMap);
                }
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        System.out.println("Loading metadata: START");
        
        int unit = 500; // Smaller batch size for CouchDB 3.x
        int turn = documents.size() / unit;
        ProgressIndicator metadataIndicator = new ProgressIndicator(documents.size());
        
        List<String> documentsResult = new ArrayList<>();
        for (int i = 0; i <= turn; i++) {
            int toIndex = Math.min(unit * (i + 1), documents.size());
            List<Map<String, Object>> subList = documents.subList(i * unit, toIndex);
            
            try {
                Map<String, Object> bulkDocsBody = new HashMap<>();
                bulkDocsBody.put("docs", subList);
                String bulkDocsJson = objectMapper.writeValueAsString(bulkDocsBody);
                
                HttpPost httpPost = new HttpPost(url + "/" + repositoryId + "/_bulk_docs");
                httpPost.setHeader("Content-Type", "application/json");
                httpPost.setEntity(new StringEntity(bulkDocsJson, ContentType.APPLICATION_JSON));
                
                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    
                    if (statusCode >= 400) {
                        System.err.println("Error inserting documents: " + statusCode);
                        return false;
                    }
                    
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        String responseBody = EntityUtils.toString(entity);
                        List<Map<String, Object>> results = objectMapper.readValue(responseBody, 
                                new TypeReference<List<Map<String, Object>>>() {});
                        
                        for (Map<String, Object> result : results) {
                            if (result.containsKey("error")) {
                                documentsResult.add(result.get("id") + ": " + result.get("reason"));
                            }
                        }
                    }
                }
                
                metadataIndicator.indicate(subList.size());
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        if (CollectionUtils.isNotEmpty(documentsResult)) {
            System.err.println("Some documents were not imported because of errors:");
            for (String error : documentsResult) {
                System.err.println(error);
            }
        }
        System.out.println("Loading metadata: END");

        System.out.println("Loading attachments: START");
        ProgressIndicator attachmentIndicator = new ProgressIndicator(payloads.size());
        
        for (Entry<String, Map<String, JsonNode>> entry : payloads.entrySet()) {
            String docId = entry.getKey();
            Map<String, JsonNode> attachments = entry.getValue();
            
            String rev = getLatestRevision(docId);
            if (rev == null) {
                System.err.println("Could not get latest revision for document: " + docId);
                continue;
            }
            
            for (Entry<String, JsonNode> attachmentEntry : attachments.entrySet()) {
                String attachmentId = attachmentEntry.getKey();
                JsonNode attachment = attachmentEntry.getValue();
                
                try {
                    String data = attachment.get("data").asText();
                    String contentType = attachment.get("content_type").asText();
                    
                    byte[] attachmentData = Base64.getDecoder().decode(data);
                    InputStream attachmentStream = new ByteArrayInputStream(attachmentData);
                    
                    URI uri = new URIBuilder(url + "/" + repositoryId + "/" + docId + "/" + attachmentId)
                            .addParameter("rev", rev)
                            .build();
                    
                    HttpPut httpPut = new HttpPut(uri);
                    httpPut.setHeader("Content-Type", contentType);
                    httpPut.setEntity(new InputStreamEntity(attachmentStream, attachmentData.length));
                    
                    try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
                        int statusCode = response.getStatusLine().getStatusCode();
                        
                        if (statusCode >= 400) {
                            System.err.println("Error uploading attachment " + attachmentId + 
                                    " for document " + docId + ": " + statusCode);
                        } else {
                            HttpEntity entity = response.getEntity();
                            if (entity != null) {
                                String responseBody = EntityUtils.toString(entity);
                                Map<String, Object> result = objectMapper.readValue(responseBody, 
                                        new TypeReference<Map<String, Object>>() {});
                                if (result.containsKey("rev")) {
                                    rev = (String) result.get("rev");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error processing attachment " + attachmentId + " for document " + docId);
                    e.printStackTrace();
                }
            }
            
            attachmentIndicator.indicate();
        }

        System.out.println("Loading attachments: END");
        
        try {
            httpClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        return true;
    }

    /**
     * Get the latest revision of a document
     */
    private String getLatestRevision(String docId) {
        try {
            HttpGet httpGet = new HttpGet(url + "/" + repositoryId + "/" + docId);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode == HttpStatus.SC_OK) {
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        String responseBody = EntityUtils.toString(entity);
                        Map<String, Object> doc = objectMapper.readValue(responseBody, 
                                new TypeReference<Map<String, Object>>() {});
                        
                        if (doc.containsKey("_rev")) {
                            return (String) doc.get("_rev");
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return null;
    }

    /**
     * Initialize repository (create database)
     */
    public boolean initRepository() {
        try {
            HttpGet httpGet = new HttpGet(url + "/" + repositoryId);
            
            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode == HttpStatus.SC_OK) {
                    return false;
                }
            }
            
            HttpPut httpPut = new HttpPut(url + "/" + repositoryId);
            
            try (CloseableHttpResponse response = httpClient.execute(httpPut)) {
                int statusCode = response.getStatusLine().getStatusCode();
                
                return statusCode == HttpStatus.SC_CREATED || statusCode == HttpStatus.SC_ACCEPTED;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Process document before insertion
     */
    private static void processDocument(ObjectNode document) {
        document.remove("_rev");
        document.remove("_attachments");
    }

    /**
     * Sanitize URL
     */
    private static String sanitizeUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return "http://127.0.0.1:5984";
        }
        
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        
        return url;
    }
}
