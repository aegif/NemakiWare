#!/bin/bash
cd /root/.sbt/app/common && mvn install
cd /root/.sbt/app/action && mvn install

export ACTIVATOR_HOME=/root/.sbt/app/ui
cd /root/.sbt/app/ui/ && sbt update
mv /root/.sbt/repositories.txt /root/.sbt/repositories
./root/.sbt/app/ui/activator war
