package jp.aegif.nemaki.api.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

/**
 * TODO see after creating other test because this is jersey, a bit special way is needed
 * @author mryoshio
 *
 */
public class AspectResourceTest extends JerseyTest {

	public AspectResourceTest() throws Exception {
		super("jp.aegif.nemaki.api.resources");
	}

	@Test
	public void testHello() {
		Client client = Client.create();
		WebResource webResource = client
				.resource("http://localhost:8180/aspects/hello");
		ClientResponse response = webResource.get(ClientResponse.class);
		String entity = response.getEntity(String.class);
		assertEquals("Hello Jersey!", entity);
	}

	@Test
	public void testBase() {
		Client client = Client.create();
		WebResource webResource = client
				.resource("http://localhost:8180/aspects/base");
		ClientResponse response = webResource.get(ClientResponse.class);
		JSONObject entity = response.getEntity(JSONObject.class);
		assertNotNull(entity);
	}
}
