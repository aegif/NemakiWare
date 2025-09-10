#!/bin/bash

# Update all javax.servlet dependencies to jakarta.servlet in OpenCMIS 1.1.0-nemakiware

set -e

echo "Starting servlet dependency updates in pom.xml files..."

# Function to update servlet dependency in a pom.xml file
update_servlet_dependency() {
    local pom_file="$1"
    if [[ -f "$pom_file" ]]; then
        echo "Updating: $pom_file"
        
        # Update the servlet dependency - different patterns
        sed -i '' 's|<groupId>javax\.servlet</groupId>|<groupId>jakarta.servlet</groupId>|g' "$pom_file"
        sed -i '' 's|<artifactId>servlet-api</artifactId>|<artifactId>jakarta.servlet-api</artifactId>|g' "$pom_file"
        sed -i '' 's|<artifactId>javax\.servlet-api</artifactId>|<artifactId>jakarta.servlet-api</artifactId>|g' "$pom_file"
        
        # Update versions to Jakarta EE 10 compatible versions
        # Look for servlet-api version and update it
        sed -i '' 's|<version>2\.4</version>\(.*servlet.*\)|<version>6.0.0</version>\1|g' "$pom_file"
        sed -i '' 's|<version>3\.0</version>\(.*servlet.*\)|<version>6.0.0</version>\1|g' "$pom_file"
        sed -i '' 's|<version>3\.1</version>\(.*servlet.*\)|<version>6.0.0</version>\1|g' "$pom_file"
        sed -i '' 's|<version>4\.0</version>\(.*servlet.*\)|<version>6.0.0</version>\1|g' "$pom_file"
        
        echo "  ✅ Updated servlet dependencies"
    fi
}

# Update all pom.xml files found with javax.servlet dependencies
find . -name "pom.xml" -exec grep -l "javax\.servlet" {} \; | while read pom_file; do
    update_servlet_dependency "$pom_file"
done

echo ""
echo "✅ Servlet dependency updates complete!"

# Verification
echo ""
echo "🔍 Verification: Checking for remaining javax.servlet in pom.xml files..."
REMAINING_POM=$(find . -name "pom.xml" -exec grep -l "javax\.servlet" {} \; | wc -l)
if [ "$REMAINING_POM" -eq 0 ]; then
    echo "✅ SUCCESS: All pom.xml files updated to jakarta.servlet"
else
    echo "⚠️  WARNING: $REMAINING_POM pom.xml files still contain javax.servlet"
    find . -name "pom.xml" -exec grep -l "javax\.servlet" {} \;
fi