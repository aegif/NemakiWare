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

import org.apache.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Job class of index tracking
 * @author linzhixing
 *
 */
public class CoreTrackerJob implements Job{
	
	private final String MODE_AUTO = "AUTO";
	
	Logger logger = Logger.getLogger(CoreTrackerJob.class);
	
	public CoreTrackerJob(){
		super();
	}
	
	public void execute(JobExecutionContext jec) throws JobExecutionException{
		CoreTracker coreTracker = (CoreTracker) jec.getJobDetail().getJobDataMap().get("TRACKER");
		
		if ( !coreTracker.isConnectionSetup() ) {
			coreTracker.setupCmisSession();
		}
		
		if(coreTracker.cmisSession == null){
			coreTracker.setupCmisSession();
		}
		if(coreTracker.cmisSession == null){
			logger.error("Tracking is not executed because the session to the CMIS server is not established.");
		}else{
			coreTracker.index(MODE_AUTO);
		}
	}
}
