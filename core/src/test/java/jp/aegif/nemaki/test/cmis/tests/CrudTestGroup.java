package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.impl.JUnitHelper;
import org.apache.chemistry.opencmis.tck.tests.crud.BulkUpdatePropertiesTest;
import org.apache.chemistry.opencmis.tck.tests.crud.ChangeTokenTest;
import org.apache.chemistry.opencmis.tck.tests.crud.ContentRangesTest;
import org.apache.chemistry.opencmis.tck.tests.crud.CopyTest;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateAndDeleteDocumentTest;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateAndDeleteFolderTest;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateAndDeleteItemTest;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateAndDeleteRelationshipTest;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateBigDocument;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateDocumentWithoutContent;
import org.apache.chemistry.opencmis.tck.tests.crud.CreateInvalidTypeTest;
import org.apache.chemistry.opencmis.tck.tests.crud.DeleteTreeTest;
import org.apache.chemistry.opencmis.tck.tests.crud.MoveTest;
import org.apache.chemistry.opencmis.tck.tests.crud.NameCharsetTest;
import org.apache.chemistry.opencmis.tck.tests.crud.OperationContextTest;
import org.apache.chemistry.opencmis.tck.tests.crud.PropertyFilterTest;
import org.apache.chemistry.opencmis.tck.tests.crud.SetAndDeleteContentTest;
import org.apache.chemistry.opencmis.tck.tests.crud.UpdateSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.crud.WhitespaceInNameTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;

public class CrudTestGroup extends TestGroupBase{
	
	@Test
	public void createAndDeleteFolderTest() throws Exception{
		CreateAndDeleteFolderTest test = new CreateAndDeleteFolderTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void createAndDeleteDocumentTest() throws Exception{
		CreateAndDeleteDocumentTest test = new CreateAndDeleteDocumentTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void createBigDocument() throws Exception{
		CreateBigDocument test = new CreateBigDocument();
		JUnitHelper.run(test);
	}
	
	@Test
	public void createDocumentWithoutContent() throws Exception{
		CreateDocumentWithoutContent test = new CreateDocumentWithoutContent();
		JUnitHelper.run(test);
	}
	
	@Test
	public void createInvalidTypeTest() throws Exception{
		CreateInvalidTypeTest test = new CreateInvalidTypeTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void nameCharsetTest() throws Exception{
		NameCharsetTest test = new NameCharsetTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void whitespaceInNameTest() throws Exception{
		WhitespaceInNameTest test = new WhitespaceInNameTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void createAndDeleteRelationshipTest() throws Exception{
		CreateAndDeleteRelationshipTest test = new CreateAndDeleteRelationshipTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void createAndDeleteItemTest() throws Exception{
		CreateAndDeleteItemTest test = new CreateAndDeleteItemTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void propertyFilterTest() throws Exception{
		PropertyFilterTest test = new PropertyFilterTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void updateSmokeTest() throws Exception{
		UpdateSmokeTest test = new UpdateSmokeTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void bulkUpdatePropertiesTest() throws Exception{
		BulkUpdatePropertiesTest test = new BulkUpdatePropertiesTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void setAndDeleteContentTest() throws Exception{
		SetAndDeleteContentTest test = new SetAndDeleteContentTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void changeTokenTest() throws Exception{
		ChangeTokenTest test = new ChangeTokenTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void contentRangesTest() throws Exception{
		ContentRangesTest test = new ContentRangesTest();
		JUnitHelper.run(test);
	}
	@Test
	public void copyTest() throws Exception{
		CopyTest test = new CopyTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void moveTest() throws Exception{
		MoveTest test = new MoveTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void deleteTreeTest() throws Exception{
		DeleteTreeTest test = new DeleteTreeTest();
		JUnitHelper.run(test);
	}
	
	@Test
	public void operationContextTest() throws Exception{
		OperationContextTest test = new OperationContextTest();
		JUnitHelper.run(test);
	}
}
