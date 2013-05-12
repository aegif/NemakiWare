package jp.aegif.nemaki.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

public class PropertyManagerTest {

	private static PropertyManager propertyManger;
	private static final String FILE = "test.properties";
	private static final String REPO_KEY = "nemakiware.repositories";
	private static final String REPO_NEW_VAL = "worms";

	private static final String ADMIN_KEY = "defaultAdminUserName";
	private static final String ADMIN_VAL = "admin";
	private static final String ADMIN_NEW_VAL = "manager";

	private static final String DB_MACX_CONENCTIONS_KEY = "db.maxConnections";
	private static final String DB_MACX_CONENCTIONS_VAL = "40";

	@BeforeClass
	public static void setup() {
		try {
			propertyManger = new PropertyManager(FILE);
		} catch (Exception e) {
			fail("Could not load " + FILE);
		}
	}

	@Test
	public void testPropertyManager() {
		assertNotNull(propertyManger);
	}

	@Test
	public void testReadValue() {
		try {
			assertEquals(ADMIN_VAL, propertyManger.readValue(ADMIN_KEY));
		} catch (Exception e) {
			fail("Could not read value of " + ADMIN_KEY);
		}
	}

	@Test
	public void testModifyValue() {
		try {
			propertyManger.modifyValue(ADMIN_KEY, ADMIN_NEW_VAL);
			assertEquals(ADMIN_NEW_VAL, propertyManger.readValue(ADMIN_KEY));
		} catch (Exception e) {
			fail("Could not modify value of " + ADMIN_KEY);
		}
	}

	@Test
	public void testAddValue() {
		try {
			String[] currentRepos = propertyManger.readValue(REPO_KEY).split(
					",");
			propertyManger.addValue(REPO_KEY, REPO_NEW_VAL);
			assertTrue(propertyManger.readValue(REPO_KEY).split(",").length > currentRepos.length);
		} catch (Exception e) {
			fail("Could not add value in " + REPO_KEY);
		}
	}

	@Test
	public void testRemoveValue() {
		try {
			String[] currentRepos = propertyManger.readValue(REPO_KEY).split(
					",");
			propertyManger.removeValue(REPO_KEY, REPO_NEW_VAL);
			assertTrue(propertyManger.readValue(REPO_KEY).split(",").length < currentRepos.length);
		} catch (Exception e) {
			fail("Could not remove value in " + REPO_KEY);
		}
	}

	@Test
	public void testReadHeadValue() {
		try {
			assertEquals(DB_MACX_CONENCTIONS_VAL,
					propertyManger.readHeadValue(DB_MACX_CONENCTIONS_KEY));
		} catch (Exception e) {
			fail("Could not remove value in " + REPO_KEY);
		}
	}

	@Test
	public void testGetPropertiesFile() {
		assertEquals(FILE, propertyManger.getPropertiesFile());
	}

}
