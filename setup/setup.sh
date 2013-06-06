#!/bin/sh

echo "NemakiWare setup starting..."

#Initialize database
sh setup_couchdb.sh

#Copy tracking file from the template
echo "Copy tracking files"
cp -f ../nemakisolr/src/main/webapp/WEB-INF/classes/tracking.properties.template ../nemakisolr/src/main/webapp/WEB-INF/classes/tracking.properties
cp -f ../nemakishare/config/latest_change_token.yml.template ../nemakishare/config/latest_change_token.yml


#Client's bundle install
echo "Install NemakiWare client dependencies"
cd ../nemakishare
bundle install

#Overwrite ActiveCMIS library
GEMWHICH=`gem which active_cmis`
GEM_LIB_LOCATION=${GEMWHICH%/*}
GEM_LOCATION=${GEM_LIB_LOCATION%/*}

#GEM_LOCATION should be like "gems/ruby-1.9.3-p362/gems/active_cmis-0.3.2/" etc.
echo "Overwrite ActiveCMIS gem at: $GEM_LOCATION"
cp -rf active_cmis/lib $GEM_LOCATION

#Prepare the client's db(for changelog)
echo "Create NemakiWare client's database"
rake db:migrate

echo "NemakiWare setup successfully done!"
echo "Please don't forget to overwrite ActiveCMIS library. The location can be found by 'gem which active_cmis'".
