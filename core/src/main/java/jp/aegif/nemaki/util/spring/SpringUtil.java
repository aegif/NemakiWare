package jp.aegif.nemaki.util.spring;

import java.util.Map;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;

public class SpringUtil {
    public static <T> T getBeanByType(ApplicationContext applicationContext, final Class<T> claz) throws UnsupportedOperationException, BeansException {
        Map beansOfType = applicationContext.getBeansOfType(claz);
        final int size = beansOfType.size();
        switch (size) {
            case 0:
                throw new UnsupportedOperationException("No bean found of type" + claz);
            case 1:
                String name = (String) beansOfType.keySet().iterator().next();
                return claz.cast(applicationContext.getBean(name, claz));
            default:
                throw new UnsupportedOperationException("Ambigious beans found of type" + claz);
        }
    }
}
