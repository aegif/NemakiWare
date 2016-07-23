package jp.aegif.nemaki.util.spring.aspect.log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface LogParam {
	String value();
}
