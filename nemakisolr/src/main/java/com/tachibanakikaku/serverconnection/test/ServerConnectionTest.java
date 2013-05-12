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
package com.tachibanakikaku.serverconnection.test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import org.junit.Test;

import com.tachibanakikaku.serverconnection.ServerConnection;
import com.tachibanakikaku.serverconnection.ServerConnectionException;

public class ServerConnectionTest extends TestCase {

	private String scheme = "http";
	private String host = "localhost";
	private int port = 8080;
	private ServerConnection connection;

	/** POST URLs */
	private List<String> postUrls = new ArrayList<String>() {
		{
			add("/people/login");
			add("/people/register");
			add("/comments/create");
			add("/events/create");
			add("/flyers/create");
			add("/test/create");
		}
	};

	/** GET URLs */
	private List<String> getUrls = new ArrayList<String>() {
		{
			add("/people/search");
			add("/coupons/search");
			add("/people/2");
			add("/events/list");
		}
	};

	/** GET Image URLs */
	private List<String> getImageUrls = new ArrayList<String>() {
		{
			add("/photos/4");
			add("/photos/3");
			add("/photos/1");
			add("/photos/2");
		}
	};

	@Override
	protected void setUp() throws Exception {
		connection = ServerConnection
				.createServerConnection(scheme, host, port);
	}

	/**
	 * POST test method
	 *//*
	@Test
	public void testPost() {
		Map<String, Object> params = new HashMap<String, Object>();
		for (Iterator<String> it = postUrls.iterator(); it.hasNext();) {
			String path = (String) it.next();
			List<List<String>> ret = new ArrayList<List<String>>();
			try {
				ret = connection.post(path, params);
			} catch (ServerConnectionException e) {
				e.printStackTrace();
			}
			System.out.println("# " + path);
			System.out.println(ret);
		}
	}*/

	/**
	 * GET test method
	 *//*
	@Test
	public void testGet() {
		Map<String, Object> params = new HashMap<String, Object>();
		for (Iterator<String> it = getUrls.iterator(); it.hasNext();) {
			String path = (String) it.next();
			List<List<String>> ret = new ArrayList<List<String>>();
			try {
				ret = connection.get(path, params);
			} catch (ServerConnectionException e) {
				e.printStackTrace();
			}
			System.out.println("# " + path);
			System.out.println(ret);
		}
	}*/

	/**
	 * Get image and write out to <n>.jpg
	 */
	public void testGetImage() {
		OutputStream os = null;
		try {
			int cnt = 0;
			for (Iterator<String> it = getImageUrls.iterator(); it.hasNext();) {
				String path = (String) it.next();
				byte[] ret = null;
				ret = connection.getImage(path);
				System.out.println("# " + path);
				os = new FileOutputStream(new File(cnt + ".jpg"));
				os.write(ret);
				cnt++;
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (os != null)
					os.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
