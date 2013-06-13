package jp.aegif.nemaki.api.resources;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sun.jersey.spi.container.servlet.ServletContainer;

public class TestServletContainer extends ServletContainer {

    @Override
    public void service(HttpServletRequest request,
            HttpServletResponse response) throws ServletException,
            IOException {
        authenticateFilter(request, response, null);
        super.service(request, response);
    }

	private void authenticateFilter(HttpServletRequest request,
			HttpServletResponse response, Object object) {
		
		UserInfo userInfo = new UserInfo();
		userInfo.userId = "admin";
		userInfo.password = "admin";
		String[] roles = new String[] {"User"};
		userInfo.roles = roles;
		
		request.getSession().setAttribute("USER_INFO", userInfo);
	} 
    
}
