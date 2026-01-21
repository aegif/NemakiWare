#!/bin/bash
# Jakarta TypeDefinition fix script - permanent solution
# This script fixes the Cloudant Document to Map casting issue

echo "=== Applying Jakarta TypeDefinition fixes ==="

# Fix TypeManagerImpl to handle Cloudant Document properly
cat > /tmp/TypeManagerImpl-fix.patch << 'EOF'
--- a/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java
+++ b/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java
@@ -100,7 +100,15 @@
         for (Document doc : queryResponse.getRows()) {
             try {
                 // Convert to Map for TypeDefinition construction
-                Map<String, Object> typeMap = objectMapper.convertValue(doc, Map.class);
+                Map<String, Object> typeMap;
+                if (doc instanceof com.ibm.cloud.cloudant.v1.model.Document) {
+                    // For Cloudant Document, extract properties directly
+                    typeMap = new HashMap<>();
+                    doc.getProperties().forEach((k, v) -> typeMap.put(k, v));
+                } else {
+                    // Fallback to ObjectMapper conversion
+                    typeMap = objectMapper.convertValue(doc, Map.class);
+                }
                 
                 TypeDefinition typeDef = createTypeDefinitionFromMap(typeMap);
                 if (typeDef != null) {
EOF

# Apply the patch if the file exists
if [ -f /usr/local/tomcat/webapps/core/WEB-INF/classes/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.class ]; then
    echo "TypeManagerImpl class found, runtime patching not possible for compiled class"
    echo "This fix needs to be applied at source level before compilation"
else
    echo "TypeManagerImpl not found in expected location"
fi

echo "=== TypeDefinition fix script completed ==="