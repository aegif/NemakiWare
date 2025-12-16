<%@ page language="java" contentType="text/html; charset=ISO-8859-1" pageEncoding="ISO-8859-1"%>
<%@ page import="org.apache.chemistry.opencmis.fileshare.*" %>
<%@ page import="org.apache.chemistry.opencmis.commons.definitions.*" %>
<%
   FileShareCmisServiceFactory factory = (FileShareCmisServiceFactory) application.getAttribute("org.apache.chemistry.opencmis.servicesfactory");
%>
<!-- 
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
-->
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
  <link rel="stylesheet" type="text/css" href="css/opencmis.css">
  <title>OpenCMIS FileShare Server</title>
  <style type="text/css">
  <!--
  body {
    font-family: Verdana, arial, sans-serif;
    color: black;
    font-size: 12px;
  }

  h1 {
    font-size: 24px;
    line-height: normal;
    font-weight: bold;
    background-color: #f0f0f0;
    color: #003366;
    border-bottom: 1px solid #3c78b5;
    padding: 2px;
    margin: 4px 0px 4px 0px;
  }

  h2 {
    font-size: 18px;
    line-height: normal;
    font-weight: bold;
    background-color: #f0f0f0;
    border-bottom: 1px solid #3c78b5;
    padding: 2px;
    margin: 4px 0px 4px 0px;
  }

  hr {
    color: 3c78b5;
    height: 1;
  }
  
  td {
    border: 1px solid #dddddd; 
    padding: 2px;
  }
  -->
  </style>
</head>
<body>

<h1>OpenCMIS FileShare Server</h1>

<p style="font-weight: bold">The OpenCMIS FileShare server is up and running.</p>
<p>You need a CMIS client to access this server. Download the <a href="http://chemistry.apache.org/java/developing/tools/dev-tools-workbench.html">CMIS Workbench</a>.</p>

<h2>Access Information</h2>

<h3>CMIS 1.1</h3>

<p>Web Services Binding: <a href="services11/cmis?wsdl">WSDL</a></p>
<p>AtomPub Binding: <a href="atom11">Service Document</a></p>
<p>Browser Binding: <a href="browser">Service Document</a></p>

<h3>CMIS 1.0</h3>

<p>Web Services Binding: <a href="services/cmis?wsdl">WSDL</a></p>
<p>AtomPub Binding: <a href="atom">Service Document</a></p>


<h2>Configured Repositories</h2>

<table>
<tr><th>Repository Id</th><th>Root Directory</th></tr>
<% for (FileShareRepository fsr: factory.getRepositoryManager().getRepositories()) { %>
<tr><td><%= fsr.getRepositoryId() %></td><td><%= fsr.getRootDirectory() %></td></tr>
<% } %>
</table>


<h2>Users</h2>

<table>
<tr><th>Login</th></tr>
<% for (String login: factory.getUserManager().getLogins()) { %>
<tr><td><%= login %></td></tr>
<% } %>
</table>


<h2>Types</h2>

<table>
<tr><th>Type Id</th><th>Name</th><th>Base Type Id</th></tr>
<% for (TypeDefinition type: factory.getTypeManager().getInternalTypeDefinitions()) { %>
<tr><td><%= type.getId() %></td><td><%= type.getDisplayName() %></td><td><%= type.getBaseTypeId().value() %></td></tr>
<% } %>
</table>


</body>
</html>