package jp.aegif.nemaki.repository;

import java.util.HashMap;

import javax.servlet.ServletContext;

import org.apache.chemistry.opencmis.server.impl.CmisRepositoryContextListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.web.context.ServletContextAware;

public class CmisLifecycleBean implements ServletContextAware,
InitializingBean, DisposableBean
{
    private ServletContext servletContext;
    private NemakiCmisServiceFactory factory;

    @Override
    public void setServletContext(ServletContext servletContext)
    {
        this.servletContext = servletContext;
    }

    public void setCmisServiceFactory(NemakiCmisServiceFactory factory)
    {
        this.factory = factory;
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        if (factory != null)
        {
            factory.init(new HashMap<String, String>());
            servletContext.setAttribute(CmisRepositoryContextListener.SERVICES_FACTORY, factory);
        }
    }

    @Override
    public void destroy() throws Exception
    {
        if (factory != null)
        {
            factory.destroy();
        }
    }

}