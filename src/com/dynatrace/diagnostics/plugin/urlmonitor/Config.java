/***************************************************
 * dynaTrace Diagnostics (c) dynaTrace software GmbH
 *
 * @file: Config.java
 * @date: 28.04.2015
 * @author: cwat-alechner
 */
package com.dynatrace.diagnostics.plugin.urlmonitor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.logging.Logger;

import com.dynatrace.diagnostics.httpclient.api.enums.RequestType;
import com.dynatrace.diagnostics.pdk.Migrator;
import com.dynatrace.diagnostics.pdk.MonitorEnvironment;
import com.dynatrace.diagnostics.pdk.Property;
import com.dynatrace.diagnostics.pdk.PropertyContainer;
import com.dynatrace.diagnostics.sdk.types.BooleanType;
import com.dynatrace.diagnostics.sdk.types.LongType;
import com.dynatrace.diagnostics.sdk.types.StringType;

/**
 * Loads the pluginconfiguration into an instance of this class. acts also as plugin-configuraiton migrator {@link Migrator}.
 *
 * @author cwat-alechner
 */
public class Config implements Migrator {

	private static final Logger log = Logger.getLogger(Config.class.getName());

	// configuration constants
	protected static final String CONFIG_HOST = "host";
	protected static final String CONFIG_PROTOCOL = "protocol";
	protected static final String CONFIG_PATH = "path";
	protected static final String CONFIG_HTTP_PORT = "httpPort";
	protected static final String CONFIG_HTTPS_PORT = "httpsPort";
	protected static final String CONFIG_METHOD = "method";
	protected static final String CONFIG_POST_DATA = "postData";
	protected static final String CONFIG_USER_AGENT = "userAgent";
	protected static final String CONFIG_HTTP_VERSION = "httpVersion";
	protected static final String CONFIG_MAX_REDIRECTS = "maxRedirects";
	protected static final String CONFIG_SOCKET_TIMEOUT = "socketTimeout";
	protected static final String CONFIG_CONNECTION_TIMEOUT = "connectionTimeout";

	protected static final String CONFIG_DT_TAGGING = "dtTagging";
	protected static final String CONFIG_DT_TIMER_NAME = "dtTimerName";

	protected static final String CONFIG_MATCH_CONTENT = "matchContent";
	protected static final String CONFIG_SEARCH_STRING = "searchString";
	protected static final String CONFIG_COMPARE_BYTES = "compareBytes";

	protected static final String CONFIG_SERVER_AUTH = "serverAuth";
	protected static final String CONFIG_SERVER_USERNAME = "serverUsername";
	protected static final String CONFIG_SERVER_PASSWORD = "serverPassword";
	protected static final String CONFIG_SERVER_AUTH_PREEMPTIVE = "serverAuthPreemptive";

	protected static final String CONFIG_USE_PROXY = "useProxy";
	protected static final String CONFIG_PROXY_HOST = "proxyHost";
	protected static final String CONFIG_PROXY_PORT = "proxyPort";
	protected static final String CONFIG_PROXY_AUTH = "proxyAuth";
	protected static final String CONFIG_PROXY_USERNAME = "proxyUsername";
	protected static final String CONFIG_PROXY_PASSWORD = "proxyPassword";
	protected static final String CONFIG_PROXY_AUTH_PREEMPTIVE = "proxyAuthPreemptive";

	protected static final String CONFIG_IGNORE_CERTIFICATE = "ignoreCertificate";

	private static final String PROTOCOL_HTTPS = "https";
	private static final String PROTOCOL_HTTP = "http";

	private static final String CONFIG_USE_CUSTOM_HEADER = "useCustomHeader";

	private static final String CONFIG_CUSTOM_HEADER = "customHeaderList";
	
	

	//String host;
	//String protocol;
	URL url;
	// String method;
	RequestType method;
	/** the postData sent with a post Request; null if no data should be sent. */
	String postData;
	String httpVersion;
	String userAgent;
	int maxRedirects;
	Integer socketTimeout;
	Integer connectionTimeout;
	boolean tagging;
	boolean ignorecert;
	String timerName;
	// content verification
	MatchContent matchContent;
	String searchString;
	// server authentification
	AuthMethod serverAuth;
	String serverUsername;
	String serverPassword;
	boolean serverAuthPreemptive;
	// proxy
	boolean useProxy;
	String proxyHost;
	int proxyPort;
	boolean proxyAuth;
	String proxyUsername;
	String proxyPassword;
	boolean proxyAuthPreemptive;
	long compareBytes;
	//custom header
	boolean useCustomHeader;
	String customHeaderField;
	String[] customHeaderArray;
	HashMap customHeaderMap = new HashMap();

