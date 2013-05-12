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
package jp.aegif.nemaki.repository;

import java.math.BigInteger;
import java.util.Map;

import jp.aegif.nemaki.service.cmis.NemakiCmisService;

import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.support.CmisServiceWrapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Service factory class, specified in repository.properties.
 */
public class NemakiCmisServiceFactory extends AbstractServiceFactory {

	private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger
			.valueOf(50);
	private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger
			.valueOf(-1);
	private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger
			.valueOf(200);
	private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger
			.valueOf(10);

	/**
	 * Repository reference to all repositories.
	 */
	private RepositoryMap repositoryMap;

	/**
	 * One CMIS service per thread.
	 */
	private ThreadLocal<CmisServiceWrapper<NemakiCmisService>> threadLocalService = new ThreadLocal<CmisServiceWrapper<NemakiCmisService>>();

	/**
	 * Add NemakiRepository into repository map at first.
	 */
	@Override
	public void init(Map<String, String> parameters) {
		repositoryMap = new RepositoryMap();
		ApplicationContext context = new ClassPathXmlApplicationContext(
				"applicationContext.xml");
		NemakiRepository nemakiRepository = context.getBean("nemakiRepository",
				NemakiRepository.class);
		repositoryMap.addRepository(nemakiRepository);
	}

	@Override
	public CmisService getService(CallContext context) {
		CmisServiceWrapper<NemakiCmisService> wrapperService = threadLocalService
				.get();
		if (wrapperService == null) {
			wrapperService = new CmisServiceWrapper<NemakiCmisService>(
					new NemakiCmisService(repositoryMap),
					DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES,
					DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS);
			threadLocalService.set(wrapperService);
		}
		wrapperService.getWrappedService().setCallContext(context);
		return wrapperService;
	}

	@Override
	public void destroy() {
		super.destroy();
	}

}
