#!/bin/sh

ORIGINAL_PWD=`pwd`

#Location
if [ -z "$1" ]; then
  	#Move to source code HOME
  	SHELL_PATH=`dirname $0`
	cd $SHELL_PATH
	cd ../../
    SOURCE_HOME=`pwd`
else
	SOURCE_HOME=$1
fi

echo "NemakiShare client stopping..."
cd $SOURCE_HOME/nemakishare
kill -9 $(cat tmp/pids/server.pid)
rm -f tmp/pids/server.pid
echo "NemakiShare client stopped."
cd $ORIGINAL_PWD

echo "NemakiSolr server stopping..."
mvn -f $SOURCE_HOME/nemakisolr jetty:stop
echo "NemakiSolr server stopped."

echo "NemakiWare server stopping..."
mvn -f $SOURCE_HOME/nemakiware jetty:stop
echo "NemakiWare server stopped."
