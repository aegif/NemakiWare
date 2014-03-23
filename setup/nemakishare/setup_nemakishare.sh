#!/bin/sh

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

#Setup
cd $SOURCE_HOME/nemakishare
bundle install --path=vendor/bundle --local
rake db:migrate:reset