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
<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@ page import="java.util.Date, java.text.SimpleDateFormat, java.util.Locale, java.util.Calendar" %>
<%@ page import="org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager" %>
<%@ page import="org.apache.chemistry.opencmis.commons.server.CallContext" %>
<%@ page import="org.apache.chemistry.opencmis.inmemory.DummyCallContext" %>
<%@ page import="org.apache.chemistry.opencmis.commons.server.CmisServiceFactory" %>
<%@ page import="org.apache.chemistry.opencmis.commons.server.CmisService" %>
<%@ page import="org.apache.chemistry.opencmis.inmemory.server.InMemoryService" %>
<%@ page import="org.apache.chemistry.opencmis.inmemory.ConfigConstants" %>
<%@ page import="org.apache.chemistry.opencmis.inmemory.ConfigurationSettings" %>

<%!
    private static final String OPENCMIS_VERSION;

    static {
        Package p = Package.getPackage("org.apache.chemistry.opencmis.inmemory");
        if (p == null) {
            OPENCMIS_VERSION = "(unofficial dev or snapshot build)";
        } else {
            String ver = p.getImplementationVersion();
            OPENCMIS_VERSION = (null == ver ? "(unofficial dev or snapshot build)" : ver);
        }
    }
%>
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
<link rel="stylesheet" type="text/css" href="css/opencmis.css"/>
<title>Apache Chemistry OpenCMIS-InMemory Server</title>

<%!
	private StoreManager getStoreManager(HttpServletRequest request) {
	    CallContext context = new DummyCallContext();
	    CmisServiceFactory servicesFactory = (CmisServiceFactory) request.getSession().getServletContext().getAttribute(
	        "org.apache.chemistry.opencmis.servicesfactory");
	    // AbstractServiceFactory factory = (AbstractServiceFactory)
	    CmisService service = servicesFactory.getService(context);
	    if (!(service instanceof InMemoryService))
	      throw new RuntimeException("Illegal configuration, service must be of type InMemoryService.");
	    return  ((InMemoryService) service).getStoreManager();
	}
%>

</head>
<body>
<img alt="Apache Chemistry Logo" title="Apache Chemistry Logo" src="images/chemistry_logo_small.png"/>
<img align="right" alt="Powered by Apache" src="images/pb-chemistry-150x150.jpg"/>
<h1>OpenCMIS InMemory Server</h1>
<p> Your server is up and running.</p>
<p>
	The OpenCMIS InMemory Server is a CMIS server for development and test purposes.
	All objects are hold in memory and will be lost after shutdown.
</p>
<p>
	You have to use a CMIS client to use this application. An example for
	such a client is the <a href="http://chemistry.apache.org/java/developing/tools/dev-tools-workbench.html"> CMIS Workbench.</a>
</p>
<table>
<tr><th>Server-Name</th><td>Apache-Chemistry-OpenCMIS-InMemory</td></tr>
<tr>
  <th>Version</th>
  <td>
<% 
       out.println(OPENCMIS_VERSION);
%>
  </td>
</tr>
</table>

<h2>Access Information</h2>
<h3>CMIS 1.1</h3>
<p>
WS (SOAP) Binding: <a href="services11/cmis?wsdl">
<% 
String reqStr = request.getRequestURL().toString();
out.println(reqStr.substring(0, reqStr.lastIndexOf('/')+1) + "services11/cmis?wsdl");
%>
</a>
</p>
<p>
AtomPub Binding: <a href="atom11"> 
<% 
reqStr = request.getRequestURL().toString();
out.println(reqStr.substring(0, reqStr.lastIndexOf('/')+1) + "atom11");
%>
</a>
</p>
<p>
Browser Binding: <a href="browser"> 
<% 
out.println(reqStr.substring(0, reqStr.lastIndexOf('/')+1) + "browser");
%>
</a>
</p>
<h3>CMIS 1.0</h3>
<p>
WS (SOAP) Binding: <a href="services/cmis?wsdl">
<% 
reqStr = request.getRequestURL().toString();
out.println(reqStr.substring(0, reqStr.lastIndexOf('/')+1) + "services/cmis?wsdl");
%>
</a>
</p>
<p>
AtomPub Binding: <a href="atom"> 
<% 
reqStr = request.getRequestURL().toString();
out.println(reqStr.substring(0, reqStr.lastIndexOf('/')+1) + "atom");
%>
</a>
</p>
<p>
<h3>Authentication</h3>
<p>
Basic Authentication (user name and password are arbitrary)<br>
Note: Authentication is optional and only informational. User names are stored 
in properties (createdBy, etc.), password is not required. The server does 
not perform any kind of secure authentication.
</p>

<h2>Web Interface</h2>
<p>
The <a href="web">OpenCMIS web interface</a> is simple web interface to access
the repository. Please note that this is not the usual way to access the repository.
Usually you will use a client application supporting the CMIS specification like
the CMIS workbench.
</p>

