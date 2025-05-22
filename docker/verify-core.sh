#!/bin/bash
echo "Checking if core is responding..."
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core/rest/repo/bedroom
