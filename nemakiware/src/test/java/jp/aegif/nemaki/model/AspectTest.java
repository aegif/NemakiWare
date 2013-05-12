package jp.aegif.nemaki.model;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

public class AspectTest {

	private static Aspect aspect;

	private static final String TEST_NAME = "testName";
	private static final List<Property> TEST_PROPERTIES = new ArrayList<Property>();

	@BeforeClass
	public static void initializeWithArgs() {
		aspect = new Aspect(TEST_NAME, TEST_PROPERTIES);
	}

	@Test
	public void testGetName() {
		assertEquals(TEST_NAME, aspect.getName());
	}

	@Test
	public void testGetProperties() {
		assertEquals(TEST_PROPERTIES, aspect.getProperties());
	}

}
