package jp.aegif.nemaki.test.nemaki;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.activation.FileTypeMap;

import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class TestBase {
	protected static Session session;
	protected static String testFolderId;
	
	@BeforeClass
	public static void before() throws Exception {
		session = SessionUtil.createCmisSession("bedroom", "admin", "admin");
		testFolderId = prepareData();
	}

	@AfterClass
	public static void after() throws Exception {
		Folder folder = (Folder) session.getObject(testFolderId);
		folder.deleteTree(true, UnfileObject.DELETE, true);
	}
	
	public static String prepareData() throws Exception{
		int itemNumber = 100;
		
		String rootFolderId = session.getRepositoryInfo().getRootFolderId();
		
		String testFolderId = createFolder(rootFolderId, "test_general_" + System.currentTimeMillis());
		
		List<CreateDocumentTask> tasks = new ArrayList<>();
		for(int i=1; i<=itemNumber; i++){
			tasks.add(new CreateDocumentTask("task_" + i , testFolderId, "task_" + i + ".txt", "これはテストです"));
		}
		
		List<Future<String>> _results = new ArrayList<>();
		ExecutorService executor = Executors.newCachedThreadPool();
		_results = executor.invokeAll(tasks);
		
		List<String> results = new ArrayList<>();
		for(Future<String> _result : _results){
			results.add(_result.get());
		}
		
		System.out.println("data initilization completed: " + results.size() + " items");
		return testFolderId;
	}
	
	public static String createFolder(String parentId, String name){
		Map<String, Object>map = new HashMap<>();
		map.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_FOLDER.value());
		map.put(PropertyIds.PARENT_ID, parentId);
		map.put(PropertyIds.NAME, name);
		ObjectId result = session.createFolder(map, new ObjectIdImpl(parentId));
		return result.getId();
	}
	
	private static class CreateDocumentTask implements Callable<String>{
		String taskId;
		String parentId;
		String name;
		String text;
		
		public CreateDocumentTask(String taskId, String parentId, String name, String text) {
			super();
			this.taskId = taskId;
			this.parentId = parentId;
			this.name = name;
			this.text = text;
		}
		@Override
		public String call() throws Exception {
			String objectId = createDocument(parentId, name, text);
			System.out.println(objectId + "(" + name + ") is created.");
			return objectId; 
		}
	}
	
	protected static OperationContext simpleOperationContext(String...filters ){
		OperationContextImpl oc = new OperationContextImpl();
		if(filters.length > 0){
			Set<String> _filters = new HashSet<String>(Arrays.asList(PropertyIds.OBJECT_ID));
			oc.setFilter(_filters);
		}
		oc.setIncludeAllowableActions(false);
		oc.setIncludeAcls(false);
		oc.setIncludePolicies(false);
		oc.setIncludeRelationships(IncludeRelationships.NONE);
		
		return oc;
	}
	
	public static String createTestFolder(){
		String rootFolderId = session.getRepositoryInfo().getRootFolderId();
		
		Map<String, Object>map = new HashMap<>();
		map.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_FOLDER.value());
		map.put(PropertyIds.PARENT_ID, rootFolderId);
		map.put(PropertyIds.NAME, "testFolder_" + System.currentTimeMillis());
		ObjectId result = session.createFolder(map, new ObjectIdImpl(rootFolderId));
		return result.getId();
	}
	
	public static String createDocument(String parentId, String name, File file){
		Map<String, Object>map = new HashMap<>();
		map.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value());
		map.put(PropertyIds.NAME, name);

		ContentStream contentStream = convertFileToContentStream(session, file);
		
		ObjectId objectId = session.createDocument(map, new ObjectIdImpl(parentId), contentStream, VersioningState.MAJOR);
	
		return objectId.getId();
	}
	
	public static String createDocument(String parentId, String name, String string){
		Map<String, Object>map = new HashMap<>();
		map.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value());
		map.put(PropertyIds.NAME, name);

		ContentStream contentStream = new ContentStreamImpl(name, "text/plain", string);
		
		ObjectId objectId = session.createDocument(map, new ObjectIdImpl(parentId), contentStream, VersioningState.MAJOR);
	
		return objectId.getId();
	}
	
	public static File convertInputStreamToFile(InputStream inputStream)
			throws IOException {

		File file = File.createTempFile(
				String.valueOf(System.currentTimeMillis()), null);
		file.deleteOnExit();

		OutputStream out = new FileOutputStream(file);
		try {
			int read = 0;
			byte[] bytes = new byte[1024];
			while ((read = inputStream.read(bytes)) != -1) {
				out.write(bytes, 0, read);
			}
		} catch (IOException e) {
			System.out.println(e.getMessage());
		}finally{
			inputStream.close();
			
			out.close();
		}

		return file;
	}
	
	public static ContentStream convertFileToContentStream(Session session,
			File file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		FileTypeMap filetypeMap = FileTypeMap.getDefaultFileTypeMap();
        String mimetype = filetypeMap.getContentType(file);
		
		ContentStream cs = session.getObjectFactory().createContentStream(
				file.getName(), file.length(), mimetype, fis);
		return cs;
	}
}
