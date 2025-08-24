<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
  <link rel="stylesheet" type="text/css" href="css/opencmis.css">
  <title>${artifactId} Server</title>
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
  -->
  </style>
</head>
<body>

<h1>${artifactId} Server</h1>

<p style="font-weight: bold">Your server is up and running.</p>
<p>The ${artifactId} server is a CMIS server based on Apache Chemistry OpenCMIS.</p>
<p>You need a CMIS client to access this server. Download the <a href="http://chemistry.apache.org/java/developing/tools/dev-tools-workbench.html">CMIS Workbench</a>.</p>

<h2>Access Information</h2>

<h3>CMIS 1.1</h3>

<p>Web Services Binding: <a href="services11/cmis?wsdl">WSDL</a></p>
<p>AtomPub Binding: <a href="atom11">Service Document</a></p>
<p>Browser Binding: <a href="browser">Service Document</a></p>

<h3>CMIS 1.0</h3>

<p>Web Services Binding: <a href="services/cmis?wsdl">WSDL</a></p>
<p>AtomPub Binding: <a href="atom">Service Document</a></p>

<h3>Authentication</h3>

<p>No authentication required.</p>


<h2>More Information</h2>

<p><a href="http://chemistry.apache.org">Apache Chemistry web site</a></p>
<p><a href="http://www.oasis-open.org/committees/cmis">CMIS Technical Committees at OASIS</a></p>

<hr/>
This software is powered by <a href="http://chemistry.apache.org/">Apache Chemistry</a>.
<br/>

</body>
</html>