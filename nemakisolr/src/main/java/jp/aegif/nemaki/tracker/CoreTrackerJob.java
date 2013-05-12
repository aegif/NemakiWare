package jp.aegif.nemaki.tracker;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Job class of index tracking
 * @author linzhixing
 *
 */
public class CoreTrackerJob implements Job{
	
	public CoreTrackerJob(){
		super();
	}
	
	public void execute(JobExecutionContext jec) throws JobExecutionException{
		CoreTracker coreTracker = (CoreTracker) jec.getJobDetail().getJobDataMap().get("TRACKER");
		//TODO When using multi threads Job, kind of exclusive control will be required. 
		coreTracker.indexNodes("AUTO");
	}
	
}
