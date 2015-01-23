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
package jp.aegif.nemaki.repository.factory.impl;

import java.math.BigInteger;
import java.util.Map;

import jp.aegif.nemaki.repository.NemakiRepository;
import jp.aegif.nemaki.repository.RepositoryMap;
import jp.aegif.nemaki.repository.factory.NemakiCmisService;
import jp.aegif.nemaki.service.cmis.AuthenticationService;
import jp.aegif.nemaki.util.constant.NemakiConstant;

import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.support.CmisServiceWrapper;

/**
 * Service factory class, specified in repository.properties.
 */
public class NemakiCmisServiceFactoryImpl extends AbstractServiceFactory implements CmisServiceFactory{

	private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger
			.valueOf(50);
	private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger
			.valueOf(-1);
	private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger
			.valueOf(200);
	private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger
			.valueOf(10);

	private NemakiRepository nemakiRepository;

	private AuthenticationService authenticationService;

	public NemakiCmisServiceFactoryImpl(){
		super();
	}


	/**
	 * Repository reference to all repositories.
	 */
	private RepositoryMap repositoryMap;

	/**
	 * One CMIS service per thread.
	 */
	private final ThreadLocal<CmisServiceWrapper<NemakiCmisService>> threadLocalService = new ThreadLocal<CmisServiceWrapper<NemakiCmisService>>();

	/**
	 * Add NemakiRepository into repository map at first.
	 */
	@Override
	public void init(Map<String, String> parameters) {
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

		//authentication
		boolean isAdmin = authenticationService.login(context.getUsername(), context.getPassword());

		//Set admin flag as Nemaki extension
		((CallContextImpl)context).put(NemakiConstant.CALL_CONTEXT_IS_ADMIN, isAdmin);

		return wrapperService;
	}

	@Override
	public void destroy() {
		super.destroy();
	}

	public void setAuthenticationService(AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public void setNemakiRepository(NemakiRepository nemakiRepository) {
		this.nemakiRepository = nemakiRepository;
	}

	public void setRepositoryMap(RepositoryMap repositoryMap) {
		this.repositoryMap = repositoryMap;
	}

}
