package eionet.cr.config;

import java.io.IOException;
import java.util.Properties;

import org.apache.log4j.Logger;

/**
 * 
 * @author heinljab
 *
 */
public class GeneralConfig {
	
	/** */
	private static final String PROPERTIES_FILE_NAME = "osi.properties";

	/** */
	public static final String DB_URL = "db.url";
	public static final String DB_DRV = "db.drv";
	public static final String DB_USER_ID = "db.usr";
	public static final String DB_USER_PWD = "db.pwd";

	/** */
	private static Logger logger = Logger.getLogger(GeneralConfig.class);

	/** */
	private static Properties properties = null;
	
	/** */
	private static void init(){
		properties = new Properties();
		try{
			properties.load(GeneralConfig.class.getClassLoader().getResourceAsStream("osi.properties"));
		}
		catch (IOException e){
			logger.fatal("Failed to load properties from " + PROPERTIES_FILE_NAME, e);
		}
	}

	/**
	 * 
	 * @param name
	 * @return
	 */
	public static synchronized String getProperty(String key){
		
		if (properties==null)
			init();
		
		return properties.getProperty(key);
	}

	/**
	 * 
	 * @param key
	 * @param defaultValue
	 * @return
	 */
	public static synchronized String getProperty(String key, String defaultValue){
		
		if (properties==null)
			init();
		
		return properties.getProperty(key, defaultValue);
	}
}
