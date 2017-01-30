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
package jp.aegif.nemaki.tracker;

import jp.aegif.nemaki.util.CmisSessionFactory;
import jp.aegif.nemaki.util.Constant;
import jp.aegif.nemaki.util.yaml.RepositorySettings;

import org.apache.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import com.ibm.icu.text.MessageFormat;

/**
 * Job class of index tracking
 *
 * @author linzhixing
 *
 */
public class CoreTrackerJob implements Job {

	Logger logger = Logger.getLogger(CoreTrackerJob.class);

	public CoreTrackerJob() {
		super();
	}

	@Override
	public void execute(JobExecutionContext jec) throws JobExecutionException {
		CoreTracker coreTracker = (CoreTracker) jec.getJobDetail()
				.getJobDataMap().get("TRACKER");

		RepositorySettings settings = CmisSessionFactory.getRepositorySettings();
		for(String repositoryId : settings.getIds()){
			try{
				coreTracker.index(Constant.MODE_DELTA, repositoryId);
			}catch(Exception ex){
				logger.error(MessageFormat.format("Indexing error repository={0}",repositoryId), ex);
			}
		}
	}
}