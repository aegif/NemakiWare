#!/usr/bin/env python3
"""
Replace inline mobile sidebar handling with TestHelper.closeMobileSidebar()
"""
import re
import os
import sys

# Pattern 1: Full sidebar closing block in beforeEach
# This replaces:
#   const viewportSize = page.viewportSize();
#   const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;
#   if (isMobileChrome) {
#     const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
#     if (await menuToggle.count() > 0) {
#       await menuToggle.first().click({ timeout: 3000 });
#       await page.waitForTimeout(500);
#     }
#   }
# With:
#   await testHelper.closeMobileSidebar(browserName);

# Pattern 1a: Simple click with optional .catch()
SIDEBAR_PATTERN_SIMPLE = re.compile(
    r'''(\s*)const viewportSize = page\.viewportSize\(\);\s*\n'''
    r'''\s*const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize\.width <= 414;\s*\n'''
    r'''\s*\n?'''
    r'''\s*if \(isMobileChrome\) \{\s*\n'''
    r'''\s*const menuToggle = page\.locator\('button\[aria-label="menu-fold"\], button\[aria-label="menu-unfold"\]'\);\s*\n'''
    r'''\s*if \(await menuToggle\.count\(\) > 0\) \{\s*\n'''
    r'''\s*await menuToggle\.first\(\)\.click\(\{ timeout: 3000 \}\)(?:\.catch\(\(\) => \{\}\))?;\s*\n'''
    r'''\s*await page\.waitForTimeout\(500\);\s*\n'''
    r'''\s*\}\s*\n'''
    r'''\s*\}''',
    re.MULTILINE
)

# Pattern 1b: Try-catch block variation (flexible comment handling)
SIDEBAR_PATTERN_TRYCATCH = re.compile(
    r'''(\s*)(?:// .*\n\s*)?const viewportSize = page\.viewportSize\(\);\s*\n'''
    r'''\s*const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize\.width <= 414;\s*\n'''
    r'''(?:\s*\n)*'''  # Allow multiple blank lines
    r'''\s*if \(isMobileChrome\) \{\s*\n'''
    r'''\s*const menuToggle = page\.locator\('button\[aria-label="menu-fold"\], button\[aria-label="menu-unfold"\]'\);\s*\n'''
    r'''(?:\s*\n)?'''  # Allow optional blank line
    r'''\s*if \(await menuToggle\.count\(\) > 0\) \{\s*\n'''
    r'''\s*try \{\s*\n'''
    r'''\s*await menuToggle\.first\(\)\.click\(\{ timeout: 3000 \}\);\s*\n'''
    r'''\s*await page\.waitForTimeout\(500\);\s*\n'''
    r'''\s*\} catch(?:\s*\([^)]*\))? \{\s*\n'''
    r'''\s*// .*\n'''
    r'''\s*\}\s*\n'''
    r'''\s*\}\s*\n'''
    r'''\s*\}''',
    re.MULTILINE
)

# Pattern 1c: With else block for alternative toggle (simple)
SIDEBAR_PATTERN_ELSE = re.compile(
    r'''(\s*)(?:// .*\n\s*)?const viewportSize = page\.viewportSize\(\);\s*\n'''
    r'''\s*const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize\.width <= 414;\s*\n'''
    r'''\s*\n?'''
    r'''\s*if \(isMobileChrome\) \{\s*\n'''
    r'''\s*const menuToggle = page\.locator\('button\[aria-label="menu-fold"\], button\[aria-label="menu-unfold"\]'\);\s*\n'''
    r'''\s*if \(await menuToggle\.count\(\) > 0\) \{\s*\n'''
    r'''\s*await menuToggle\.first\(\)\.click\(\{ timeout: 3000 \}\);\s*\n'''
    r'''\s*await page\.waitForTimeout\(500\);\s*\n'''
    r'''\s*\} else \{\s*\n'''
    r'''(?:\s*.*\n)*?'''
    r'''\s*\}\s*\n'''
    r'''\s*\}''',
    re.MULTILINE
)

# Pattern 1d: Try-catch with else block - most complex variation
SIDEBAR_PATTERN_TRYCATCH_ELSE = re.compile(
    r'''(\s*)(?:// .*\n\s*)?const viewportSize = page\.viewportSize\(\);\s*\n'''
    r'''\s*const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize\.width <= 414;\s*\n'''
    r'''\s*\n?'''
    r'''\s*if \(isMobileChrome\) \{\s*\n'''
    r'''\s*const menuToggle = page\.locator\('button\[aria-label="menu-fold"\], button\[aria-label="menu-unfold"\]'\);\s*\n'''
    r'''\s*if \(await menuToggle\.count\(\) > 0\) \{\s*\n'''
    r'''\s*try \{\s*\n'''
    r'''\s*await menuToggle\.first\(\)\.click\(\{ timeout: 3000 \}\);\s*\n'''
    r'''\s*await page\.waitForTimeout\(500\);\s*\n'''
    r'''\s*\} catch(?:\s*\([^)]*\))? \{\s*\n'''
    r'''\s*// .*\n'''
    r'''\s*\}\s*\n'''
    r'''\s*\} else \{\s*\n'''
    r'''(?:\s*.*\n)*?'''  # Match any lines in else block
    r'''\s*\}\s*\n'''
    r'''\s*\}''',
    re.MULTILINE
)

