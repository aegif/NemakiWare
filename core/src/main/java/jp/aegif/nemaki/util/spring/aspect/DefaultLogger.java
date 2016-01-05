package jp.aegif.nemaki.util.spring.aspect;

import java.util.Arrays;

import javax.annotation.PostConstruct;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.aspectj.lang.ProceedingJoinPoint;

public class DefaultLogger {

	private static Logger log = Logger.getLogger(DefaultLogger.class);
	private String logLevel;
	private boolean returnValue;
	private boolean fullQualifiedName;
	private boolean arguments;
	private boolean beforeEnabled;
	private boolean afterEnabled;
	private boolean callContextEnabled;

   @PostConstruct
	public void init() {
		if (log != null){
			log.setLevel(Level.toLevel(logLevel));
		}
	}

	public Object aroundMethod(ProceedingJoinPoint jp) throws Throwable{

		StringBuilder sb = new StringBuilder();
		Object[] args = jp.getArgs();

		//Parse callContext
		if(callContextEnabled){
			CallContext callContext = getCallContext(args);
			if(callContext == null){
				sb.append("N/A : ");
			}else{
				String userId = callContext.getUsername();
				sb.append(userId + " : ");
			}
		}

		//Method name
		if(fullQualifiedName){
			sb.append(jp.getTarget().getClass().getName());
		}else{
			sb.append(jp.getTarget().getClass().getSimpleName());
		}
		sb.append("#").append(jp.getSignature().getName());
		if(arguments){
			sb.append(Arrays.asList(args));
		}

		//Before advice
		if(beforeEnabled){
			log.info(sb.toString());
		}

		//Execute method
		try{
			Object result = jp.proceed();

			//After advice
			if(afterEnabled){
				sb.append(" returned ");
				if (returnValue && result != null)
					sb.append(result.toString());

				log.info(sb.toString());
			}

			return result;
		}catch(Exception e){
			log.error("Error:", e);
			throw e;
		}
	}

	private CallContext getCallContext(Object[] args){
		if(args != null && args.length > 0){
			for(int i=0; i < args.length; i++){
				Object arg = args[i];
				if(arg != null && arg instanceof CallContext){
					CallContext callContext = (CallContext)arg;
					return callContext;
				}
			}
		}
		return null;
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

	public void setBeforeEnabled(boolean beforeEnabled) {
		this.beforeEnabled = beforeEnabled;
	}

	public void setAfterEnabled(boolean afterEnabled) {
		this.afterEnabled = afterEnabled;
	}

	public void setCallContextEnabled(boolean callContextEnabled) {
		this.callContextEnabled = callContextEnabled;
	}
}
