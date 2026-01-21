package jp.aegif.nemaki.cmis.original;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.Ignore;

// ALL TESTS ENABLED (2025-12-16): All multi-thread tests now use versionable documents (nemaki:document)
// for proper checkOut/checkIn support. Tests: readTest_All, checkOutTest_single, checkOutTest, checkInTest, copyTest, moveTest
public class MultiThreadTest extends TestBase{
	// @Ignore removed - re-enabled for comprehensive test verification (2025-12-16)
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
		
		//read
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
			
			System.out.println(String.format(logFormat, taskId, duration.getMillis(), doc.getId(), start, end));
		}
	}
	
	// @Ignore removed - now uses versionable nemaki:document type (2025-12-16)
	@Test(timeout = 60000) // 60秒タイムアウト設定（バージョニング操作用に延長）
	public void checkOutTest_single(){
		String folderId = null;
		try {
			System.out.println("=== checkOutTest_single started ===");

			folderId = createTestFolder();
			System.out.println("Created test folder: " + folderId);

			String docId = createVersionableDocument(folderId, "test.txt", "This is test");
			System.out.println("Created test document: " + docId);
			
			Document doc = (Document) session.getObject(docId);
			System.out.println("Retrieved document object successfully");
			
			long start = System.currentTimeMillis();
			ObjectId checkedOutId = doc.checkOut();
			long end = System.currentTimeMillis();
			
			System.out.println("CheckOut completed:");
			System.out.println("  - Duration: " + (end - start) + "ms");
			System.out.println("  - Original ID: " + doc.getId()); 
			System.out.println("  - Checked out ID: " + checkedOutId.getId());
			
			System.out.println("=== checkOutTest_single completed successfully ===");
			
		} catch (Exception e) {
			System.err.println("checkOutTest_single failed: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException("Test failed", e);
		} finally {
			// クリーンアップ
			if (folderId != null) {
				try {
					Folder folder = (Folder) session.getObject(folderId);
					folder.deleteTree(true, UnfileObject.DELETE, true);
					System.out.println("Cleanup completed for folder: " + folderId);
				} catch (Exception cleanupEx) {
					System.err.println("Cleanup failed: " + cleanupEx.getMessage());
				}
			}
		}
	}
	
	// @Ignore removed - re-enabled with versionable documents support (2025-12-16)
	@Test
	public void checkOutTest() throws InterruptedException, ExecutionException{
		//document ids();
		List<Document> docs = getDocs(testFolderId);
		
		ExecutorService es = Executors.newFixedThreadPool(10);
		CompletionService<Object> completionService = new ExecutorCompletionService<Object>(es); 
		
		Integer taskNum = 1;
		for(Document doc : docs){
			completionService.submit(new CheckOutTask(taskNum, doc), null);
			taskNum++;
		}
		
		es.shutdown(); 
		for (int i = 0; i < docs.size(); i++) { 
			Future<Object> future = completionService.take(); 
			Object result = future.get(); 
				
		}
		
		System.out.println("test end: " + taskNum + " threads processed");
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
			
			System.out.println(String.format(logFormat, taskId, duration.getMillis(), doc.getId(), start, end));
		}
	}
	
	// @Ignore removed - re-enabled with versionable documents support (2025-12-16)
	@Test
	public void checkInTest() throws InterruptedException, ExecutionException{
		checkOutTest();
		
		System.out.println("---checkout test finished---");
		System.out.println("---checkin test started---");
		
		//document ids
		List<Document> docs = getDocs(testFolderId);
		
		ExecutorService es = Executors.newFixedThreadPool(10);
		CompletionService<Object> completionService = new ExecutorCompletionService<Object>(es); 
		
		Integer taskNum = 1;
		for(Document doc : docs){
			completionService.submit(new CheckInTask(taskNum, doc), null);
			taskNum++;
		}
		
		es.shutdown(); 
		for (int i = 0; i < docs.size(); i++) { 
			Future<Object> future = completionService.take(); 
			Object result = future.get(); 
				
		}
		
		System.out.println("test end: " + taskNum + " threads processed");
	}

	public class CheckInTask implements Runnable{
		private int taskId;
		private Document doc;
		
		public CheckInTask(int taskId, Document doc){
			this.taskId = taskId;
			this.doc = doc;
		}

		@Override
		public void run() {
			DateTime start = new DateTime();
			
			ContentStream contentStream = new ContentStreamImpl(doc.getName(), "text/plain", "this is checked in.");
			ObjectId objectId = doc.checkIn(true, null, contentStream, "checkIn comment");
			
			DateTime end = new DateTime();
			
			Duration duration = new Duration(start, end);
			
			System.out.println(String.format(logFormat, taskId, duration.getMillis(), doc.getId(), start, end));
		}
	}
	
	// @Ignore removed - re-enabled for comprehensive test verification (2025-12-16)
	@Test
	public void copyTest() throws InterruptedException{
		//document ids
		List<Document> docs = getDocs(testFolderId);
		String targetFolderId = createTestFolder();
		System.out.println("taget folder: " + targetFolderId);
		
		List<Thread> threads = new ArrayList<>();
		Integer taskNum = 1;
		for(Document doc : docs){
			threads.add(new Thread(new CopyTask(taskNum, doc, new ObjectIdImpl(targetFolderId))));
			taskNum++;
		}
		
		for(Thread thread : threads){
			thread.start();
		}
		
		for(Thread thread : threads){
			thread.join();
		}
		
		Folder targetFolder = (Folder) session.getObject(targetFolderId);
		final long itemNum = targetFolder.getChildren().getPageNumItems();
		targetFolder.deleteTree(true, UnfileObject.DELETE, true);
		
		
		System.out.println("copy test finished. " + itemNum + "items");
	}
	
	private static class CopyTask implements Runnable{
		private int taskId;
		private Document doc;
		private ObjectId targetFolderId;
		
		public CopyTask(int taskId, Document doc, ObjectId targetFolderId){
			this.taskId = taskId;
			this.doc = doc;
			this.targetFolderId = targetFolderId;
		}

		@Override
		public void run() {
			DateTime start = new DateTime();
			
			Document copied = doc.copy(targetFolderId);
			
			DateTime end = new DateTime();
			Duration duration = new Duration(start, end);
			
			System.out.println(String.format(logFormat, taskId,  duration.getMillis(), doc.getId(), start, end));
		}
	}
	
	// @Ignore removed - re-enabled for comprehensive test verification (2025-12-16)
	@Test
	public void moveTest() throws InterruptedException{
		//document ids
		List<Document> docs = getDocs(testFolderId);
		String targetFolderId = createTestFolder();
		System.out.println("taget folder: " + targetFolderId);
		
		List<Thread> threads = new ArrayList<>();
		Integer taskNum = 1;
		for(Document doc : docs){
			threads.add(new Thread(new MoveTask(taskNum, doc, new ObjectIdImpl(testFolderId), new ObjectIdImpl(targetFolderId))));
			taskNum++;
		}
		
		for(Thread thread : threads){
			thread.start();
		}
		
		for(Thread thread : threads){
			thread.join();
		}
		
		Folder targetFolder = (Folder) session.getObject(targetFolderId);
		final long itemNum = targetFolder.getChildren().getPageNumItems();
		targetFolder.deleteTree(true, UnfileObject.DELETE, true);
		
		System.out.println("move test finished. " + itemNum + "items");
	}
	
	private static class MoveTask implements Runnable{
		private int taskId;
		private Document doc;
		private ObjectId sourceFolderId;
		private ObjectId targetFolderId;
		
		public MoveTask(int taskId, Document doc, ObjectId sourceFolderId, ObjectId targetFolderId){
			this.taskId = taskId;
			this.doc = doc;
			this.sourceFolderId = sourceFolderId;
			this.targetFolderId = targetFolderId;
		}

		@Override
		public void run() {
			DateTime start = new DateTime();
			
			FileableCmisObject moved = doc.move(sourceFolderId, targetFolderId);
			
			DateTime end = new DateTime();
			Duration duration = new Duration(start, end);
			
			System.out.println(String.format(logFormat, taskId,  duration.getMillis(), doc.getId(), start, end));
		}
	}
}
