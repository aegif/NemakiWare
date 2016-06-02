package jp.aegif.nemaki.test.nemaki;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Test;

public class MultiThreadTest extends TestBase{
	
	@Test
	public void checkOutTest_single(){
		String folderId = createTestFolder();
		String docId = createDocument(folderId, "test.txt", "This is test");
		Document doc = (Document) session.getObject(docId);
		
		long start = System.currentTimeMillis();
		doc.checkOut();
		long end = System.currentTimeMillis();
		
		System.out.println("start=" + start + ", end=" + end);
		
		Folder folder = (Folder) session.getObject(folderId);
		folder.deleteTree(true, UnfileObject.DELETE, true);
	}
	
	@Test
	public void checkOutTest_All() throws InterruptedException, ExecutionException{
		//document ids
		Folder folder = (Folder) session.getObject(testFolderId);
		OperationContext oc = simpleOperationContext();
		oc.setMaxItemsPerPage(Integer.MAX_VALUE);
		
		ItemIterable<CmisObject> children = folder.getChildren(oc);
		
		Iterator<CmisObject> itr = children.iterator();
		List<Document> docs = new ArrayList<>();
		while(itr.hasNext()){
			CmisObject child = itr.next();
			if(child.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT){
				docs.add((Document)child);
			}
		}
		
		//checkout
		List<Thread> threads = new ArrayList<>();
		Integer taskNum = 1;
		for(Document doc : docs){
			threads.add(new Thread(new CheckOutTask(taskNum, doc)));
			taskNum++;
		}
		
		for(Thread thread : threads){
			thread.start();
		}
		
		for(Thread thread : threads){
			thread.join();
		}
		
		System.out.println("test end: " + threads.size() + " threads processed");
	}
	
	public class CheckOutTask implements Runnable{
		private int taskId;
		private Document doc;
		
		public CheckOutTask(int taskId, Document doc){
			this.taskId = taskId;
			this.doc = doc;
		}

		@Override
		public void run() {
			DateTime start = new DateTime();
			
			ObjectId objectId = doc.checkOut();
			
			DateTime end = new DateTime();
			
			Duration duration = new Duration(start, end);
			
			System.out.println(taskId + ", " + duration.getMillis()  + ", " + doc.getId() + ", " + start + ", " + end);
		}
	}
	
	@Test
	public void readTest_All() throws InterruptedException{
		//document ids
		Folder folder = (Folder) session.getObject(testFolderId);
		OperationContext oc = simpleOperationContext();
		oc.setMaxItemsPerPage(Integer.MAX_VALUE);
		
		ItemIterable<CmisObject> children = folder.getChildren(oc);
		
		Iterator<CmisObject> itr = children.iterator();
		List<Document> docs = new ArrayList<>();
		while(itr.hasNext()){
			CmisObject child = itr.next();
			if(child.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT){
				docs.add((Document)child);
			}
		}
		
		//checkout
		List<Thread> threads = new ArrayList<>();
		Integer taskNum = 1;
		for(Document doc : docs){
			threads.add(new Thread(new ReadTask(taskNum, doc.getId())));
			taskNum++;
		}
		
		for(Thread thread : threads){
			thread.start();
		}
		
		for(Thread thread : threads){
			thread.join();
		}
		
		System.out.println("test end: " + threads.size() + " threads processed");
	}
	
	public class ReadTask implements Runnable{
		private int taskId;
		private String objectId;
		
		public ReadTask(int taskId, String objectId){
			this.taskId = taskId;
			this.objectId = objectId;
		}

		@Override
		public void run() {
			System.out.println(taskId + ":start");
			
			OperationContext oc = simpleOperationContext();
			oc.setCacheEnabled(false);
			
			DateTime start = new DateTime();
			
			Document doc = (Document) session.getObject(objectId, oc);
			
			DateTime end = new DateTime();
			
			System.out.println(taskId + ":end");
			
			Duration duration = new Duration(start, end);
			
			System.out.println(taskId + ", " + duration.getMillis()  + ", " + doc.getId() + ", " + start + ", " + end);
		}
	}
}
