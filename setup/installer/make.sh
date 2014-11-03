#!/bin/sh

# ShellScript to generate NemakiWare installer from source code
#
# Usage:
# ./make.sh 
# or
# ./make.sh PATH_TO_NEMAKIWARE_SOURCECODE
#
# Note: PATH_TO_NEMAKIWARE_SOURCECODE means the parent folder of nemakiware, nemakisolr etc.
# Note: PATH_TO_NEMAKIWARE_SOURCECODE should be without the last slash.
# Note: -e option enables to execute the generated install.jar

###

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
EXECUTION_DIRECTORY=`pwd`
if [ -z "$1" ]; then
  	#Move to source code HOME
  	SHELL_PATH=`dirname $0`
	cd $SHELL_PATH
	cd ../../
    SOURCE_HOME=`pwd`
else
	SOURCE_HOME=$1
fi
SCRIPT_HOME=$SOURCE_HOME/setup/installer
DEFAULT_PROP_PATH=$SOURCE_HOME/core/src/main/webapp/WEB-INF/classes
CUSTOM_PROP_PATH=$SOURCE_HOME/core/src/main/resources

#Build install utilities
mvn -f $SCRIPT_HOME/install-util/pom.xml package
mvn -f $SOURCE_HOME/setup/couchdb/bjornloka/pom.xml package
#Setting installer default values from source code
#Override by custom properties
PROPERTIES=$DEFAULT_PROP_PATH/nemakiware.properties
PROPERTIES_CUSTOM=$CUSTOM_PROP_PATH/custom-nemakiware.properties

USER_INPUT_SPEC=$SCRIPT_HOME/user-input-spec.xml
USER_INPUT_SPEC_MODIFIED=$SCRIPT_HOME/user-input-spec_modified.xml
java -cp $SCRIPT_HOME/install-util/target/install-util.jar jp.aegif.nemaki.installer.ProcessTemplate $USER_INPUT_SPEC $PROPERTIES $PROPERTIES_CUSTOM

#Prepare WAR
mvn -f $SOURCE_HOME/core/pom.xml clean
mvn -f $SOURCE_HOME/core/pom.xml -Dmaven.test.skip=true package
mvn -f $SOURCE_HOME/solr/pom.xml clean
mvn -f $SOURCE_HOME/solr/pom.xml -Dmaven.test.skip=true package
cd $SOURCE_HOME/ui/
activator war
cd EXECUTION_DIRECTORY

#Build installer
$SCRIPT_HOME/IzPack/bin/compile $SCRIPT_HOME/install.xml -b $SOURCE_HOME -o $SCRIPT_HOME/install.jar -k standard

#Delete tmp file after putting them into installer
rm $USER_INPUT_SPEC_MODIFIED

#Execute isntaller
if [ "$FLG_E" = "TRUE" ]; then
	java -jar $SCRIPT_HOME/install.jar
fi