	/**
	 * no arg contructor only needed, to act as MonitorEnvironmentMigrator.
	 */
	public Config() {
	}

	Config(MonitorEnvironment env) throws MalformedURLException {
		String host = env.getConfigString(CONFIG_HOST);
		
		// Legacy protection - If a host hasn't been explictly passed, fall back to environment host.
		if (host == null || host.isEmpty()) host = env.getHost().getAddress();
				
		String protocol = env.getConfigString(CONFIG_PROTOCOL);
		int port;
		if (protocol != null && protocol.contains("https")) {
			port = env.getConfigLong(CONFIG_HTTPS_PORT).intValue();
			protocol = PROTOCOL_HTTPS;
		} else {
			port = env.getConfigLong(CONFIG_HTTP_PORT).intValue();
			protocol = PROTOCOL_HTTP;
		}
		String path = fixPath(env.getConfigString(CONFIG_PATH));
		ignorecert = env.getConfigBoolean(CONFIG_IGNORE_CERTIFICATE);

		
		url = new URL(protocol, host, port, path);
		//url = new URL(protocol, env.getHost().getAddress(), port, path);

		String methodString = env.getConfigString(CONFIG_METHOD);
		if ("POST".equalsIgnoreCase(methodString)) {
			method = RequestType.POST;
		} else if ("HEAD".equalsIgnoreCase(methodString)) {
			method = RequestType.HEAD;
		} else {
			method = RequestType.GET;
		}
		postData = env.getConfigString(CONFIG_POST_DATA);
		if (postData != null && postData.length() == 0) {
			postData = null;
		}
		httpVersion = env.getConfigString(CONFIG_HTTP_VERSION);
		userAgent = env.getConfigString(CONFIG_USER_AGENT);
		if (userAgent == null) {
			userAgent = "dynaTrace/6";
		}
		tagging = env.getConfigBoolean(CONFIG_DT_TAGGING) == null ? false : env.getConfigBoolean(CONFIG_DT_TAGGING);
		if (tagging) {
			timerName = env.getConfigString(CONFIG_DT_TIMER_NAME) == null ? ""
					: env.getConfigString(CONFIG_DT_TIMER_NAME);
		}
		maxRedirects = env.getConfigLong(CONFIG_MAX_REDIRECTS) == null ? 0
				: env.getConfigLong(CONFIG_MAX_REDIRECTS).intValue();
		socketTimeout = env.getConfigLong(CONFIG_SOCKET_TIMEOUT) == null ? Integer.valueOf(0)
				: Integer.valueOf(env.getConfigLong(
						CONFIG_SOCKET_TIMEOUT).intValue());
		connectionTimeout = env.getConfigLong(CONFIG_CONNECTION_TIMEOUT) == null ? Integer.valueOf(0)
				: Integer.valueOf(env.getConfigLong(
						CONFIG_CONNECTION_TIMEOUT).intValue());
		String matchContent = env.getConfigString(CONFIG_MATCH_CONTENT);
		this.matchContent = MatchContent.getByConfigValue(matchContent);

		searchString = env.getConfigString(CONFIG_SEARCH_STRING) == null ? "" : env.getConfigString(CONFIG_SEARCH_STRING);
		compareBytes = env.getConfigLong(CONFIG_COMPARE_BYTES) == null ? 0 : env.getConfigLong(CONFIG_COMPARE_BYTES);

		serverAuth = AuthMethod.getByConfigValue(env.getConfigString(CONFIG_SERVER_AUTH));
		if (serverAuth != AuthMethod.disabled) {
			serverUsername = env.getConfigString(CONFIG_SERVER_USERNAME);
			serverPassword = env.getConfigPassword(CONFIG_SERVER_PASSWORD);
			serverAuthPreemptive = env.getConfigBoolean(CONFIG_SERVER_AUTH_PREEMPTIVE) == null ? false
					: env.getConfigBoolean(CONFIG_SERVER_AUTH_PREEMPTIVE);
		}

		useProxy = env.getConfigBoolean(CONFIG_USE_PROXY) == null ? false : env.getConfigBoolean(CONFIG_USE_PROXY);
		if (useProxy) {
			proxyHost = env.getConfigString(CONFIG_PROXY_HOST);
			proxyPort = env.getConfigLong(CONFIG_PROXY_PORT) == null ? 0
					: env.getConfigLong(CONFIG_PROXY_PORT).intValue();
		}
		proxyAuth = env.getConfigBoolean(CONFIG_PROXY_AUTH) == null ? false : env.getConfigBoolean(CONFIG_PROXY_AUTH);
		if (proxyAuth) {
			proxyUsername = env.getConfigString(CONFIG_PROXY_USERNAME);
			proxyPassword = env.getConfigPassword(CONFIG_PROXY_PASSWORD);
			proxyAuthPreemptive = env.getConfigBoolean(CONFIG_PROXY_AUTH_PREEMPTIVE) == null ? false
					: env.getConfigBoolean(CONFIG_PROXY_AUTH_PREEMPTIVE);
		}
		
		//add custom header(s)
		//added by Robert Kühn, T-Systems Multimedia Solutions GmbH, robert.kuehn@t-systems.com
		useCustomHeader = env.getConfigBoolean(CONFIG_USE_CUSTOM_HEADER) == null ? false : env.getConfigBoolean(CONFIG_USE_CUSTOM_HEADER);
		if (useCustomHeader) {
			customHeaderField = env.getConfigString(CONFIG_CUSTOM_HEADER);
			log.fine("Value of " + CONFIG_CUSTOM_HEADER + ": " + customHeaderField);
			//split customHeaderField by , 
			String[] customHeaderArray = customHeaderField.split(",");
			String headerValueTemp = null;
			for (int i = 0; i < customHeaderArray.length; i++) {
				headerValueTemp = customHeaderArray[i].trim();
				log.fine("Value of Custom Header Split # " + i + ": " + headerValueTemp);
				
				// ignore empty entries 
				if (headerValueTemp.isEmpty()) {
					continue;
				}
				
				//find : to split 
				int seperator = headerValueTemp.indexOf(":");
				if (seperator > 1) {
					String key = headerValueTemp.substring(0, seperator).trim();
					String value = headerValueTemp.substring(seperator+1).trim();
					
					customHeaderMap.put(key, value);
					
					log.fine("Value of key and value: " + key + ": " + value);
				} else {
					log.warning("Invalid format for custom header field: " + headerValueTemp);
					log.warning("Expected format: header1: value1, header2: value2");
				}
				
			}
		}
	}

