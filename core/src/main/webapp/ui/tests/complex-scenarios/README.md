# Complex Scenario Tests for NemakiWare

This directory contains comprehensive Playwright E2E tests for complex scenarios involving multiple NemakiWare features working together. These tests validate the consistency and integrity of the system when performing operations that span across different functional areas.

## Test Suites Overview

### 1. Custom Type with Versioning and Search (`custom-type-versioning-search.spec.ts`)

**Purpose**: Validates the complete workflow of custom type creation, document registration with validation, search filtering by custom properties, and version management.

**Scenarios Tested**:
- Create a custom document type with required and searchable properties
- Create a document with the custom type and fill required properties
- Search for the document using custom property filters
- Update custom property values and verify search results change
- Create new versions and restore original property values
- Delete versions and verify search behavior changes accordingly

**CMIS Concepts**: Custom Type Definition, Property Validation, CMIS SQL Query, Document Versioning, Solr Indexing

---

### 2. ACL Inheritance and Custom Type Interaction (`acl-custom-type-interaction.spec.ts`)

**Purpose**: Validates the interaction between ACL (Access Control List) permissions and custom type documents.

**Scenarios Tested**:
- Create a test folder for ACL testing
- Create a document inside the test folder
- Set ACL permissions on the folder
- Break ACL inheritance on the document
- Verify the document has independent ACL settings

**CMIS Concepts**: ACL Management, ACL Inheritance, Permission Propagation

---

### 3. Secondary Type with Custom Properties (`secondary-type-properties.spec.ts`)

**Purpose**: Validates secondary type (aspect) functionality including application, property management, and search behavior.

**Scenarios Tested**:
- Create a secondary type with custom properties
- Create a test document
- Apply the secondary type to the document
- Search by secondary type properties
- Remove the secondary type and verify search behavior

**CMIS Concepts**: Secondary Type Definition (cmis:secondary), Aspect Application, Property Inheritance

---

### 4. Folder Hierarchy with Scoped Search (`folder-hierarchy-search.spec.ts`)

**Purpose**: Validates folder hierarchy operations and scoped search functionality.

**Scenarios Tested**:
- Create a folder hierarchy (root folder with subfolders)
- Create documents in different subfolders
- Search with folder scope (IN_TREE predicate)
- Move documents between folders
- Verify search results update after move operations

**CMIS Concepts**: Folder Hierarchy, Document Filing, IN_FOLDER/IN_TREE Predicates, moveObject Operation

---

### 5. Version and Property History Consistency (`version-property-history.spec.ts`)

**Purpose**: Validates the consistency between document versioning and property values across versions.

**Scenarios Tested**:
- Create initial document (Version 1.0)
- Check out and create Version 2.0 with different content
- Create Version 3.0 with different content
- View version history and verify all versions exist
- Delete latest version and verify rollback to previous version
- Verify document content matches previous version after rollback

**CMIS Concepts**: Version Series Management, PWC Operations, Property Persistence, Version History Navigation

---

### 6. Archive and Restore Consistency (`archive-restore-consistency.spec.ts`)

**Purpose**: Validates the archive (soft delete) and restore functionality.

**Scenarios Tested**:
- Create test folder and document
- Archive the document
- View archived document in archive/trash view
- Restore document from archive
- Verify restored document is back in original location with preserved properties
- Archive and permanently delete document

**CMIS Concepts**: Archive/Soft Delete, Restore from Archive, Permanent Deletion, Property Preservation

---

### 7. Type Management Consistency (`type-management-consistency.spec.ts`)

**Purpose**: Validates the consistency between type management operations and document operations.

**Scenarios Tested**:
- Create a custom document type with properties
- Create a document with the custom type
- Attempt to delete the custom type (should fail due to existing documents)
- Preview document with custom type and verify custom properties are displayed
- Delete the document and then successfully delete the custom type

**CMIS Concepts**: Type Definition Management, Type Mutability Constraints, Document-Type Relationships

---

## Test Environment

- **Server**: http://localhost:8080/core/ui/
- **Authentication**: admin:admin
- **Repository**: bedroom
- **Browsers**: Chromium, Firefox, WebKit, Mobile Chrome, Mobile Safari

## Running the Tests

```bash
# Run all complex scenario tests
npx playwright test tests/complex-scenarios/

# Run a specific test file
npx playwright test tests/complex-scenarios/custom-type-versioning-search.spec.ts

# Run with UI mode for debugging
npx playwright test tests/complex-scenarios/ --ui

# Run with headed browser
npx playwright test tests/complex-scenarios/ --headed
```

## Test Design Principles

1. **Serial Execution**: Tests within each suite run serially (`test.describe.configure({ mode: 'serial' })`) because they share state and depend on previous test results.

2. **Unique Naming**: All test data uses UUID-based unique naming to prevent conflicts in parallel test execution across different browser profiles.

3. **Comprehensive Cleanup**: Each test suite includes an `afterAll` hook that cleans up all test data (documents, folders, custom types) to prevent test pollution.

4. **Mobile Browser Support**: Tests detect mobile viewports and apply appropriate handling (force clicks, sidebar management).

5. **Graceful Degradation**: Tests use conditional skipping when UI features are not available, allowing the test suite to adapt to different implementation states.

6. **Detailed Logging**: Tests include comprehensive console logging for debugging in CI/CD environments.

## Common Patterns

### Authentication
```typescript
const authHelper = new AuthHelper(page);
await authHelper.login();
```

### Document Upload
```typescript
const testHelper = new TestHelper(page);
await testHelper.uploadDocument(fileName, content, isMobile);
```

### Mobile Browser Detection
```typescript
const viewportSize = page.viewportSize();
const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;
```

### Ant Design Component Interaction
```typescript
// Select dropdown
const select = page.locator('.ant-select').first();
await select.click();
await page.waitForTimeout(300);
const option = page.locator('.ant-select-item-option').filter({ hasText: 'Option Text' });
await option.click();

// Modal handling
const modal = page.locator('.ant-modal:visible');
await expect(modal).toBeVisible({ timeout: 5000 });
```

## Troubleshooting

### Tests Skip Due to Missing UI Elements
- Check if the feature is implemented in the current UI version
- Verify the server is running and accessible
- Check browser console for JavaScript errors

### Tests Fail Due to Timing Issues
- Increase `waitForTimeout` values for slow CI environments
- Add explicit waits for specific elements before interaction
- Check Solr indexing delays for search-related tests

### Cleanup Failures
- Check if test data was created with expected names
- Verify permissions allow deletion
- Check for orphaned documents blocking type deletion
