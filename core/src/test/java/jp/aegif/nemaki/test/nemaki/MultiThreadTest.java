package jp.aegif.nemaki.test.nemaki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.node.ObjectNode;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.http.StdHttpClient.Builder;
import org.ektorp.impl.StdCouchDbInstance;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
		//document ids();
		List<Document> docs = getDocs(testFolderId);
		
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
	
	@Test
	public void checkOutTest_All_By_Executor() throws InterruptedException, ExecutionException{
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
			
			System.out.println(taskId + ", " + duration.getMillis()  + ", " + doc.getId() + ", " + start + ", " + end);
		}
	}
	
	
	public void checkInTest_All() throws InterruptedException, ExecutionException{
		checkOutTest_All();
		
		System.out.println("---checkout test finished---");
		System.out.println("---checkin test started---");
		
		//document ids
		List<Document> docs = getDocs(testFolderId);
		
		for(Document doc : docs){
			
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
	
	@Test
	public void copyTest_All() throws InterruptedException{
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
		targetFolder.deleteTree(true, UnfileObject.DELETE, true);
		
		
		System.out.println("copy test finished. target folder contains " + targetFolder.getChildren().getPageNumItems());
		
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
			System.out.println(taskId + ":start " + doc.getName());
			Document copy = doc.copy(targetFolderId);
			System.out.println(taskId + ":copied " + copy.getName());
		}
		
	}
	
	@Test
	public void tooManyThreadTest() throws InterruptedException{
		int threadNum = 500;
		
		//couch connector
		Builder builder = new StdHttpClient.Builder()
				.host("localhost")
				.port(5984)
				.maxConnections(1000)
				.cleanupIdleConnections(true);
		HttpClient httpClient = builder.build();
		CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);

		CouchDbConnector connector = dbInstance.createConnector("bedroom5", false);
		
		//threads
		List<Thread> threads = new ArrayList<>();
		Integer taskNum = 1;
		for(int i=0; i< threadNum; i++){
			threads.add(new Thread(new HttpCallTask(connector, "task" + i)));
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
	
	private class HttpCallTask implements Runnable{

		private CouchDbConnector connector;
		private String taskId;
		
		
		public HttpCallTask(CouchDbConnector connector, String taskId) {
			this.connector = connector;
			this.taskId = taskId;
		}
		
		@Override
		public void run() {
			
			DateTime start = new DateTime();
			
			
			ObjectMapper mapper = new ObjectMapper();
			com.fasterxml.jackson.databind.node.ObjectNode obj = mapper.createObjectNode().put("fuga", "hoge");
			connector.create(obj);
			obj.put("fuga", "piyo");
			connector.update(obj);
			
			DateTime end = new DateTime();
			
			Duration duration = new Duration(start, end);
			
			System.out.println(taskId + ", " + duration.getMillis()  + ", " + start + ", " + end);
			
		}
		
	}
}
