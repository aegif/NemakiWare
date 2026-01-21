package jp.aegif.nemaki.bjornloka.util;

import java.text.MessageFormat;

public class Indicator {
	private int percent;
	private int percentStep;
	private long processed;
	private long total = 1;
	
	public Indicator() {
		this.processed = 0;
		this.percent = 0;
		this.percentStep = 10;
	}
	
	public Indicator(long total){
		this();
		this.total = total;
	}
	
	public Indicator(long total, int percentStep){
		this(total);
		this.percentStep = percentStep;
	}
	
	public void indicate(){
		indicate(1);
	}
	
	public void indicate(long processStep){
		if(this.processed + processStep <= total){
			this.processed += processStep;
		}else{
			this.processed = total;
		}
		
		double calculatedPercent = (double)processed / (double)total * 100.0;
		if(calculatedPercent >= percent + percentStep){
			//Update percent
			percent = (int)((calculatedPercent / (double)percentStep)) * percentStep;	//floor
			System.out.println(MessageFormat.format("{0}% completed ({1}/{2})", this.percent, this.processed, this.total));
		}
	}
}