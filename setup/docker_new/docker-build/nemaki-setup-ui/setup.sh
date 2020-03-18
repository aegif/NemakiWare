#!/bin/bash
java -version
export ACTIVATOR_HOME=/home/app/ui
cd /home/app/ui/ && ./activator eclipse
cd /home/app/ui/ && ./activator war