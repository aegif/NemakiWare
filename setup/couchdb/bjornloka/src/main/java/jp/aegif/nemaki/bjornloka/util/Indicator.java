package jp.aegif.nemaki.bjornloka.util;

import java.text.MessageFormat;

public class Indicator {
	private int progress;
	private long processed;
	private long total = 1;
	
	public Indicator() {
		super();
		this.processed = 0;
		this.progress = 0;
	}
	
	public Indicator(long total){
		this();
		this.total = total;
	}

	public void increment(){
		this.processed += 1;
		logWithPercentage();
	}
	
	private void logWithPercentage(){
		double percentage = (double)processed / (double)total * 100.0;
		if(percentage >= this.progress){
			System.out.println(MessageFormat.format("{0}% completed ({1} items dumped)", this.progress, this.processed));
			this.progress += 10;
		}
	}
}
