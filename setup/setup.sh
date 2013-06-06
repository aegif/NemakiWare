#!/bin/sh

#Initialize database
sh setup_couchdb.sh

#Copy tracking file from the template
cp -f ../nemakisolr/src/main/webapp/WEB-INF/classes/tracking.properties.template ../nemakisolr/src/main/webapp/WEB-INF/classes/tracking.properties
cp -f ../nemakishare/config/latest_change_token.yml.template ../nemakishare/config/latest_change_token.yml

#Prepare the client's db(for changelog)
cd ../nemakishare
rake db:migrate