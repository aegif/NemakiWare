#!/usr/bin/env python3
"""Remove debug System.err.println statements from Java files"""

import os
import re
import sys

def should_remove_line(line):
    """Check if a line should be removed (debug System.err.println)"""
    # Remove lines with System.err.println that contain debug markers
    if 'System.err.println' in line:
        debug_markers = ['***', '===', 'DEBUG', 'TCK', 'CRITICAL', 'WARNING', 'SUCCESS', '⚠️', '✅']
        return any(marker in line for marker in debug_markers)

    # Remove log.error lines with *** markers
    if 'log.error' in line and '***' in line:
        return True

    # Remove log.info DEBUG lines
    if 'log.info("DEBUG:' in line:
        return True

    return False

def remove_debug_from_file(filepath):
    """Remove debug statements from a single file"""
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = f.readlines()

        original_count = len(lines)
        filtered_lines = [line for line in lines if not should_remove_line(line)]
        removed_count = original_count - len(filtered_lines)

        if removed_count > 0:
            with open(filepath, 'w', encoding='utf-8') as f:
                f.writelines(filtered_lines)
            print(f"✅ {filepath}: Removed {removed_count} debug lines")
            return removed_count
        else:
            return 0

    except Exception as e:
        print(f"❌ Error processing {filepath}: {e}", file=sys.stderr)
        return 0

def main():
    """Main function to process all Java files"""
    base_dir = '/Users/ishiiakinori/NemakiWare/core/src/main/java/jp/aegif/nemaki'
    total_removed = 0
    files_modified = 0

    for root, dirs, files in os.walk(base_dir):
        for file in files:
            if file.endswith('.java') and not file.endswith('.bak'):
                filepath = os.path.join(root, file)
                removed = remove_debug_from_file(filepath)
                if removed > 0:
                    total_removed += removed
                    files_modified += 1

    print(f"\n📊 Summary:")
    print(f"   Files modified: {files_modified}")
    print(f"   Debug lines removed: {total_removed}")

if __name__ == '__main__':
    main()
