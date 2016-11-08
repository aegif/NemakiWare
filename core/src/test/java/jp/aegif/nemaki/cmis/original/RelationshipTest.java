package jp.aegif.nemaki.cmis.original;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.junit.Test;
import org.springframework.util.Assert;

public class RelationshipTest extends TestBase{
	@Test
	public void cascadeTest() throws InterruptedException{
		String folderId = createTestFolder();
		String docId1 = createDocument(folderId, "test1.txt", "This is test");
		String docId2 = createDocument(folderId, "test2.txt", "This is test");


		Map<String, String> relProps = new HashMap<String, String>();
		relProps.put(PropertyIds.OBJECT_TYPE_ID, "nemaki:parentChildRelationship");
		relProps.put(PropertyIds.NAME, "テスト");
		relProps.put("cmis:sourceId", docId1);
		relProps.put("cmis:targetId", docId2);

		session.createRelationship(null);

		session.delete(new ObjectIdImpl(docId1));

		CmisObject child = session.getObject(docId2);
		Assert.isNull(child);

	}

	@Test
	public void nonCascadeTest() throws InterruptedException{
		String folderId = createTestFolder();
		String docId1 = createDocument(folderId, "test1.txt", "This is test");
		String docId2 = createDocument(folderId, "test2.txt", "This is test");


		Map<String, String> relProps = new HashMap<String, String>();
		relProps.put(PropertyIds.OBJECT_TYPE_ID, "nemaki:bidirectionalRelationship");
		relProps.put(PropertyIds.NAME, "テスト");
		relProps.put("cmis:sourceId", docId1);
		relProps.put("cmis:targetId", docId2);

		session.createRelationship(null);

		session.createRelationship(null);

		session.delete(new ObjectIdImpl(docId1));

		CmisObject child = session.getObject(docId2);
		Assert.notNull(child);

	}
}
