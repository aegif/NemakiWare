#!/bin/sh

ORIGINAL_PWD=`pwd`

#Parse options
while getopts e opt
do
	case ${opt} in
		e) 
			shift
			FLG_E="TRUE"
			;;
		*)
	exit 1;;
	esac
done

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

#nemakiware
echo "NemakiWare configuration..."
mvn -f $SOURCE_HOME/nemakiware eclipse:eclipse
echo "NemakiWare configuration done."

#nemakisolr
echo "NemakiSolr configuration..."
mvn -f $SOURCE_HOME/nemakisolr eclipse:eclipse
echo "NemakiSolr configuration done."

#nemakishare
echo "NemakiShare configuration..."
cd $SOURCE_HOME/nemakishare
bundle install --path vendor/bundle
rake db:migrate:reset
cd $ORIGINAL_PWD
echo "NemakiShare configuration done."

#Run applications
if [ "$FLG_E" = "TRUE" ]; then
	sh start.sh
fi