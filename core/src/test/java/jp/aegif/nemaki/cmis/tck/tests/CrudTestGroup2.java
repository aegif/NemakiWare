package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.crud.CreateAndDeleteRelationshipTest;
import org.apache.chemistry.opencmis.tck.tests.crud.DeleteTreeTest;
import org.apache.chemistry.opencmis.tck.tests.crud.MoveTest;
import org.apache.chemistry.opencmis.tck.tests.crud.NameCharsetTest;
import org.apache.chemistry.opencmis.tck.tests.crud.OperationContextTest;
import org.apache.chemistry.opencmis.tck.tests.crud.PropertyFilterTest;
import org.apache.chemistry.opencmis.tck.tests.crud.SetAndDeleteContentTest;
import org.apache.chemistry.opencmis.tck.tests.crud.UpdateSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.crud.WhitespaceInNameTest;
import org.junit.Test;
import org.junit.Ignore;

import jp.aegif.nemaki.cmis.tck.TestGroupBase;

/**
 * CRUD Test Group Part 2 (9 tests)
 * Split from original CrudTestGroup to avoid timeout issues
 * Tests: Name handling, updates, relationships, tree operations, and filters
 *
 * UPDATE (2025-10-11): Cleanup logic re-enabled - testing if resource exhaustion is resolved
 * Previous issue was caused by disabled cleanupTckTestArtifacts() at TestGroupBase.java:179
 * Re-enabled cleanup should prevent test artifact accumulation
 * Individual successful tests: whitespaceInNameTest, createAndDeleteRelationshipTest,
 * propertyFilterTest, updateSmokeTest, setAndDeleteContentTest, moveTest, operationContextTest (7/9 pass individually)
 */
// @Ignore removed to test cleanup fix - was: "Cumulative resource exhaustion - individual tests pass, class execution times out"
public class CrudTestGroup2 extends TestGroupBase {

	// @Ignore removed to test with cleanup fix - was: "Timeout issue - charset name handling causes indefinite hang"
	@Test
	public void nameCharsetTest() throws Exception{
		NameCharsetTest test = new NameCharsetTest();
		run(test);
	}

	@Test
	public void whitespaceInNameTest() throws Exception{
		WhitespaceInNameTest test = new WhitespaceInNameTest();
		run(test);
	}

	@Test
	public void createAndDeleteRelationshipTest() throws Exception{
		CreateAndDeleteRelationshipTest test = new CreateAndDeleteRelationshipTest();
		run(test);
	}

	@Test
	public void propertyFilterTest() throws Exception{
		PropertyFilterTest test = new PropertyFilterTest();
		run(test);
	}

	@Test
	public void updateSmokeTest() throws Exception{
		UpdateSmokeTest test = new UpdateSmokeTest();
		run(test);
	}

	@Test
	public void setAndDeleteContentTest() throws Exception{
		SetAndDeleteContentTest test = new SetAndDeleteContentTest();
		run(test);
	}

	@Test
	public void moveTest() throws Exception{
		MoveTest test = new MoveTest();
		run(test);
	}

	// @Ignore removed to test with cleanup fix - was: "Timeout issue - tree deletion hangs indefinitely"
	@Test
	public void deleteTreeTest() throws Exception{
		DeleteTreeTest test = new DeleteTreeTest();
		run(test);
	}

	@Test
	public void operationContextTest() throws Exception{
		OperationContextTest test = new OperationContextTest();
		run(test);
	}
}
