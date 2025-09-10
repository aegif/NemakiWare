CMIS Workbench

This is a simple Content Management Interoperability Services (CMIS) client based on Swing and 
Apache Chemistry OpenCMIS (http://chemistry.apache.org/java/opencmis.html).

This CMIS client is distributed under the Apache License, version 2.0.
Please see the NOTICE and LICENSE files for details.

Original icons by Piotr Kwiatkowski. Converted and modified for the CMIS Workbench.

Get the latest released CMIS Workbench from here:
https://chemistry.apache.org/java/download.html



System properties reference
---------------------------

Login dialog:

cmis.workbench.url               - preset URL
cmis.workbench.user              - preset user name
cmis.workbench.password          - preset password
cmis.workbench.binding           - preset binding (atompub/webservices/browser)
cmis.workbench.authentication    - preset authentication method (none/standard/ntlm/oauth/cert)
cmis.workbench.compression       - preset compression (on/off)
cmis.workbench.clientcompression - preset client compression (on/off)
cmis.workbench.cookies           - preset cookies (on/off)
cmis.workbench.connecttimeout    - preset the connect timeout (in seconds)
cmis.workbench.readtimeout       - preset the read timeout (in seconds)
cmis.workbench.language          - preset the language
cmis.workbench.csrfheader        - preset the CSRF HTTP header

cmis.workbench.logintab          - selects the login tab (0 for the leftmost tab)


Folder operation context:

cmis.workbench.folder.filter
cmis.workbench.folder.includeAcls
cmis.workbench.folder.includeAllowableActions
cmis.workbench.folder.includePolicies
cmis.workbench.folder.includeRelationships
cmis.workbench.folder.renditionFilter
cmis.workbench.folder.orderBy
cmis.workbench.folder.maxItemsPerPage
cmis.workbench.folder.maxChildren


Object operation context:

cmis.workbench.object.filter
cmis.workbench.object.includeAcls
cmis.workbench.object.includeAllowableActions
cmis.workbench.object.includePolicies
cmis.workbench.object.includeRelationships
cmis.workbench.object.renditionFilter


Version operation context:

cmis.workbench.version.filter
cmis.workbench.version.includeAcls
cmis.workbench.version.includeAllowableActions
cmis.workbench.version.includePolicies
cmis.workbench.version.includeRelationships
cmis.workbench.version.renditionFilter
cmis.workbench.version.maxItemsPerPage

Others:

cmis.workbench.acceptSelfSignedCertificates - disable SSL certificate check (true/false)

cmis.workbench.configs - path to a repository configuration library properties file
cmis.workbench.scripts - path to Groovy scripts library properties file