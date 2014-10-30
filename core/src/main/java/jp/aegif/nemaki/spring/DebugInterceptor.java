/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.spring;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodBeforeAdvice;

/**
 * For debugging purpose, print to the log for every method call<br/>
 * - Method name<br/>
 * - Arguments<br/>
 * - Result
 */
public class DebugInterceptor implements MethodBeforeAdvice,
		AfterReturningAdvice {

	private static final Log log = LogFactory.getLog(DebugInterceptor.class);
	private static final SimpleDateFormat sdf = new SimpleDateFormat("hh:mm:ss.S");

	public void before(Method method, Object[] args, Object target)
			throws Throwable {
		try {
			log.debug(sdf.format(new Date()) + " " +  method.getDeclaringClass().getSimpleName() + "#"
					+ method.getName() + " " /* + Arrays.asList(args) */);
		} catch (Exception e) {
			log.warn(e);
		}
	}

	public void afterReturning(Object returnValue, Method method,
			Object[] args, Object target) throws Throwable {
		try {
			log.debug(sdf.format(new Date()) + " " +  method.getDeclaringClass().getSimpleName() + "#"
					+ method.getName() + " returned "  /*+ returnValue */);
		} catch (Exception e) {
			log.warn(e);
		}
	}

}
	