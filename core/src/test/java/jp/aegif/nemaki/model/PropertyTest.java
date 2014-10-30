package jp.aegif.nemaki.model;

import static org.junit.Assert.assertEquals;

import org.junit.BeforeClass;
import org.junit.Test;

public class PropertyTest {

	private static Property property;
	private static final String TEST_KEY = "key";
	private static final String TEST_VALUE = "value";
	private static final String TEST_QUERY_NAME = "queryName";

	@BeforeClass
	public static void setup() {
		property = new Property(TEST_KEY, TEST_VALUE);
		property.setQueryName(TEST_QUERY_NAME);
	}

	@Test
	public void testGetKey() {
		assertEquals(TEST_KEY, property.getKey());
	}

	@Test
	public void testGetValue() {
		assertEquals(TEST_VALUE, property.getValue());
	}

	@Test
	public void testGetQueryName() {
		assertEquals(TEST_QUERY_NAME, property.getQueryName());
	}

}
