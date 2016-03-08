#!/bin/sh
mvn -f core/pom.xml jetty:run &
wait
mvn -f core/pom.xml -P product -e -X test
