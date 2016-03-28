package edu.umich.its.cpm;

import org.springframework.boot.actuate.endpoint.Endpoint;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;

import javax.servlet.ServletContext;

import org.json.JSONObject;

import edu.umich.its.cpm.MigrationRepository;

/**
 * this is a single static page serves as /status/ping
 * 
 * @author zqian
 *
 */
@Component
public class StatusPingEndpoint implements Endpoint<String>{

	private static ServletContext servletContext = null;

	@Autowired
	MigrationRepository repository;
	
	public String getId() {
		return "status/ping";
	}

	public boolean isEnabled() {
		return true;
	}

	public boolean isSensitive() {
		return true;
	}

	public String invoke() {
		String rv = "";
		
		// Custom logic to build the output
		try {
			// output the git version, CTools and Box url 
			HashMap<String, Object> statusMap = new HashMap<String, Object>();
			statusMap.put("status", "OK");
			statusMap.put("database connection", repository.validate() >= 0);
			rv = (new JSONObject(statusMap)).toString();
			
		} catch (Throwable e) {
			e.printStackTrace();
		}
		
		return 	rv;
	}
	
	public void setServletContext(ServletContext servletContext){
		this.servletContext = servletContext;
	}
	
}