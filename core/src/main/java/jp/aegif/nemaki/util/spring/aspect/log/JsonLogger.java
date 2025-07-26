package jp.aegif.nemaki.util.spring.aspect.log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.annotation.PostConstruct;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
// ObjectInFolderContainer import commented out due to classloader issues
// import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import jp.aegif.nemaki.util.spring.aspect.log.JsonLogger.JsonLogConfig.GlobalConfig;
import jp.aegif.nemaki.util.spring.aspect.log.JsonLogger.JsonLogConfig.MethodConfig;
import jp.aegif.nemaki.util.spring.aspect.log.JsonLogger.JsonLogConfig.ValueConfig;
import net.logstash.logback.marker.Markers;

public class JsonLogger {
	private static Logger logger = LoggerFactory.getLogger(JsonLogger.class);
	
	@Autowired
	@Qualifier("debugObjectMapper")
	private ObjectMapper mapper;
	
	private JsonLogConfig config;
	private String jsonConfigurationFile;
	
	private final MethodConfig defaultMethodConfig = new MethodConfig();;
	
	private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
	
	@PostConstruct
	public void init() throws JsonParseException, JsonMappingException, IOException {
		load(jsonConfigurationFile);
		defaultMethodConfig.merge(config.getGlobal());
	}

	private void load(String fileName) throws JsonParseException, JsonMappingException, IOException {
		InputStream is = this.getClass().getClassLoader().getResourceAsStream(fileName);
		config = mapper.readValue(is, JsonLogConfig.class);
		
		//merge
		final GlobalConfig globalConfig = config.getGlobal(); 
		for(Entry<String, MethodConfig> entry : config.getMethod().entrySet()){
			entry.getValue().merge(globalConfig);
		}
	}

	public Object aroundMethod(ProceedingJoinPoint jp) throws Throwable {
		lock.readLock().lock();
		try{
			return aroundMethodBody(jp);
		}finally{
			lock.readLock().unlock();
		}
	}
	
	private Object aroundMethodBody(ProceedingJoinPoint jp) throws Throwable {
		if(!logger.isInfoEnabled()){
			return jp.proceed();
		}
		
		DateTime logTimeStart = new DateTime();
		
		// AOP parameters
		MethodSignature signature = (MethodSignature) jp.getSignature();
		final String methodName =  signature.getMethod().getDeclaringClass().getCanonicalName() + "." + signature.getName();
		Annotation[][] annotations = signature.getMethod().getParameterAnnotations();
		Object[] inputs = jp.getArgs();

		// read config
		MethodConfig methodConfig = config.getMethod().get(methodName);
		if (methodConfig == null) {
			methodConfig = defaultMethodConfig;
		}

		// create log instance
		JsonLog log = new JsonLog(methodConfig);
		
		// Parse callContext
		CallContext callContext = getCallContext(inputs);
		if (callContext != null) {
			log.setRepo(callContext.getRepositoryId());
			log.setUser(callContext.getUsername());
		}

		// Method name
		switch (methodConfig.getSetting().getName()) {
		case simple:
			log.setMethod(signature.getName());
			break;
		case full:
			log.setMethod(methodName);
			break;
		default:
			log.setMethod(methodName);
			break;
		}

		// input
		for (int i = 0; i < annotations.length; i++) {
			final String paramName = getParamName(annotations[i]);
			Object value = inputs[i];

			// except for CallContext
			if (value != null && value instanceof CallContext) {
				continue;
			}

			// log
			final ValueConfig inputConfig = methodConfig.getInput().get(paramName);
			if (inputConfig == null) {
				continue;
			}
			switch (inputConfig.getType()) {
			case none:
				break;
			case simple:
				log.getInput().put(paramName, simple(value, inputConfig));
				break;
			case full:
				log.getInput().put(paramName, value);
				break;
			default:
				break;
			}
		}

		//TODO
		//log.setWhen("calling")
		
		// Execute method
		try {
			DateTime timeStart = new DateTime();
			//execute
			Object result = jp.proceed();
			DateTime timeEnd = new DateTime();
			if(methodConfig.getSetting().getTime()){
				log.setTime(new Duration(timeStart.getMillis(), timeEnd.getMillis()).getMillis());
			}
			
			// output
			// TODO
			final ValueConfig outputConfig = methodConfig.getOutput();
			if (outputConfig != null) {
				switch (outputConfig.getType()) {
				case none:
					break;
				case simple:
					log.setOutput(simple(result, outputConfig));
					break;
				case full:
					log.setOutput(result);
					break;
				default:
					break;
				}
			}

			// logging
			DateTime logTimeEnd = new DateTime();
			if(methodConfig.getSetting().getLogTime()){
				log.setLogTime(new Duration(logTimeStart.getMillis(), logTimeEnd.getMillis()).getMillis());
			}
			
			log.setWhen("completed");			
			String json = mapper.writeValueAsString(log);
			
			logger.info(Markers.appendRaw("message", json), null);

			return result;
		} catch (Exception e) {
			log.setWhen("error");
			String json = mapper.writeValueAsString(log);
			logger.error(Markers.appendRaw("message", json), null, e);
			throw e;
		}
	}

