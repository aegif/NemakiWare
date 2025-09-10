package jp.aegif.nemaki.cmis.tck.tests;

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
import org.junit.Ignore;

import jp.aegif.nemaki.cmis.tck.TckSuite;

// @Ignore("TCK tests temporarily disabled due to data visibility issues - see CLAUDE.md") - ENABLED: Data visibility issues resolved
public class CrudTestGroup extends TckSuite{
	
	@Test
	public void createAndDeleteFolderTest() throws Exception{
		CreateAndDeleteFolderTest test = new CreateAndDeleteFolderTest();
		run(test);
	}
	
	@Test
	public void createAndDeleteDocumentTest() throws Exception{
		CreateAndDeleteDocumentTest test = new CreateAndDeleteDocumentTest();
		run(test);
	}
	
	@Test
	public void createBigDocument() throws Exception{
		CreateBigDocument test = new CreateBigDocument();
		run(test);
	}
	
	@Test
	public void createDocumentWithoutContent() throws Exception{
		CreateDocumentWithoutContent test = new CreateDocumentWithoutContent();
		run(test);
	}
	
	@Test
	public void createInvalidTypeTest() throws Exception{
		CreateInvalidTypeTest test = new CreateInvalidTypeTest();
		run(test);
	}
	
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
	public void createAndDeleteItemTest() throws Exception{
		CreateAndDeleteItemTest test = new CreateAndDeleteItemTest();
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
	public void bulkUpdatePropertiesTest() throws Exception{
		BulkUpdatePropertiesTest test = new BulkUpdatePropertiesTest();
		run(test);
	}
	
	@Test
	public void setAndDeleteContentTest() throws Exception{
		SetAndDeleteContentTest test = new SetAndDeleteContentTest();
		run(test);
	}
	
	@Test
	public void changeTokenTest() throws Exception{
		ChangeTokenTest test = new ChangeTokenTest();
		run(test);
	}
	
	@Test
	public void contentRangesTest() throws Exception{
		ContentRangesTest test = new ContentRangesTest();
		run(test);
	}
	@Test
	public void copyTest() throws Exception{
		CopyTest test = new CopyTest();
		run(test);
	}
	
	@Test
	public void moveTest() throws Exception{
		MoveTest test = new MoveTest();
		run(test);
	}
	
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