<h2>NOTICE</h2>
<p>
This is an unsupported and experimental service. Any use is at your own risk.
</p>
<p>
This service is provided on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS 
OF ANY KIND, either express or implied. See the license below for more information.
</p>

<h2>Monitor</h2>
<p>
  Current state of the server:
</p>

<table>
<tr> <th> Repository Id </th> <th> No. of objects</th></tr>
<% 
   StoreManager sm = getStoreManager(request);
   for (String repId: sm.getAllRepositoryIds() ) {
       out.println("<td>" + repId + "</td>");
       out.println("<td>" + sm.getObjectStore(repId).getObjectCount() + "</td>");
   }       
%>
</table>
<p>&nbsp;</p>
<table>
<tr> <th> Java VM </th> <th>Size</th></tr>
<% 
   Runtime runtime = Runtime.getRuntime ();   
   long mb = 1048576;
   long value;
   value = runtime.totalMemory ();
   value = (value + mb/2) / mb; 
   out.println("<tr><td> Used Memory </td>");
   out.println("<td>" +  value + "MB</td></tr>");
   value = runtime.maxMemory ();
   value = (value + mb/2) / mb; 
   out.println("<tr><td> Max Memory </td>");
   out.println("<td>" + value + "MB</td></tr>");
   value = runtime.freeMemory ();
   value = (value + mb/2) / mb; 
   out.println("<tr><td> Free Memory </td>");
   out.println("<td>" + value + "MB</td>");
   out.println("<tr><td> Processors </td>");
   out.println("<td>" + runtime.availableProcessors() + "</td></tr>");
%>
</table>

<h2>Configuration</h2>
<p>
  Important configuration settings
</p>

<table>
<tr> <th> Setting </th> <th> Value</th></tr>
<tr>
	<td>Max. allowed content size </td>
	<% 
	  String maxSize = ConfigurationSettings.getConfigurationValueAsString(ConfigConstants.MAX_CONTENT_SIZE_KB);
	  if (null == maxSize)
	      maxSize = "unlimited";
	  else
		maxSize += "KB";
	  out.println("<td>" + maxSize + "</td>");
	%>
</tr>
<tr>
	<td>Auto clean every</td>
	<% 
	  String cleanInterValStr = ConfigurationSettings.getConfigurationValueAsString(ConfigConstants.CLEAN_REPOSITORY_INTERVAL);
	  if (null == cleanInterValStr)
	      out.println("<td> - </td>");
	  else
	  	out.println("<td>" + cleanInterValStr + " minutes </td>");
	%>
</tr>
<tr>
	<td>Time of deployment</td>
	<% 
	  out.println("<td>" + ConfigurationSettings.getConfigurationValueAsString(ConfigConstants.DEPLOYMENT_TIME) + "</td>");
	%>
</tr>
<tr>
	<td>Next cleanup</td>
	<% 
	  String dateStr;
	  Long interval = ConfigurationSettings.getConfigurationValueAsLong(ConfigConstants.CLEAN_REPOSITORY_INTERVAL);
	  long diff = 0;
	  
	  if (null == interval)
	      dateStr = "Never";
	  else {
		  try {
		      Date now = new Date();
		      Calendar calNow = Calendar.getInstance();
		      Calendar calNextClean = Calendar.getInstance();
		      calNow.setTime(now);
			  SimpleDateFormat formatter ; 
		      Date deploy;
		      formatter = new SimpleDateFormat("EEE MMM dd hh:mm:ss a z yyyy", Locale.US);
		      deploy = formatter.parse(ConfigurationSettings.getConfigurationValueAsString(ConfigConstants.DEPLOYMENT_TIME));
		      calNextClean.setTime(deploy);
		      while (calNextClean.before(calNow))
		          calNextClean.add(Calendar.MINUTE, interval.intValue());
		      dateStr = formatter.format(calNextClean.getTime());
		      diff = calNextClean.getTimeInMillis() - calNow.getTimeInMillis();
		      
		  } catch (Exception e) {
		      dateStr = e.getMessage();
		  }
	  }
	  if (diff > 0)
	      dateStr += " (in " + diff / 60000 + "min, " + ((diff / 1000) % 60) + "s)";
	  
	  // Date deploy = new Date(Date.parse();
	  out.println("<td>" + dateStr + "</td>");
	%>
</tr>
</table>

<h2>More Information</h2>
<p>
<a href="http://chemistry.apache.org"> Apache Chemistry web site</a>
</p>
<p>
<a href="http://www.oasis-open.org/committees/cmis"> CMIS page at OASIS</a>
</p>


<hr/>
<h2>License Information</h2>
This software is licensed under the 
<a href="http://www.apache.org/licenses/LICENSE-2.0.html"> Apache 2.0 License </a>
<br/>

<a href="http://www.apache.org">
  <img alt="ASF Logo" title="ASF Logo" src="images/asf_logo.png" align="right"/>
</a>
</body>
</html>