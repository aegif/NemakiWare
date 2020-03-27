#!/bin/bash
export ACTIVATOR_HOME=/root/.sbt/app/ui
cd /root/.sbt/app/ui/ && sbt update
cp /root/.sbt/repositories.txt /root/.sbt/repositories
./root/.sbt/app/ui/activator war
