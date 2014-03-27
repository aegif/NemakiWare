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

echo "NemakiWare server starting..."
nohup mvn -f $SOURCE_HOME/nemakiware jetty:run &

echo "NemakiSolr server starting..."
nohup mvn -f $SOURCE_HOME/nemakisolr jetty:run &

echo "NemakiShare client starting..."
cd $SOURCE_HOME/nemakishare
nohup rails s &
cd $ORIGINAL_PWD