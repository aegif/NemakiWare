package jp.aegif.nemaki.rest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jp.aegif.nemaki.util.spring.aspect.log.JsonLogger;
import net.logstash.logback.marker.Markers;

@Path("/all/log")
public class LogResource extends ResourceBase{

	private JsonLogger jsonLogger;
	
	private ObjectMapper mapper;
	
	private static final Log log = LogFactory.getLog(LogResource.class);
	
	private ObjectMapper getMapper() {
		if (mapper != null) {
			return mapper;
		}
		// Fallback to manual Spring context lookup
		try {
			return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
					.getBean("debugObjectMapper", ObjectMapper.class);
		} catch (Exception e) {
			// If debugObjectMapper not found, try default nemakiObjectMapper
			return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
					.getBean("nemakiObjectMapper", ObjectMapper.class);
		}
	}

	@GET
	@Path("/config")
	@Produces(MediaType.APPLICATION_JSON)
	public String get(@Context HttpServletRequest request) throws JsonProcessingException  {
		boolean status = true;
		ObjectNode result = getMapper().createObjectNode();
		ArrayNode errMsg = getMapper().createArrayNode();

		//check admin
		if(!checkAdmin(errMsg, request)){
			getMapper().writeValueAsString(makeResult(status, result, errMsg));
		}

		//get config
		try{
			JsonNode config = jsonLogger.getJsonConfiguration();
			result.set("config", config);

		}catch(Exception e){
			addErrMsg(errMsg, "error", e.getMessage());
			log.error(e.getMessage(), e);
		}

		result = makeResult(status, result, errMsg);
		return getMapper().writeValueAsString(makeResult(status, result, errMsg));

	}

	@PUT
	@Path("/config/_update")
	@Produces(MediaType.APPLICATION_JSON)
	public String update(@Context HttpServletRequest request) throws JsonParseException, JsonMappingException, IOException {
		boolean status = true;
		ObjectNode result = getMapper().createObjectNode();
		ArrayNode errMsg = getMapper().createArrayNode();

		//check admin
		if(!checkAdmin(errMsg, request)){
			return makeResult(status, result, errMsg).toString();
		}

		//udpate config
		try{
			jsonLogger.updateJsonConfiguration(parseBody(request));
		}catch(Exception e){
			addErrMsg(errMsg, "error", e.getMessage());
			log.error(e.getMessage(), e);
		}

		result = makeResult(status, result, errMsg);
		return getMapper().writeValueAsString(makeResult(status, result, errMsg));
	}

	@PUT
	@Path("/config/_reload")
	@Produces(MediaType.APPLICATION_JSON)
	public String reload(@Context HttpServletRequest request) throws JsonParseException, JsonMappingException, IOException {
		boolean status = true;
		ObjectNode result = getMapper().createObjectNode();
		ArrayNode errMsg = getMapper().createArrayNode();

		//check admin
		if(!checkAdmin(errMsg, request)){
			return makeResult(status, result, errMsg).toString();
		}

		//reload config
		try{
			jsonLogger.reloadJsonConfiguration();
		}catch(Exception e){
			addErrMsg(errMsg, "error", e.getMessage());
			log.error(e.getMessage(), e);
		}

		result = makeResult(status, result, errMsg);
		return getMapper().writeValueAsString(makeResult(status, result, errMsg));
	}

	private String parseBody(HttpServletRequest request) {
		if (isJson(request)) {
            try {
                String json = IOUtils.toString(request.getInputStream(), "UTF-8");
                // do whatever you need with json

                // replace input stream for Jersey as we've already read it
                InputStream in = IOUtils.toInputStream(json);
                String theString = IOUtils.toString(in, "UTF-8");
                return theString;
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
		return null;
	}

	boolean isJson(HttpServletRequest request) {
        return request.getContentType().contains("application/json");
    }

	public void setJsonLogger(JsonLogger jsonLogger) {
		this.jsonLogger = jsonLogger;
	}

}