	private CallContext getCallContext(Object[] args) {
		if (args != null && args.length > 0) {
			for (int i = 0; i < args.length; i++) {
				Object arg = args[i];
				if (arg != null && arg instanceof CallContext) {
					CallContext callContext = (CallContext) arg;
					return callContext;
				}
			}
		}
		return null;
	}

	private String getParamName(Annotation[] annotationsOnParam) {
		for (int i = 0; i < annotationsOnParam.length; i++) {
			if (annotationsOnParam[i] instanceof LogParam) {
				LogParam logParam = (LogParam) (annotationsOnParam[i]);
				return logParam.value();
			}
		}

		return null;
	}

	// ////////////////////////////////////////////////
	// Log record
	// //////////////////////////////////////////////
	@JsonInclude(Include.NON_NULL)
	private static class JsonLog {

		public JsonLog(MethodConfig methodConfig){
			if(methodConfig.getSetting().getUuid()){
				this.uuid = UUID.randomUUID().toString();
			}
		}

		private String uuid;
		private String repo;
		private String user;
		private String when;
		private Long time;
		private Long logTime;
		private String method;
		private Map<String, Object> input = new HashMap<>();
		private Object output;

		public String getUuid() {
			return uuid;
		}

		public String getRepo() {
			return repo;
		}

		public void setRepo(String repo) {
			this.repo = repo;
		}

		public String getUser() {
			return user;
		}

		public void setUser(String user) {
			this.user = user;
		}

		public String getWhen() {
			return when;
		}

		public void setWhen(String when) {
			this.when = when;
		}

		public Long getTime() {
			return time;
		}

		public void setTime(Long time) {
			this.time = time;
		}

		public Long getLogTime() {
			return logTime;
		}

		public void setLogTime(Long logTime) {
			this.logTime = logTime;
		}

		public void setUuid(String uuid) {
			this.uuid = uuid;
		}

		public String getMethod() {
			return method;
		}

		public void setMethod(String method) {
			this.method = method;
		}

		

		public Map<String, Object> getInput() {
			return input;
		}

		public void setInput(Map<String, Object> input) {
			this.input = input;
		}

		public Object getOutput() {
			return output;
		}

		public void setOutput(Object output) {
			this.output = output;
		}
	}

	// ////////////////////////////////////////////////
	// Log config
	// //////////////////////////////////////////////
	public static class JsonLogConfig {
		private GlobalConfig global;
		private Map<String, MethodConfig> method;

		public JsonLogConfig() {

		}

		public GlobalConfig getGlobal() {
			return global;
		}

		public void setGlobal(GlobalConfig global) {
			this.global = global;
		}

		public Map<String, MethodConfig> getMethod() {
			return method;
		}

		public void setMethod(Map<String, MethodConfig> method) {
			this.method = method;
		}
		
		@JsonIgnoreProperties
		public static class GlobalConfig {
			private Setting setting;
			private Map<String, ValueConfig>input;
			private ValueConfig output;

			public Setting getSetting() {
				return setting;
			}

			public void setSetting(Setting setting) {
				this.setting = setting;
			}

			public Map<String, ValueConfig> getInput() {
				return input;
			}

			public void setInput(Map<String, ValueConfig> input) {
				this.input = input;
			}

			public ValueConfig getOutput() {
				return output;
			}

			public void setOutput(ValueConfig output) {
				this.output = output;
			}
		}
		
		@JsonIgnoreProperties
		public static class Setting {
			private Boolean uuid;
			private NameSetting name;
			private Boolean time;
			private Boolean logTime;
			public enum NameSetting{
				simple, full,
			}
			
			public Boolean getUuid() {
				return uuid;
			}

			public void setUuid(Boolean uuid) {
				this.uuid = uuid;
			}

			public NameSetting getName(){
				return name;
			}
			
			public void setName(NameSetting name){
				this.name = name;
			}

			public Boolean getTime() {
				return time;
			}

			public void setTime(Boolean time) {
				this.time = time;
			}

			public Boolean getLogTime() {
				return logTime;
			}

			public void setLogTime(Boolean logTime) {
				this.logTime = logTime;
			}
		}

		@JsonIgnoreProperties
		public static class MethodConfig {
			private Setting setting = new Setting();
			private Map<String, ValueConfig> input = new HashMap<>();
			private ValueConfig output = new ValueConfig();

			public MethodConfig() {

			}

