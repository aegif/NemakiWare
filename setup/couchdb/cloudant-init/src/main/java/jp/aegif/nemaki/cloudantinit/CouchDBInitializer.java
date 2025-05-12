package jp.aegif.nemaki.cloudantinit;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.cloudant.v1.model.Ok;
import com.ibm.cloud.cloudant.v1.model.PostBulkDocsOptions;
import com.ibm.cloud.cloudant.v1.model.PutAttachmentOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

/**
 * CouchDB 3.x initializer using IBM Cloudant Java SDK
 */
public class CouchDBInitializer {
    private final String url;
    private final String username;
    private final String password;
    private final String repositoryId;
    private final File file;
    private final boolean force;
    private final Cloudant client;

    public CouchDBInitializer(String url, String username, String password, String repositoryId, File file, boolean force) {
        this.url = sanitizeUrl(url);
        this.username = username;
        this.password = password;
        this.repositoryId = repositoryId;
        this.file = file;
        this.force = force;
        
        BasicAuthenticator authenticator = new BasicAuthenticator.Builder()
            .username(username)
            .password(password)
            .build();
        
        this.client = new Cloudant("cloudant", authenticator);
        this.client.setServiceUrl(this.url);
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

        List<Document> documents = new ArrayList<>();
        Map<String, Map<String, JsonNode>> payloads = new HashMap<>();

        try {
            JsonNode _dump = new ObjectMapper().readTree(file);
            ArrayNode dump = (ArrayNode) _dump;

            Iterator<JsonNode> iterator = dump.iterator();
            while (iterator.hasNext()) {
                JsonNode _entry = iterator.next();
                ObjectNode documentNode = (ObjectNode) _entry.get("document");
                processDocument(documentNode); // remove some fields
                
                Document document = new Document();
                document.setId(documentNode.get("_id").textValue());
                
                Iterator<String> docFieldNames = documentNode.fieldNames();
                while (docFieldNames.hasNext()) {
                    String fieldName = docFieldNames.next();
                    if (!fieldName.equals("_id")) {
                        document.put(fieldName, documentNode.get(fieldName));
                    }
                }
                
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
            List<Document> subList = documents.subList(i * unit, toIndex);
            
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> bulkDocsBody = new HashMap<>();
                bulkDocsBody.put("docs", subList);
                String bulkDocsJson = mapper.writeValueAsString(bulkDocsBody);
                
                PostBulkDocsOptions bulkDocsOptions = new PostBulkDocsOptions.Builder()
                    .db(repositoryId)
                    .body(new ByteArrayInputStream(bulkDocsJson.getBytes()))
                    .build();
                
                Response<List<DocumentResult>> response = client.postBulkDocs(bulkDocsOptions).execute();
                
                if (response.getStatusCode() >= 400) {
                    System.err.println("Error inserting documents: " + response.getStatusCode());
                    return false;
                }
                
                List<DocumentResult> results = response.getResult();
                for (DocumentResult result : results) {
                    if (result.getError() != null) {
                        documentsResult.add(result.getId() + ": " + result.getReason());
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
            
            for (Entry<String, JsonNode> attachmentEntry : attachments.entrySet()) {
                String attachmentId = attachmentEntry.getKey();
                JsonNode attachment = attachmentEntry.getValue();
                
                try {
                    String data = attachment.get("data").asText();
                    String contentType = attachment.get("content_type").asText();
                    
                    byte[] attachmentData = java.util.Base64.getDecoder().decode(data);
                    InputStream attachmentStream = new ByteArrayInputStream(attachmentData);
                    
                    PutAttachmentOptions attachmentOptions = new PutAttachmentOptions.Builder()
                        .db(repositoryId)
                        .docId(docId)
                        .attachmentName(attachmentId)
                        .attachment(attachmentStream)
                        .contentType(contentType)
                        .build();
                    
                    Response<DocumentResult> response = client.putAttachment(attachmentOptions).execute();
                    
                    if (response.getStatusCode() >= 400) {
                        System.err.println("Error uploading attachment " + attachmentId + " for document " + docId + ": " + response.getStatusCode());
                    }
                } catch (Exception e) {
                    System.err.println("Error processing attachment " + attachmentId + " for document " + docId);
                    e.printStackTrace();
                }
            }
            
            attachmentIndicator.indicate();
        }

        System.out.println("Loading attachments: END");
        return true;
    }

    /**
     * Initialize repository (create database)
     */
    public boolean initRepository() {
        try {
            try {
                GetDatabaseInformationOptions options = new GetDatabaseInformationOptions.Builder()
                    .db(repositoryId)
                    .build();
                
                client.getDatabaseInformation(options).execute();
                
                return false;
            } catch (Exception e) {
                PutDatabaseOptions options = new PutDatabaseOptions.Builder()
                    .db(repositoryId)
                    .build();
                
                Response<Ok> response = client.putDatabase(options).execute();
                
                return response.getStatusCode() == 201 || response.getStatusCode() == 202;
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