# Pattern 1e: Flexible pattern with inline comments (handles document-management.spec.ts style)
SIDEBAR_PATTERN_FLEXIBLE = re.compile(
    r'''(\s*)(?:// .*\n\s*)?const viewportSize = page\.viewportSize\(\);\s*\n'''
    r'''\s*const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize\.width <= 414;\s*\n'''
    r'''(?:\s*\n)*'''  # Allow any blank lines
    r'''\s*if \(isMobileChrome\) \{\s*\n'''
    r'''(?:\s*// .*\n)?'''  # Optional comment
    r'''\s*const menuToggle = page\.locator\('button\[aria-label="menu-fold"\], button\[aria-label="menu-unfold"\]'\);\s*\n'''
    r'''(?:\s*\n)?'''  # Optional blank line
    r'''\s*if \(await menuToggle\.count\(\) > 0\) \{\s*\n'''
    r'''\s*try \{\s*\n'''
    r'''\s*await menuToggle\.first\(\)\.click\(\{ timeout: 3000 \}\);\s*\n'''
    r'''\s*await page\.waitForTimeout\(500\);(?:.*\n)'''  # Allow inline comment
    r'''\s*\} catch(?:\s*\([^)]*\))? \{\s*\n'''
    r'''\s*// .*\n'''
    r'''\s*\}\s*\n'''
    r'''\s*\} else \{\s*\n'''
    r'''(?:\s*.*\n)*?'''  # Match any lines in else block
    r'''\s*\}\s*\n'''
    r'''\s*\}''',
    re.MULTILINE
)

def replace_sidebar_pattern(content):
    """Replace inline sidebar handling with closeMobileSidebar()"""
    def replacer(match):
        indent = match.group(1)
        return f"{indent}await testHelper.closeMobileSidebar(browserName);"

    # Try patterns in order of complexity (most specific first)
    # Flexible pattern (handles comments and blank lines)
    result = SIDEBAR_PATTERN_FLEXIBLE.sub(replacer, content)
    # Try-catch with else block (most complex)
    result = SIDEBAR_PATTERN_TRYCATCH_ELSE.sub(replacer, result)
    # Then else block pattern
    result = SIDEBAR_PATTERN_ELSE.sub(replacer, result)
    # Then try-catch pattern
    result = SIDEBAR_PATTERN_TRYCATCH.sub(replacer, result)
    # Finally simple pattern
    result = SIDEBAR_PATTERN_SIMPLE.sub(replacer, result)
    return result


# Pattern for inline isMobile with viewportSize variable
ISMOBILE_WITH_VIEWPORT = re.compile(
    r'''const viewportSize = page\.viewportSize\(\);\s*\n'''
    r'''\s*const isMobile = browserName === 'chromium' && viewportSize && viewportSize\.width <= 414;''',
    re.MULTILINE
)

# Pattern for inline isMobile without viewportSize variable (one-liner)
ISMOBILE_ONELINER = re.compile(
    r'''const isMobile = browserName === 'chromium' && page\.viewportSize\(\)\?\.width <= 414;'''
)

def replace_ismobile_pattern(content):
    """Replace inline isMobile checks with testHelper.isMobile()"""
    replacement = 'const isMobile = testHelper.isMobile(browserName);'

    result = ISMOBILE_WITH_VIEWPORT.sub(replacement, content)
    result = ISMOBILE_ONELINER.sub(replacement, result)
    return result


def process_file(filepath):
    """Process a single file"""
    with open(filepath, 'r', encoding='utf-8') as f:
        original = f.read()

    modified = replace_sidebar_pattern(original)
    modified = replace_ismobile_pattern(modified)

    if modified != original:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(modified)
        return True
    return False


def main():
    tests_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__))) + '/tests'

    modified_files = []

    for root, dirs, files in os.walk(tests_dir):
        for filename in files:
            if filename.endswith('.spec.ts'):
                filepath = os.path.join(root, filename)
                if process_file(filepath):
                    modified_files.append(filepath)

    print(f"Modified {len(modified_files)} files:")
    for f in modified_files:
        print(f"  - {os.path.relpath(f, tests_dir)}")

    return len(modified_files)


if __name__ == '__main__':
    count = main()
    sys.exit(0 if count >= 0 else 1)