			public Setting getSetting() {
				return setting;
			}

			public void setSetting(Setting setting) {
				this.setting = setting;
			}

			public Map<String, ValueConfig> getInput() {
				return input;
			}

			public void setInput(Map<String, ValueConfig> input) {
				this.input = input;
			}

			public ValueConfig getOutput() {
				return output;
			}

			public void setOutput(ValueConfig output) {
				this.output = output;
			}
			
			public void merge(GlobalConfig globalConfig){
				if(globalConfig == null){
					return;
				}
				
				//merge setting
				Setting globalSetting = globalConfig.getSetting();
				if(globalSetting != null){
					if(setting.getUuid() == null) setting.setUuid(globalSetting.getUuid());
					if(setting.getName() == null) setting.setName(globalSetting.getName());
					if(setting.getTime() == null) setting.setTime(globalSetting.getTime());
					if(setting.getLogTime() == null) setting.setLogTime(globalSetting.getLogTime());
				}
				
				//merge input
				Map<String, ValueConfig> globalInput  = globalConfig.getInput();
				if(globalInput != null){
					for(String key : globalInput.keySet()){
						ValueConfig globalEachInput = globalInput.get(key);
						ValueConfig thisEachInput = input.get(key);
						if(thisEachInput == null){
							input.put(key, globalEachInput);
						}else{
							thisEachInput.merge(globalEachInput);
							input.put(key, thisEachInput);
						}
						
					}
				}
						
				//merge output
				output.merge(globalConfig.getOutput());
			}
		}

		public enum ValueType {
			none, simple, custom, full,
		}
		
		public static class ValueConfig {
			private ValueType type;
			private ListConfig list = new ListConfig();
			private Map<String, Boolean> properties = new HashMap<>();
			
			public ValueType getType() {
				return type;
			}

			public void setType(ValueType type) {
				this.type = type;
			}

			public ListConfig getList() {
				return list;
			}

			public void setList(ListConfig list) {
				this.list = list;
			}

			public Map<String, Boolean> getProperties() {
				return properties;
			}

			public void setProperties(Map<String, Boolean> properties) {
				this.properties = properties;
			}
			
			public void merge(ValueConfig other){
				if(other != null){
					if(this.getType() == null) this.setType(other.getType());
					if(other.getList() != null){
						ListConfig globalListConfig = other.getList();
						if(this.getList().getNum() == null) this.getList().setNum(globalListConfig.getNum());
						if(this.getList().getItem() == null) this.getList().setItem(globalListConfig.getItem());
					}
					if(other.getProperties() != null){
						Map<String, Boolean> globalProperties = other.getProperties();
						for(String key : globalProperties.keySet()){
							if(this.getProperties().get(key) == null){
								this.getProperties().put(key, globalProperties.get(key));
							}
							
						}
					}
				}
			}
		}
		
		public static class ListConfig{
			private Boolean num;
			private Boolean item;
			public Boolean getNum() {
				return num;
			}
			public void setNum(Boolean num) {
				this.num = num;
			}
			public Boolean getItem() {
				return item;
			}
			public void setItem(Boolean item) {
				this.item = item;
			}
		}
	}

	// //////////////////////////////////////////////
	// conversion method
	// //////////////////////////////////////////////
	private JsonNode simple(Object value, ValueConfig valueConfig){
		if(value == null){
			return null;
		}
		
		if(value instanceof ObjectData){
			return simple((ObjectData)value, valueConfig);
		}else if(value instanceof Properties){
			return simple((Properties)value, valueConfig);
		}else if(value instanceof FailedToDeleteData){
			return simple((FailedToDeleteData)value, valueConfig);
		}else if(value instanceof RenditionData){
			return simple((RenditionData)value, valueConfig);
		}else if(value instanceof BulkUpdateObjectIdAndChangeToken){
			return simple((BulkUpdateObjectIdAndChangeToken)value, valueConfig);
		}else if(value instanceof ContentStream){
			return simple((ContentStream)value, valueConfig);
		}else if(value instanceof Acl){
			return simple((Acl)value, valueConfig);
		}else if(value instanceof ObjectInFolderData){
			return simple((ObjectInFolderData)value, valueConfig);
		}else if(value instanceof ObjectInFolderList){
			return simple((ObjectInFolderList)value, valueConfig);
		// ObjectInFolderContainer handling commented out due to classloader issues
		// }else if(value instanceof ObjectInFolderContainer){
		//	return simple((ObjectInFolderContainer)value, valueConfig);
		}else if(value instanceof ObjectParentData){
			return simple((ObjectParentData)value, valueConfig);
		}else if(value instanceof Holder){
			return simple((Holder)value, valueConfig);
		}else if(value instanceof Collection){
			return simple( (Collection)value, valueConfig);
		}
		return mapper.valueToTree(value);
	}
	
