#!/usr/bin/env python3
"""Remove debug statements from Java files - improved version"""

import os
import re

def process_file(filepath):
    """Process a single Java file to remove debug statements"""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()

        original_content = content

        # Remove System.err.println lines with debug markers
        # Pattern: lines containing System.err.println and debug markers
        content = re.sub(r'^\s*System\.err\.println\([^)]*?(===|\*\*\*|DEBUG|TCK|CRITICAL|WARNING|SUCCESS|⚠️|✅)[^)]*\);\s*\n', '', content, flags=re.MULTILINE)

        # Remove remaining standalone System.err.println lines (likely debug)
        content = re.sub(r'^\s*System\.err\.println\([^)]*\);\s*\n', '', content, flags=re.MULTILINE)

        # Remove log.error lines with *** markers
        content = re.sub(r'^\s*log\.error\([^)]*\*\*\*[^)]*\);\s*\n', '', content, flags=re.MULTILINE)

        # Remove log.info DEBUG lines
        content = re.sub(r'^\s*log\.info\("DEBUG:[^)]*\);\s*\n', '', content, flags=re.MULTILINE)

        if content != original_content:
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(content)
            removed = original_content.count('\n') - content.count('\n')
            return removed
        return 0

    except Exception as e:
        print(f"Error processing {filepath}: {e}")
        return 0

def main():
    base_dir = '/Users/ishiiakinori/NemakiWare/core/src/main/java/jp/aegif/nemaki'
    total_removed = 0
    files_modified = 0

    for root, dirs, files in os.walk(base_dir):
        for file in files:
            if file.endswith('.java'):
                filepath = os.path.join(root, file)
                removed = process_file(filepath)
                if removed > 0:
                    print(f"✅ {os.path.relpath(filepath, base_dir)}: {removed} lines")
                    total_removed += removed
                    files_modified += 1

    print(f"\n📊 Files modified: {files_modified}, Lines removed: {total_removed}")

if __name__ == '__main__':
    main()
