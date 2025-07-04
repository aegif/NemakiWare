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
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import jp.aegif.nemaki.tracker.CoreTracker;
import jp.aegif.nemaki.tracker.CoreTrackerJob;
import jp.aegif.nemaki.util.CmisSessionFactory;
import jp.aegif.nemaki.util.Constant;
import jp.aegif.nemaki.util.PropertyKey;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.StringPool;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;
import jp.aegif.nemaki.util.yaml.RepositorySetting;
import jp.aegif.nemaki.util.yaml.RepositorySettings;

import org.apache.commons.lang3.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
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
public class NemakiCoreAdminHandler extends RequestHandlerBase {

	private static final Logger logger = LoggerFactory.getLogger(NemakiCoreAdminHandler.class);

	ConcurrentHashMap<String, CoreTracker> trackers = new ConcurrentHashMap<String, CoreTracker>();
	Scheduler scheduler = null;


	public NemakiCoreAdminHandler() {
		super();
	}

	@Override
	public void init(org.apache.solr.common.util.NamedList<?> args) {
		super.init(args);
		logger.info("NemakiCoreAdminHandler initialized");
		
		// Initialize scheduler if needed
		initializeScheduler();
	}
	
	private void initializeScheduler() {
		try {
			PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);
			String jobEnabled = pm.readValue(PropertyKey.SOLR_TRACKING_CRON_ENABLED);
			
			if("true".equals(jobEnabled)){
				// Configure Job
				JobDataMap jobDataMap = new JobDataMap();
				jobDataMap.put("ADMIN_HANDLER", this);
				JobDetail job = newJob(CoreTrackerJob.class)
						.withIdentity("CoreTrackerJob", "Solr")
						.usingJobData(jobDataMap).build();

				// Configure Trigger
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
				factory.initialize(properties);
				scheduler = factory.getScheduler();
				scheduler.start();
				scheduler.scheduleJob(job, trigger);
				logger.info("NemakiWare Quartz scheduler started successfully");
			}
		} catch (Exception e) {
			logger.error("Failed to initialize NemakiWare scheduler:", e);
		}
	}

	/**
	 * Main handler method required by RequestHandlerBase
	 */
	@Override
	public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {

		SolrParams params = req.getParams();

		// Get current core from request context
		SolrCore currentCore = req.getCore();
		CoreContainer coreContainer = currentCore.getCoreContainer();
		
		// Get Server & Tracker info
		String indexCoreName = params.get(CoreAdminParams.CORE);
		if (indexCoreName == null) {
			indexCoreName = currentCore.getName(); // Use current core if not specified
		}
		String tokenCoreName = "token";

		SolrClient indexServer = new EmbeddedSolrServer(coreContainer, indexCoreName);
		SolrClient tokenServer = new EmbeddedSolrServer(coreContainer, tokenCoreName);
		SolrCore core = coreContainer.getCore(indexCoreName);
		CoreTracker tracker = new CoreTracker(this, core, indexServer, tokenServer);

		// Stop cron when executing action
		try {
			if(scheduler != null) scheduler.standby();
		} catch (SchedulerException e) {
			logger.error("Stop cron when executing action error:", e);
		}

		// Action
		doAction(rsp, tracker, params);

		// Restart cron
		try {
			if(scheduler != null) scheduler.start();
		} catch (SchedulerException e) {
			logger.error("Restart cron error:", e);
		}


	}

	private void doAction(SolrQueryResponse rsp, CoreTracker tracker, SolrParams params){
		String action = params.get(CoreAdminParams.ACTION);
		String repositoryId = params.get("repositoryId");

		if (action.equalsIgnoreCase("INDEX")) {
			index(rsp, tracker, params, repositoryId);
		} else if (action.equalsIgnoreCase("INIT")) {
			init(rsp, tracker, repositoryId);
		} else if (action.equalsIgnoreCase("CHANGE_PASSWORD")) {
			changePassword(rsp, tracker, repositoryId, params);
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

	private void changePassword(SolrQueryResponse rsp, CoreTracker tracker, String repositoryId, SolrParams params){
		//Validation
		if(StringUtils.isEmpty(repositoryId)){
			rsp.setException(new Exception("repositoryId is not set."));
			return;
		}

		String password = params.get("password");
		if(StringUtils.isEmpty(password)){
			rsp.setException(new Exception("New password is not set."));
			return;
		}

		String currentPassword = params.get("currentPassword");
		if(StringUtils.isEmpty(password)){
			rsp.setException(new Exception("Current password is not set."));
			return;
		}

		//Execute
		RepositorySettings settings = CmisSessionFactory.getRepositorySettings();
		RepositorySetting setting = settings.get(repositoryId);
		if(setting == null){
			rsp.setException(new Exception("Specified repository does not exist."));
			return;
		}
		if(!currentPassword.equals(setting.getPassword())){
			rsp.setException(new Exception("Current password does not match."));
			return;
		}
		setting.setPassword(password);
		CmisSessionFactory.modifyRepositorySettings(settings);

		rsp.add("Result", "Successfully password changed!");

	}

	/**
	 * @return the trackers
	 */
	public ConcurrentHashMap<String, CoreTracker> getTrackers() {
		return trackers;
	}
	
	@Override
	public String getDescription() {
		return "NemakiWare Core Admin Handler for automatic indexing and CMIS content tracking";
	}
	
	@Override
	public PermissionNameProvider.Name getPermissionName(AuthorizationContext ctx) {
		return PermissionNameProvider.Name.CORE_EDIT_PERM;
	}
	
	@Override
	public void close() throws IOException {
		try {
			if (scheduler != null && !scheduler.isShutdown()) {
				scheduler.shutdown();
				logger.info("NemakiWare Quartz scheduler shut down");
			}
		} catch (SchedulerException e) {
			logger.error("Error shutting down scheduler:", e);
		}
		super.close();
	}

}