	private ObjectNode simple(ObjectData objectData, ValueConfig valueConfig) {
		ObjectNode json = mapper.createObjectNode()
				.put("objectId", objectData.getId());
		
		PropertyData name = objectData.getProperties().getProperties().get(PropertyIds.NAME);
		if(name != null){
			json.put("name", ObjectUtils.toString(name.getFirstValue()));
		}
		
		return json;
	}
	
	private ObjectNode simple(FailedToDeleteData failedToDeleteData, ValueConfig valueConfig){
		List<String>ids = failedToDeleteData.getIds();
		return simple(ids, valueConfig);
	}
	
	private ObjectNode simple(RenditionData renditionData, ValueConfig valueConfig){
		return mapper.createObjectNode()
				.put("streamId", renditionData.getStreamId());
	}

	private ObjectNode simple(BulkUpdateObjectIdAndChangeToken bulkUpdateObjectIdAndChangeToken, ValueConfig valueConfig){
		return mapper.createObjectNode()
				.put("objectId", bulkUpdateObjectIdAndChangeToken.getId())
				.put("newObjectId", bulkUpdateObjectIdAndChangeToken.getNewId())
				.put("changeToken", bulkUpdateObjectIdAndChangeToken.getChangeToken());
	}
	
	private ObjectNode simple(ContentStream contentStream, ValueConfig valueConfig){
		return mapper.createObjectNode()
				.put("size", contentStream.getLength());
	}

	private ObjectNode simple(Acl acl, ValueConfig valueConfig){
		ObjectNode json = simple(acl.getAces(), valueConfig);
		
		Boolean inherited = null;
		List<CmisExtensionElement> exts = acl.getExtensions();
		if(!CollectionUtils.isEmpty(exts)){
			for(CmisExtensionElement ext : exts){
				if(ext.getName().equals("inherited")){
					inherited = Boolean.valueOf(ext.getValue());
					json.put("inherited", inherited);
				}
			}
		}
		
		return json;
	}

	private ObjectNode simple(ObjectInFolderList objectInFolderList, ValueConfig valueConfig){
		ObjectNode json = simple(objectInFolderList.getObjects(), valueConfig);
		
		json.put("hasMoreItems", objectInFolderList.hasMoreItems());
		json.put("totalNum", objectInFolderList.getNumItems().intValue());
		
		return json;
	}
	
	private ObjectNode simple(ObjectInFolderData objectInFolderData, ValueConfig valueConfig){
		return simple(objectInFolderData.getObject(), valueConfig);
	}
	
	// ObjectInFolderContainer method completely removed due to classloader issues
	
	private ObjectNode simple(ObjectParentData objectParentData, ValueConfig valueConfig){
		return simple(objectParentData.getObject(), valueConfig);
	}
	
	private ObjectNode simple(Properties properties, ValueConfig valueConfig){
		ObjectNode json = mapper.createObjectNode();
		
		for(String key : valueConfig.getProperties().keySet()){
			Boolean enabled = valueConfig.getProperties().get(key);
			if(enabled != null & enabled){
				PropertyData<?> value = properties.getProperties().get(key);
				if(value != null && value.getFirstValue() != null){
					json.put(key, value.getFirstValue().toString());
				}
			}
		}
		
		return json;
	}
	
	private JsonNode simple(Holder holder,  ValueConfig valueConfig){
		Object value = holder.getValue();
		if(value != null){
			return new TextNode(value.toString());
		}
		return null;
	}
	
	private ObjectNode simple(Collection collection, ValueConfig valueConfig){
		ObjectNode json = mapper.createObjectNode();
		
		if(valueConfig.getList().getNum()){
			json.put("num", collection.size());
		}

		if(valueConfig.getList().getItem()){
			ArrayNode listNode = json.putArray("list");
			for(Object item : collection){
				listNode.add(simple(item, valueConfig));
			}
		}
		return json;
	}

	// ////////////////////////////////////////////////
	// config api
	// //////////////////////////////////////////////	
	public JsonNode getJsonConfiguration() {
		return mapper.valueToTree(config);
	}
	
	public void updateJsonConfiguration(String json) throws JsonParseException, JsonMappingException, IOException{
		lock.writeLock().lock();
		try{
			config = mapper.readValue(json, JsonLogConfig.class);
		}finally{
			lock.writeLock().unlock();
		}
	}

	public void reloadJsonConfiguration() throws JsonParseException, JsonMappingException, IOException {
		lock.writeLock().lock();
		try{
			load(jsonConfigurationFile);
		}finally{
			lock.writeLock().unlock();
		}
	}
	
	public void setJsonConfigurationFile(String jsonConfigurationFile) {
		this.jsonConfigurationFile = jsonConfigurationFile;
	}
}