	private String fixPath(String path) {
		if (path == null)
			return "/";
		if (!path.startsWith("/"))
			return "/" + path;
		return path;
	}

	@Override
	public void migrate(PropertyContainer properties, int major, int minor, int micro, String qualifier) {
		// JLT-41859: change protocol value from http:// and https:// to http and https
		Property protocolProp = properties.getProperty(CONFIG_PROTOCOL);
		if (protocolProp != null) {
			if (protocolProp.getValue() != null && protocolProp.getValue().contains("https")) {
				protocolProp.setValue("https");
			} else {
				protocolProp.setValue("http");
			}
		}

		// JLT-123725: introduce some features (from community, integrate common http client, ...)
		// migrate timeout to socketTimout and connectionTimeout:
		final String CONFIG_TIMEOUT = "timeout";
		Property timeoutProp = properties.getProperty(CONFIG_TIMEOUT);
		Property soTimoutProp = properties.getProperty(CONFIG_SOCKET_TIMEOUT);
		Property connTimoutProp = properties.getProperty(CONFIG_CONNECTION_TIMEOUT);
		if (timeoutProp != null) {
			log.fine(String.format("migrate timeout property: old value=%s, soTimeout exists: %b, coTimeout exists: %b",
					timeoutProp.getValue(), soTimoutProp == null, connTimoutProp == null));
			if (soTimoutProp == null)
				properties.addProperty(CONFIG_SOCKET_TIMEOUT, LongType.TYPE_ID, timeoutProp.getValue());
			if (connTimoutProp == null)
				properties.addProperty(CONFIG_CONNECTION_TIMEOUT, LongType.TYPE_ID, timeoutProp.getValue());
			properties.remove(timeoutProp);
		}

		// migrate serverAuth from bool to String (list)
		Property authProp = properties.getProperty(CONFIG_SERVER_AUTH);
		Property userProp = properties.getProperty(CONFIG_SERVER_USERNAME);
		if (authProp != null && authProp.getType().equals(BooleanType.TYPE_ID)) {
			properties.remove(authProp);
			AuthMethod authMethodToMigrate;
			if (userProp != null && userProp.getValue() != null && "true".equalsIgnoreCase(authProp.getValue()))
				authMethodToMigrate = AuthMethod.basic;
			else if (userProp != null && userProp.getValue() != null && userProp.getValue().indexOf('\\') != -1)
				authMethodToMigrate = AuthMethod.NTLM;
			else
				authMethodToMigrate = AuthMethod.disabled;
			log.fine(String.format("migrate serverAuth property: old value=%s, new value=%s, user=%s", authProp.getValue(),
					authMethodToMigrate.getConfigPropertyValue(), userProp == null ? "<null>" : userProp.getValue()));
			properties.addProperty(CONFIG_SERVER_AUTH, StringType.TYPE_ID, authMethodToMigrate.getConfigPropertyValue());
		}
	}

}