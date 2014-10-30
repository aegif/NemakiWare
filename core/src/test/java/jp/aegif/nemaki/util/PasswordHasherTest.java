package jp.aegif.nemaki.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class PasswordHasherTest {

	private static final String PASSWORD = "PASSWD";

	@Test
	public void testHash() {
		assertNotSame(PASSWORD, PasswordHasher.hash(PASSWORD));
	}

	@Test
	public void testIsCompared() {
		assertTrue(PasswordHasher.isCompared(PASSWORD,
				PasswordHasher.hash(PASSWORD)));
	}

}
