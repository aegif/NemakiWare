/**
 *	Copyright (C) 2011 mryoshio (yoshiokaas _at_ tachibanakikaku.com)
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.tachibanakikaku.serverconnection;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;


public class ServerConnection {
	private static final String CHARSET = "UTF-8";
	private static final String SEPARATOR = ",";

	private static ServerConnection connection;
	private static String scheme;
	private static String host;
	private static int port;
	private static HttpClient client;

	private ServerConnection(String _scheme, String _host, int _port) {
		scheme = _scheme;
		host = _host;
		port = _port;
		client = new DefaultHttpClient();
	}

	public static synchronized ServerConnection createServerConnection(
			String _scheme, String _host, int _port) {
		if (connection == null)
			connection = new ServerConnection(_scheme, _host, _port);
		return connection;
	}

	/**
	 * Build GET method
	 * 
	 * @param _path
	 * @param _params
	 * @return GET method
	 * @throws ServerConnectionException
	 */
	private HttpGet buildGetRequest(String _method, String _path,
			Map<String, Object> _params) throws ServerConnectionException {

		List<NameValuePair> params = new ArrayList<NameValuePair>();
		for (Iterator<String> it = _params.keySet().iterator(); it.hasNext();) {
			String k = it.next();
			params.add(new BasicNameValuePair(k, (String) _params.get(k)));
		}
		HttpGet request = new HttpGet();
		try {
			request.setURI(URIUtils.createURI(scheme, host, port, _path,
					URLEncodedUtils.format(params, CHARSET), null));
		} catch (URISyntaxException e) {
			throw new ServerConnectionException(e);
		}
		return request;
	}
	
	/**
	 * Build POST method
	 * this method is originally used as buildPostRequest
	 * @param _path
	 * @param _params
	 * @return POST method
	 * @throws ServerConnectionException
	 */
	private HttpPost buildPostRequestforCSV(String _method, String _path,
			Map<String, Object> _params) throws ServerConnectionException {

		HttpPost request = new HttpPost();
		try {
			request.setURI(URIUtils.createURI(scheme, host, port, _path, null,
					null));
		     
			MultipartEntity entity = new MultipartEntity(
					HttpMultipartMode.BROWSER_COMPATIBLE);
			 for (Iterator<String> it = _params.keySet().iterator(); it
					.hasNext();) {
				String key = (String) it.next();
				if (_params.get(key) instanceof File) {
					FileBody fileBody = new FileBody((File) _params.get(key));
					entity.addPart(key, fileBody);
				} else {
					entity.addPart(key,
							new StringBody((String) _params.get(key)));
				}
				request.setEntity(entity);
			}
			
		} catch (Exception e) {
			new ServerConnectionException(e);
		}
		return request;
	}
	
	//TODO 内部変数の名前変更
	/**
	 * Build POST method
	 * @param _method
	 * @param _path
	 * @param _params
	 * @return
	 * @throws ServerConnectionException
	 */
	private HttpPost buildPostRequests(String _method, String _path,
			Map<String, Object> _params) throws ServerConnectionException {
		HttpPost request = new HttpPost();
		
		try {
			request.setURI(URIUtils.createURI(scheme, host, port, _path, null,
					null));
		} catch (Exception e) {
			new ServerConnectionException(e);
		}
		
		request.setEntity(buildParam(_params));
		return request;
	}
	
	/**
	 * Build PUT method
	 * @param _method
	 * @param _path
	 * @param _params
	 * @return
	 * @throws ServerConnectionException
	 */
	private HttpPut buildPutRequests(String _method, String _path,
			Map<String, Object> _params) throws ServerConnectionException {
		HttpPut request = new HttpPut();
		try {
			request.setURI(URIUtils.createURI(scheme, host, port, _path, null,
					null));
		} catch (Exception e) {
			new ServerConnectionException(e);
		}
		request.setEntity(buildParam(_params));
		return request;
	}
	
	/**
	 * Build DELETE method
	 * Use HttpDeleteWithBody because HttpDelete can't hold parameters on body
	 * @param _method
	 * @param _path
	 * @param _params
	 * @return
	 * @throws ServerConnectionException
	 */
	private HttpDeleteWithBody buildDeleteRequests(String _method, String _path,
			Map<String, Object> _params) throws ServerConnectionException {
		HttpDeleteWithBody request = new HttpDeleteWithBody();
		try {
			request.setURI(URIUtils.createURI(scheme, host, port, _path, null,
					null));
		} catch (Exception e) {
			new ServerConnectionException(e);
		}
		request.setEntity(buildParam(_params));
		return request;
	}
	
	/**
	 * Build FormEntity method from Map of parameters
	 * @param _params
	 * @return
	 */
	private UrlEncodedFormEntity buildParam(Map<String, Object> _params){
		List<NameValuePair> params = new ArrayList<NameValuePair>();
        for (Entry<String, Object> entry : _params.entrySet()) {  
            params.add(new BasicNameValuePair(entry.getKey(), (String)entry.getValue()));  
        } 
        UrlEncodedFormEntity entity;
		try {
			entity = new UrlEncodedFormEntity(params, CHARSET);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
        return entity;
	}
	
	/**
	 * Inner class for HttpDelete with body
	 * @author linzhixing
	 *
	 */
	private class HttpDeleteWithBody extends HttpEntityEnclosingRequestBase {
	    public static final String METHOD_NAME = "DELETE";
	    public String getMethod() { return METHOD_NAME; }

	    public HttpDeleteWithBody(final String uri) {
	        super();
	        setURI(URI.create(uri));
	    }
	    public HttpDeleteWithBody(final URI uri) {
	        super();
	        setURI(uri);
	    }
	    public HttpDeleteWithBody() { super(); }
	}
	

	/**
	 * Execute GET/POST request
	 * 
	 * @param httpMethod
	 * @param path
	 * @param params
	 * @return List of List
	 * @throws ServerConnectionException
	 */
	private List<List<String>> requestCSV(String method, String path,
			Map<String, Object> params) throws ServerConnectionException {

		BufferedReader reader = null;

		try {
			HttpResponse response;
			if (method.equals(HttpGet.METHOD_NAME)) {
				response = client
						.execute(buildGetRequest(method, path, params));
			} else {
				response = client
						.execute(buildPostRequestforCSV(method, path, params));
			}
			HttpEntity entity = response.getEntity();

			List<List<String>> retList = new ArrayList<List<String>>();
			if (entity == null) {
				return retList;
			} else {
				reader = new BufferedReader(new InputStreamReader(
						entity.getContent(), CHARSET));
				String line = null;
				while ((line = reader.readLine()) != null) {
					String[] data = line.split(SEPARATOR);
					List<String> lineList = new ArrayList<String>();
					for (int i = 0; i < data.length; i++)
						lineList.add(data[i]);
					retList.add(lineList);
				}
				return retList;
			}
		} catch (Exception e) {
			throw new ServerConnectionException(e);
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException e) {
					throw new ServerConnectionException(e);
				}
		}
	}

	/**
	 * Get image
	 * 
	 * @param path
	 * @return byte array of image
	 * @throws ServerConnectionException
	 */
	private byte[] requestImage(String path) throws ServerConnectionException {
		BufferedInputStream inStream = null;

		try {
			HttpResponse response = client.execute(buildGetRequest(
					HttpGet.METHOD_NAME, path, new HashMap<String, Object>()));
			HttpEntity entity = response.getEntity();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			if (entity == null) {
				return new byte[0];
			} else {
				inStream = new BufferedInputStream(entity.getContent());
				byte[] bytes = new byte[1024];
				int b = 0;
				while ((b = inStream.read(bytes)) != -1) {
					outputStream.write(bytes);
				}
				outputStream.flush();
				outputStream.close();
				return outputStream.toByteArray();
			}
		} catch (Exception e) {
			throw new ServerConnectionException(e);
		} finally {
			if (inStream != null)
				try {
					inStream.close();
				} catch (IOException e) {
					throw new ServerConnectionException(e);
				}
		}
	}

	
	/**
	 * Get JSON on GET/POST/PUT/DELETE method
	 * @param method
	 * @param path
	 * @param params
	 * @return
	 * @throws ServerConnectionException
	 */
	private JSONObject requestJSON(String method, String path,
			Map<String, Object> params) throws ServerConnectionException {
		BufferedReader reader = null;
		try {
			HttpResponse response;
			if (method.equals(HttpGet.METHOD_NAME)) {
				response = client
						.execute(buildGetRequest(method, path, params));
			}else if(method.equals(HttpPost.METHOD_NAME)){
				response = client
						.execute(buildPostRequests(method, path, params));
			}else if(method.equals(HttpPut.METHOD_NAME)){
				response = client
						.execute(buildPutRequests(method, path, params));	
			}else if(method.equals(HttpDeleteWithBody.METHOD_NAME)){
				response = client
						.execute(buildDeleteRequests(method, path, params));
			} else {
				throw new RuntimeException("The method is not implemented!");
			}
			HttpEntity entity = response.getEntity();
			if (entity == null) {
				return new JSONObject();
			} else {
				reader = new BufferedReader(new InputStreamReader(
						entity.getContent(), CHARSET));
				JSONObject json = (JSONObject) JSONValue.parse(reader);
				return json;
			}
		} catch (Exception e) {
			throw new ServerConnectionException(e);
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException e) {
					throw new ServerConnectionException(e);
				}
		}
	}
	
	//Retrieve simple HTTP body string
	private String request(String method, String path,
			Map<String, Object> params) throws ServerConnectionException {
		BufferedReader reader = null;
		try {
			HttpResponse response;
			if (method.equals(HttpGet.METHOD_NAME)) {
				response = client
						.execute(buildGetRequest(method, path, params));
			}else if(method.equals(HttpPost.METHOD_NAME)){
				response = client
						.execute(buildPostRequests(method, path, params));
			}else if(method.equals(HttpPut.METHOD_NAME)){
				response = client
						.execute(buildPutRequests(method, path, params));	
			}else if(method.equals(HttpDeleteWithBody.METHOD_NAME)){
				response = client
						.execute(buildDeleteRequests(method, path, params));
			} else {
				throw new RuntimeException("The method is not implemented!");
			}
			HttpEntity entity = response.getEntity();
			if (entity == null) {
				return null;
			} else {
				reader = new BufferedReader(new InputStreamReader(
						entity.getContent(), CHARSET));
				
				StringBuilder sb = new StringBuilder();
				String readLine;
				while( (readLine = reader.readLine()) != null ){
					sb.append(reader.readLine()).append(System.getProperty("line.separator"));
				}
				
				String rsp = new String(sb);
				return rsp;
			}
		} catch (Exception e) {
			throw new ServerConnectionException(e);
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException e) {
					throw new ServerConnectionException(e);
				}
		}
	}
	
	
	

	/*public List<List<String>> post(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return requestCSV(HttpPost.METHOD_NAME, path, params);
	}

	public List<List<String>> get(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return requestCSV(HttpGet.METHOD_NAME, path, params);
	}*/

	public byte[] getImage(String path) throws ServerConnectionException {
		return requestImage(path);
	}

	public String get(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return request(HttpGet.METHOD_NAME, path, params);
	}
	
	public JSONObject getJSON(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return requestJSON(HttpGet.METHOD_NAME, path, params);
	}
	
	public JSONObject postJSON(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return requestJSON(HttpPost.METHOD_NAME, path, params);
	}

	public JSONObject putJSON(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return requestJSON(HttpPut.METHOD_NAME, path, params);
	}
	
	public JSONObject deleteJSON(String path, Map<String, Object> params)
			throws ServerConnectionException {
		return requestJSON(HttpDeleteWithBody.METHOD_NAME, path, params);
	}
}
