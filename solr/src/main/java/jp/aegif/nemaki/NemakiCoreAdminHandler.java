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
package jp.aegif.nemaki;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import jp.aegif.nemaki.tracker.CoreTracker;
import jp.aegif.nemaki.tracker.CoreTrackerJob;
import jp.aegif.nemaki.util.Constant;
import jp.aegif.nemaki.util.PropertyKey;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.StringPool;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Solr core handler classs
 * Called on server start up & user request via RESTful
 * @author linzhixing
 *
 */
public class NemakiCoreAdminHandler extends CoreAdminHandler {

	Logger logger = Logger.getLogger(NemakiCoreAdminHandler.class);

	ConcurrentHashMap<String, CoreTracker> trackers = new ConcurrentHashMap<String, CoreTracker>();
	Scheduler scheduler = null;


	public NemakiCoreAdminHandler() {
		super();
	}

	public NemakiCoreAdminHandler(CoreContainer coreContainer) {
		super(coreContainer);

		PropertyManager pm = new PropertyManagerImpl(
				StringPool.PROPERTIES_NAME);

		String repositoryCorename = pm.readValue(PropertyKey.SOLR_CORE_MAIN);
		String tokenCoreName = pm.readValue(PropertyKey.SOLR_CORE_TOKEN);

		SolrServer repositoryServer = new EmbeddedSolrServer(coreContainer, repositoryCorename);
		SolrServer tokenServer = new EmbeddedSolrServer(coreContainer, tokenCoreName);

		SolrCore core = getCoreContainer().getCore(repositoryCorename);
		CoreTracker tracker = new CoreTracker(this, core, repositoryServer, tokenServer);
		logger.info("NemakiCoreAdminHandler successfully instantiated");

		String  jobEnabled = pm.readValue(PropertyKey.SOLR_TRACKING_CRON_ENABLED);
		if("true".equals(jobEnabled)){
			// Configure Job
			JobDataMap jobDataMap = new JobDataMap();
			jobDataMap.put("ADMIN_HANDLER", this);
			jobDataMap.put("TRACKER", tracker);
			JobDetail job = newJob(CoreTrackerJob.class)
					.withIdentity("CoreTrackerJob", "Solr")
					.usingJobData(jobDataMap).build();

			// Configure Trigger
			// Cron expression is set in a property file
			String cron = pm.readValue(PropertyKey.SOLR_TRACKING_CRON_EXPRESSION);
			Trigger trigger = newTrigger().withIdentity("TrackTrigger", "Solr")
					.withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();

			// Configure Scheduler
			StdSchedulerFactory factory = new StdSchedulerFactory();
			Properties properties = new Properties();
			properties.setProperty("org.quartz.scheduler.instanceName",
					"NemakiSolrTrackerScheduler");
			properties.setProperty("org.quartz.threadPool.class",
					"org.quartz.simpl.SimpleThreadPool");
			properties.setProperty("org.quartz.threadPool.threadCount", "1");
			properties.setProperty("org.quartz.threadPool.makeThreadsDaemons",
					"true");
			properties.setProperty(
					"org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
			properties.setProperty("org.quartz.jobStore.class",
					"org.quartz.simpl.RAMJobStore");

			// Start quartz scheduler
			try {
				factory.initialize(properties);
				scheduler = factory.getScheduler();
				scheduler.start();
				scheduler.scheduleJob(job, trigger);
			} catch (SchedulerException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Switch actions on REST API
	 *
	 * Boolean return value is used as "doPersist" parameter,
	 * which relate to the persistence of action results to the core.
	 */
	@Override
	protected void handleCustomAction(SolrQueryRequest req,
			SolrQueryResponse rsp) {

		SolrParams params = req.getParams();

		// Get Server & Tracker info
		String indexCoreName = params.get(CoreAdminParams.CORE);
		String tokenCoreName = "token";

		SolrServer indexServer = new EmbeddedSolrServer(coreContainer, indexCoreName);
		SolrServer tokenServer = new EmbeddedSolrServer(coreContainer, tokenCoreName);
		SolrCore core = getCoreContainer().getCore(indexCoreName);
		CoreTracker tracker = new CoreTracker(this, core, indexServer, tokenServer);

		// Stop cron when executing action
		try {
			if(scheduler != null) scheduler.standby();
		} catch (SchedulerException e) {
			e.printStackTrace();
		}
		
		// Action
		doAction(rsp, tracker, params);
		
		// Restart cron
		try {
			if(scheduler != null) scheduler.start();
		} catch (SchedulerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
	}

	private void doAction(SolrQueryResponse rsp, CoreTracker tracker, SolrParams params){
		String action = params.get(CoreAdminParams.ACTION);
		String repositoryId = params.get("repositoryId");
		
		if (action.equalsIgnoreCase("INDEX")) {
			index(rsp, tracker, params, repositoryId);
		} else if (action.equalsIgnoreCase("INIT")) {
			init(rsp, tracker, repositoryId);
		}
	}
	
	private void index(SolrQueryResponse rsp, CoreTracker tracker, SolrParams params, String repositoryId){
		// Get tracking mode: FULL or DELTA
		String tracking = params.get("tracking"); // tracking mode
		if (tracking == null || !tracking.equals(Constant.MODE_FULL)) {
			tracking = Constant.MODE_DELTA; // default to DELTA
		}
		
		// Action=INDEX: track documents(by FULL or DELTA)
		if(tracking.equals(Constant.MODE_FULL)){
			// Init
			if(StringUtils.isBlank(repositoryId)){
				tracker.initCore();
			}else{
				tracker.initCore(repositoryId);
			}
		}
		
		// Index
		if(StringUtils.isBlank(repositoryId)){
			tracker.index(tracking);
		}else{
			tracker.index(tracking, repositoryId);
		}
		
		
		// TODO More info
		rsp.add("Result", "Successfully tracked!");
	}
	
	private void init(SolrQueryResponse rsp, CoreTracker tracker, String repositoryId){
		// Action=INIT: initialize core
		
		if(StringUtils.isBlank(repositoryId)){
			tracker.initCore();
		}else{
			tracker.initCore(repositoryId);
		}
		
		rsp.add("Result", "Successfully initialized!");
	}
	
	
	/**
	 * @return the trackers
	 */
	public ConcurrentHashMap<String, CoreTracker> getTrackers() {
		return trackers;
	}

}
