#!/bin/bash

echo "=== Removing tracked files that should be ignored ==="
echo ""

# Files to remove from Git tracking
FILES_TO_REMOVE=(
    "docker/tck-execution.log"
    "docker/test-output.log"
    "docker/tck-reports/"
)

echo "Files that will be removed from Git tracking:"
for file in "${FILES_TO_REMOVE[@]}"; do
    if git ls-files --error-unmatch "$file" 2>/dev/null; then
        echo "  - $file"
    fi
done

echo ""
read -p "Remove these files from Git tracking? (y/N): " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    for file in "${FILES_TO_REMOVE[@]}"; do
        echo "Removing $file from Git..."
        git rm -r --cached "$file" 2>/dev/null || echo "  Already untracked: $file"
    done
    
    echo ""
    echo "Files removed from Git tracking."
    echo ""
    echo "These files are now ignored by Git but remain in your working directory."
    echo ""
    echo "To complete the process:"
    echo "1. Review the changes: git status"
    echo "2. Commit the changes: git commit -m 'Clean up tracked files per .gitignore'"
else
    echo "Operation cancelled."
fi