@echo off
rem Licensed to the Apache Software Foundation (ASF) under one or more
rem contributor license agreements.  See the NOTICE file distributed with
rem this work for additional information regarding copyright ownership.
rem The ASF licenses this file to You under the Apache License, Version 2.0
rem (the "License"); you may not use this file except in compliance with
rem the License.  You may obtain a copy of the License at
rem
rem     http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.

rem @version@

rem use variable CUSTOM_JAVA_OPTS to set additional JAVA options

rem uncomment the following lines to configure HTTP proxy

rem set http_proxy=http://<proxy>:<port>
rem set https_proxy=https://<proxy>:<port>
rem set no_proxy=localhost,127.0.0.0,.local


for /F "delims=/" %%x in ('"java -classpath .;%~dp0\lib\* org.apache.chemistry.opencmis.workbench.ProxyDetector -j -s"') do set "JAVA_PROXY_CONF=%%x"
set JAVA_OPTS=%JAVA_PROXY_CONF%

java %JAVA_OPTS% %CUSTOM_JAVA_OPTS% -classpath ".;%~dp0\lib\*" org.apache.chemistry.opencmis.script.ScriptExecutor %*