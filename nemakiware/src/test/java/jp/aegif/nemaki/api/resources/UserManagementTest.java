package jp.aegif.nemaki.api.resources;

import static org.junit.Assert.assertEquals;


import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.util.Assert;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;

/**
 * Unit Tests for User Management REST service
 * @author T.Totani
 *
 */
public class UserManagementTest extends AbstractResourceTest {

	public UserManagementTest() throws Exception {
		super();
	}
	
	private ClientResponse createTestUser(Client client) {
		return createUser(client, "ut_user1", "name_ut_user1" ,"pw_name_ut_user1", "fn", "ln", "ut_user1@localhost.localdomain");
	}
    
	private ClientResponse createUser(Client client, String userId, String name, String password, String firstName, String lastName, String email) {
		MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
		formData.add("name",name);
		formData.add("password", password);
		formData.add("firstName", firstName);
		formData.add("lastName", lastName);
		formData.add("email", email);
		
		WebResource webResource = client
				.resource("http://localhost:9998/Nemaki/user/create/" + userId);
		ClientResponse response = webResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE).post(ClientResponse.class, formData);
		return response;
	}
	
	private JSONObject listUsers(Client client) {
		WebResource webResource = client
		.resource("http://localhost:9998/Nemaki/user/list");
		ClientResponse response =
			webResource
			//.header("Authorization", "token=" + encodeBase64("admin:admin"))
			.accept(MediaType.APPLICATION_JSON)
			.get(ClientResponse.class);
		
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		return retJson;
	}
	
	private JSONObject findUser(Client client, String userId) {
		JSONObject retJson = this.listUsers(client);
		JSONArray users = (JSONArray)retJson.get("users");
		
		for(int i = 0 ; i < users.size(); i++ ) {
			JSONObject userElement = (JSONObject)users.get(i);
			String foundUserId = (String)userElement.get("userId");
			if ( foundUserId.equals(userId) ) {
				return userElement;
			}
		}
		return null;
	}
	
	private ClientResponse deleteUser(Client client, String userId) {
		WebResource webResource = client.resource("http://localhost:9998/Nemaki/user/delete/" + userId);
		ClientResponse response = webResource
		//.header("Authorization", "token=" + encodeBase64("admin:admin"))
		.delete(ClientResponse.class);
		return response;
	}
	
	private ClientResponse updateTestUser(Client client,String userId, String name, String password, String firstName, String lastName, String email) {
		MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
		formData.add("name",name);
		formData.add("password",password);
		formData.add("firstName",firstName);
		formData.add("lastName",lastName);
		formData.add("email", email);
		
		WebResource webResource = client.resource("http://localhost:9998/Nemaki/user/update/" + userId);
		ClientResponse response = webResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
				//.header("Authorization", "token=" + encodeBase64("admin:admin"))
				.accept(MediaType.APPLICATION_JSON)
				.put(ClientResponse.class, formData);
		return response;
	}
	
	private ClientResponse updatePassword(Client client, String userId, String password) {
		MultivaluedMap<String,String> formData = new MultivaluedMapImpl();
		formData.add("newPassword", password);
		
		WebResource webResource = client.resource("http://localhost:9998/Nemaki/user/updatePassword/" + userId);
		ClientResponse response = webResource.type(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
		//.header("Authorization", "token=" + encodeBase64("admin:admin"))
		.accept(MediaType.APPLICATION_JSON)
		.put(ClientResponse.class, formData);
		return response;
	}
	
	private JSONObject showUser(Client client, String userId) {
		WebResource webResource = client
		.resource("http://localhost:9998/Nemaki/user/show/" + userId);
		ClientResponse response =
			webResource
			//.header("Authorization", "token=" + encodeBase64("admin:admin"))
			.accept(MediaType.APPLICATION_JSON)
			.get(ClientResponse.class);
		
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		return retJson;
	}
	
    @Test
	public void testUserCreate() {
		Client client = Client.create();
		
		ClientResponse response;
		String entity;
		
		//check creating user already exists
		JSONObject userElement = this.findUser(client, "ut_user1");		
		if ( userElement != null ) {
			this.deleteUser(client, "ut_user1");
		}
		
		//create new user
		response = this.createTestUser(client);
		entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"success");
		
		//check created user is in the list 
		JSONObject foundUserElement = this.findUser(client, "ut_user1");
		Assert.notNull(foundUserElement);
		
		if ( foundUserElement != null ) {
			assertEquals("name_ut_user1", foundUserElement.get("userName")) ;
			assertEquals("fn", foundUserElement.get("firstName")) ;
			assertEquals("ln", foundUserElement.get("lastName")) ;
			assertEquals("ut_user1@localhost.localdomain", foundUserElement.get("email")) ;
		}
		
	}
    
    @Test
    public void testDuplicateUserCreate() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement == null ) {
    		this.createTestUser(client);
    	}
    	
    	ClientResponse response = this.createTestUser(client);
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"failure");
    }
    
    @Ignore
    @Test
    public void testCreateSameEmailUsers() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement != null ) {
    		this.deleteUser(client, "ut_user1");
    	}
    	this.createTestUser(client);
    	
    	userElement = this.findUser(client, "ut_user2");
    	if ( userElement != null) {
    		this.deleteUser(client, "ut_user2");
    	}
    	
    	ClientResponse response = 
    		this.createUser(client, "ut_user2", "name_ut_user2", "pw_ut2", "fn", "ln", "ut_user1@localhost.localdomain");
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"), "failure");
		
		userElement = this.findUser(client, "ut_user2");
		Assert.isNull(userElement);
    }
    
    @Ignore
    @Test
    public void testCreateSameName() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement != null ) {
    		this.deleteUser(client, "ut_user1");
    	}
    	this.createTestUser(client);
    	
    	userElement = this.findUser(client, "ut_user2");
    	if ( userElement != null) {
    		this.deleteUser(client, "ut_user2");
    	}
    	
    	ClientResponse response = 
    		this.createUser(client, "ut_user2", "name_ut_user1", "pw_ut2", "fn", "ln", "ut_user2@localhost.localdomain");
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"), "failure");
		
		userElement = this.findUser(client, "ut_user2");
		Assert.isNull(userElement);
    }
    
    @Test
    public void testUserUpdate() {
    	//create user first
		Client client = Client.create();
		
		ClientResponse response;
		String entity;
		
		//check creating user already exists
		JSONObject retJson = this.findUser(client, "ut_user1");

		if ( retJson != null ) {
			//do nothing	
		}
		else {
			//create test user
			response = this.createTestUser(client);
			entity = response.getEntity(String.class);
			retJson = (JSONObject)JSONValue.parse(entity);
			assertEquals(retJson.get("status"),"success");
		}
		
		//update user
		response = this.updateTestUser(client,"ut_user1", "name_ut_user1_update","pw_name_ut_user1_update","fn_update", "ln_update","ut_user1@localhost.localdomain.update" );
		
		entity = response.getEntity(String.class);
		retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"success");
		
		//check if properties are updated
		JSONObject foundUserElement = this.findUser(client, "ut_user1");
		Assert.notNull(foundUserElement);
		
		if ( foundUserElement != null ) {
			assertEquals("name_ut_user1_update", foundUserElement.get("userName")) ;
			assertEquals("fn_update", foundUserElement.get("firstName")) ;
			assertEquals("ln_update", foundUserElement.get("lastName")) ;
			assertEquals("ut_user1@localhost.localdomain.update", foundUserElement.get("email")) ;
		}	
    }
    
    @Test
    public void testNullUserUpdate() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement != null ) {
    		this.deleteUser(client, "ut_user1");
    	}
    	
		ClientResponse response = 
			this.updateTestUser(client,"ut_user1", "name_ut_user1_update","pw_name_ut_user1_update","fn_update", "ln_update","ut_user1@localhost.localdomain.update" );
		
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"failure");
    }
    
    @Test
    public void testUserDelete() {
    	Client client = new Client();
    	//create test user if not exists
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement == null ) {
    		this.createTestUser(client);
    	}
    	
    	ClientResponse response = this.deleteUser(client, "ut_user1");
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"success");
    	
    	userElement = this.findUser(client, "ut_user1");
    	Assert.isNull(userElement);
    }
    
    @Test
    public void testNullUserDelete() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement != null ) {
    		this.deleteUser(client, "ut_user1");
    	}
    	
		ClientResponse response = this.deleteUser(client, "ut_user1");
		
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"failure");
    }
    
    @Test 
    public void testUserUpdatePassword() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement == null ) {
    		this.createTestUser(client);
    	}
    	ClientResponse response = this.updatePassword(client, "ut_user1", "new_password");
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"success");
    }
    
    @Test
    public void testNullUserUpdatePassword() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement != null ) {
    		this.deleteUser(client, "ut_user1");
    	}
    	
		ClientResponse response = this.updatePassword(client, "ut_user1", "newPassword");
		
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"),"failure");	
    }
    
    @Test
    public void testUserUpdateBlankPassword() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement == null ) {
    		this.createTestUser(client);
    	}
    	ClientResponse response = this.updatePassword(client, "ut_user1", "");
		String entity = response.getEntity(String.class);
		JSONObject retJson = (JSONObject)JSONValue.parse(entity);
		assertEquals(retJson.get("status"), "failure");
    }
    
    @Test
    public void testShowUser() {
    	Client client = new Client();
    	JSONObject userElement = this.findUser(client, "ut_user1");
    	if ( userElement != null ) {
    		this.deleteUser(client, "ut_user1");
    	}
    	this.createTestUser(client);
    	
    	JSONObject retJson = this.showUser(client, "ut_user1");
    	assertEquals(retJson.get("status"), "success");
    	assertEquals(((JSONObject)retJson.get("user")).get("userName"), "name_ut_user1");
    }

}
