#\!/bin/bash

BOUNDARY="----FormBoundary$(date +%s)"
PDF_FILE="/Users/ishiiakinori/NemakiWare/setup/installer/bedroom/apache-tomcat-9.0.37/webapps/docs/architecture/startup/serverStartup.pdf"
FOLDER_ID="92b4f6b35e0a1573d109baba8a0080ed"

# Create multipart content
cat > /Users/ishiiakinori/NemakiWare/multipart_content << EOL
--${BOUNDARY}
Content-Disposition: form-data; name="atom"
Content-Type: application/atom+xml

<?xml version="1.0" encoding="UTF-8"?>
<entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
  <title>serverStartup.pdf</title>
  <cmisra:object>
    <cmis:properties>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>serverStartup.pdf</cmis:value>
      </cmis:propertyString>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:document</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:description">
        <cmis:value>Tomcat Server Startup PDF for full-text search testing</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmisra:object>
  <cmisra:content>
    <cmisra:mediatype>application/pdf</cmisra:mediatype>
  </cmisra:content>
</entry>

--${BOUNDARY}
Content-Disposition: form-data; name="content"; filename="serverStartup.pdf"
Content-Type: application/pdf

EOL

# Add PDF binary content
cat "${PDF_FILE}" >> /Users/ishiiakinori/NemakiWare/multipart_content

# Add final boundary
echo "" >> /Users/ishiiakinori/NemakiWare/multipart_content
echo "--${BOUNDARY}--" >> /Users/ishiiakinori/NemakiWare/multipart_content

# Upload with curl
curl -s -u admin:admin \
  -X POST \
  -H "Content-Type: multipart/form-data; boundary=${BOUNDARY}" \
  --data-binary @/Users/ishiiakinori/NemakiWare/multipart_content \
  "http://localhost:8080/core/atom/bedroom/children?id=${FOLDER_ID}"

