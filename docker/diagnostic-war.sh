#!/bin/bash

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}    NemakiWare WAR Test Diagnostics     ${NC}"
echo -e "${BLUE}========================================${NC}"

echo -e "${BLUE}Checking container status:${NC}"
CONTAINERS=("docker-couchdb2-1" "docker-initializer2-1" "docker-solr2-1" "docker-core2-1" "docker-ui2-war-1")
ALL_RUNNING=true

for CONTAINER in "${CONTAINERS[@]}"
do
    STATUS=$(docker inspect --format='{{.State.Status}}' $CONTAINER 2>/dev/null || echo "not_found")
    if [ "$STATUS" = "running" ]; then
        echo -e "  ${GREEN}✓ $CONTAINER is running${NC}"
    else
        echo -e "  ${RED}✗ $CONTAINER is not running (status: $STATUS)${NC}"
        ALL_RUNNING=false
    fi
done

if [ "$ALL_RUNNING" = false ]; then
    echo -e "${RED}Some containers are not running. Check the logs for errors.${NC}"
fi

echo -e "\n${BLUE}Checking CouchDB status:${NC}"
COUCHDB_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:5984/_up || echo "connection_error")
if [ "$COUCHDB_STATUS" = "200" ]; then
    echo -e "  ${GREEN}✓ CouchDB is up and running${NC}"
else
    echo -e "  ${RED}✗ CouchDB returned status $COUCHDB_STATUS${NC}"
fi

echo -e "  ${BLUE}Checking CouchDB databases:${NC}"
COUCHDB_DBS=$(curl -s -u admin:password http://localhost:5984/_all_dbs || echo "connection_error")
echo -e "  Databases: $COUCHDB_DBS"

if [[ $COUCHDB_DBS == *"bedroom"* ]]; then
    echo -e "  ${GREEN}✓ 'bedroom' database exists${NC}"
else
    echo -e "  ${RED}✗ 'bedroom' database not found${NC}"
fi

echo -e "\n${BLUE}Checking Core server status:${NC}"
CORE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core || echo "connection_error")
if [ "$CORE_STATUS" = "200" ]; then
    echo -e "  ${GREEN}✓ Core server is up and running${NC}"
else
    echo -e "  ${RED}✗ Core server returned status $CORE_STATUS${NC}"
    echo -e "  ${YELLOW}Checking Core server logs:${NC}"
    docker logs docker-core2-1 | grep -A 3 -B 3 "SEVERE" | tail -20
fi

echo -e "\n${BLUE}Checking UI server status:${NC}"
UI_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000 || echo "connection_error")
if [ "$UI_STATUS" = "200" ]; then
    echo -e "  ${GREEN}✓ UI server is up and running${NC}"
else
    echo -e "  ${RED}✗ UI server returned status $UI_STATUS${NC}"
    
    echo -e "  ${YELLOW}Checking alternate UI paths:${NC}"
    UI_ROOT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ || echo "connection_error")
    UI_UI_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui/ || echo "connection_error")
    UI_REPO_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/repo/bedroom/ || echo "connection_error")
    UI_UI_REPO_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/ui/repo/bedroom/ || echo "connection_error")
    
    echo -e "  Root path (/): $UI_ROOT_STATUS"
    echo -e "  UI path (/ui/): $UI_UI_STATUS"
    echo -e "  Repo path (/repo/bedroom/): $UI_REPO_STATUS"
    echo -e "  UI+Repo path (/ui/repo/bedroom/): $UI_UI_REPO_STATUS"
    
    echo -e "  ${YELLOW}Checking UI server logs:${NC}"
    docker logs docker-ui2-war-1 | grep -i "error\|exception\|failure" | tail -20
fi

echo -e "\n${BLUE}Checking UI to Core connectivity:${NC}"
echo -e "  UI is configured to connect to: $(docker exec docker-ui2-war-1 cat /usr/local/tomcat/conf/nemakiware_ui.properties | grep nemaki.core.uri)"

echo -e "\n${BLUE}Diagnostic Summary:${NC}"
if [ "$ALL_RUNNING" = true ] && [ "$COUCHDB_STATUS" = "200" ] && [ "$CORE_STATUS" = "200" ] && [ "$UI_STATUS" = "200" ]; then
    echo -e "${GREEN}All services are running correctly!${NC}"
else
    echo -e "${RED}Some services have issues. Please check the logs above for more details.${NC}"
fi

echo -e "\n${BLUE}Test completed.${NC}"
