/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Utility class to hash password one-way.
 */
public class PasswordHasher {

	/**
	 * Hash a password. An attacker knowing the output should not be able to
	 * guess the input. Currently implemented with Bcrypt, a variant of
	 * Blowfish. http://en.wikipedia.org/wiki/Bcrypt
	 */
	public static String hash(String password) {
		return BCrypt.hashpw(password, BCrypt.gensalt());
	}

	/**
	 * Hashed passwords for a few test users. More can be created using the
	 * "bcrypt" UNIX command line tool.
	 */
	public static void main(String[] args) {
		System.out.println(PasswordHasher.hash("jiro"));
		System.out.println(PasswordHasher.hash("saburo"));
		System.out.println(PasswordHasher.hash("admin"));
	}
	
	public static Boolean isCompared(String password, String hash){
		return BCrypt.checkpw(password, hash);
	}
}
