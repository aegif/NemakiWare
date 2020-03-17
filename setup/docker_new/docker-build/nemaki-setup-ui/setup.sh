#mvn -f /home/app/action/pom.xml install
#mvn -f /home/app/common/pom.xml install
#mvn -f /home/app/core/pom.xml install
#mvn -f /home/app/solr/pom.xml install
# cd /home/app && mvn package
# cd /home/app && mvn install
cd /home/app/ui/ && ./activator update
cd /home/app/ui/ && ./activator war