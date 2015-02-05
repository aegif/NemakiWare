package jp.aegif.nemaki.util.spring.aspect;

import java.util.Arrays;

import javax.annotation.PostConstruct;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;

public class DefaultLogger {

	private static Logger log = Logger.getLogger(DefaultLogger.class);
	private String logLevel;
	private boolean returnValue;
	private boolean fullQualifiedName;
	private boolean arguments;

   @PostConstruct
	public void init() {
		if (log != null){
			log.setLevel(Level.toLevel(logLevel));
		}
	}

	public Object aroundMethod(ProceedingJoinPoint jp) throws Throwable{
		//Before advice
		StringBuilder sb = new StringBuilder();
		if(fullQualifiedName){
			sb.append(jp.getTarget().getClass().getName());
		}else{
			sb.append(jp.getTarget().getClass().getSimpleName());
		}
		sb.append("#").append(jp.getSignature().getName());
		if(arguments){
			Object[] args = jp.getArgs();
			sb.append(Arrays.asList(args));
		}
		
		log.debug(sb.toString());

		//Execute method
		try{
			Object result = jp.proceed();

			//After advice
			sb.append(" returned ");
			if (returnValue && result != null)
				sb.append(result.toString());
			log.debug(sb.toString());

			return result;
		}catch(Exception e){
			log.error("Error:", e);
			throw e;
		}
	}

	public void setLogLevel(String logLevel) {
		this.logLevel = logLevel;
	}

	public void setReturnValue(boolean returnValue) {
		this.returnValue = returnValue;
	}

	public void setFullQualifiedName(boolean fullQualifiedName) {
		this.fullQualifiedName = fullQualifiedName;
	}

	public void setArguments(boolean arguments) {
		this.arguments = arguments;
	}
}
