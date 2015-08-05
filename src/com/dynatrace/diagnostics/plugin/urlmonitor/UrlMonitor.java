package com.dynatrace.diagnostics.plugin.urlmonitor;

import com.dynatrace.diagnostics.global.Constants;
import com.dynatrace.diagnostics.pdk.*;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpVersion;
import org.apache.http.auth.*;
import org.apache.http.auth.params.AuthPNames;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.NTLMScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EncodingUtils;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitor which requests URLs via HTTP GET/POST/HEAD and returns measures for the requests.
 * Redirects are followed to some extent.
 * possible authentication schemes are basic and NTLM.
 * Proxies are supported.
 */
public class UrlMonitor implements Monitor, Migrator {

	private static final int READ_CHUNK_SIZE = 1024;
	private static final double MILLIS = 0.000001;
	private static final double SECS = 0.000000001;

	private static final String HTTP_1_0 = "1.0";
//	private static final String HTTP_1_1 = "1.1"; //default anyways

	// configuration constants
	private static final String CONFIG_PROTOCOL = "protocol";
	private static final String CONFIG_PATH = "path";
	private static final String CONFIG_HTTP_PORT = "httpPort";
	private static final String CONFIG_HTTPS_PORT = "httpsPort";
	private static final String CONFIG_METHOD = "method";
	private static final String CONFIG_POST_DATA = "postData";
	private static final String CONFIG_USER_AGENT = "userAgent";
	private static final String CONFIG_HTTP_VERSION = "httpVersion";
	private static final String CONFIG_MAX_REDIRECTS = "maxRedirects";
	//modified by Robert Kühn, robert.kuehn@t-systems.com
	private static final String CONFIG_MAX_SOCKET_TIMEOUT = "maxSocketTimeout";
	private static final String CONFIG_MAX_CONNECTION_TIMEOUT = "maxConnectionTimeout";

	private static final String CONFIG_DT_TAGGING = "dtTagging";
	private static final String CONFIG_DT_TIMER_NAME = "dtTimerName";

	private static final String CONFIG_MATCH_CONTENT = "matchContent";
	private static final String CONFIG_SEARCH_STRING = "searchString";
	private static final String CONFIG_COMPARE_BYTES = "compareBytes";

	private static final String CONFIG_SERVER_AUTH = "serverAuth";
	private static final String CONFIG_SERVER_USERNAME = "serverUsername";
	private static final String CONFIG_SERVER_PASSWORD = "serverPassword";

	private static final String CONFIG_USE_PROXY = "useProxy";
	private static final String CONFIG_PROXY_HOST = "proxyHost";
	private static final String CONFIG_PROXY_PORT = "proxyPort";
	private static final String CONFIG_PROXY_AUTH = "proxyAuth";
	private static final String CONFIG_PROXY_USERNAME = "proxyUsername";
	private static final String CONFIG_PROXY_PASSWORD = "proxyPassword";

	private static final String CONFIG_IGNORE_CERTIFICATE = "ignoreCertificate";

	// measure constants
	private static final String METRIC_GROUP = "URL Monitor";
	private static final String MSR_HOST_REACHABLE = "HostReachable";
	private static final String MSR_HEADER_SIZE = "HeaderSize";
	private static final String MSR_FIRST_RESPONSE_DELAY = "FirstResponseDelay";
	private static final String MSR_RESPONSE_COMPLETE_TIME = "ResponseCompleteTime";
	private static final String MSR_RESPONSE_SIZE = "ResponseSize";
	private static final String MSR_THROUGHPUT = "Throughput";
	private static final String MSR_HTTP_STATUS_CODE = "HttpStatusCode";
	private static final String MSR_CONN_CLOSE_DELAY = "ConnectionCloseDelay";
	private static final String MSR_CONTENT_VERIFIED = "ContentVerified";
	//modified by Robert Kühn, robert.kuehn@t-systems.com
	private static final String MSR_SOCKET_TIMEOUT = "SocketTimedOut";
	private static final String MSR_CONNECT_TIMEOUT = "ConnectionTimedOut";

	private static final Logger log = Logger.getLogger(UrlMonitor.class.getName());
	private static final String PROTOCOL_HTTPS_IGNORECERT = "https+ignorecert";
	private static final String PROTOCOL_HTTPS = "https";
	private static final String PROTOCOL_HTTP = "http";
	private ThreadSafeClientConnManager connectionManager;


	private enum MatchContent {disabled, successIfMatch, errorIfMatch, bytesMatch}

	private Config config;

	private DefaultHttpClient httpClient;

	@Override
	public Status setup(MonitorEnvironment env) throws Exception {
		Status status = new Status(Status.StatusCode.Success);
		connectionManager = new ThreadSafeClientConnManager();
		httpClient = new DefaultHttpClient(connectionManager);

		config = readConfig(env);

		SSLSocketFactory dumbSslSocketFactory = new SSLSocketFactory(new DumbTrustStrategy(), new AllowAllHostnameVerifier());
		//changed by Rick B -- richard.boyd@dynatrace.com
		//Scheme https = new Scheme(PROTOCOL_HTTPS_IGNORECERT, config.url.getPort(), dumbSslSocketFactory);
		Scheme https;
		if(config.url.getPort()==-1) {
			https = new Scheme(PROTOCOL_HTTPS_IGNORECERT, config.url.getDefaultPort(), dumbSslSocketFactory);
		} else {
			https = new Scheme(PROTOCOL_HTTPS_IGNORECERT, config.url.getPort(), dumbSslSocketFactory);
		}

		
		
		connectionManager.getSchemeRegistry().register(https);
		return status;
	}

	@Override
	public void teardown(MonitorEnvironment env) throws Exception {
		connectionManager.shutdown();
	}

	@Override
	public Status execute(MonitorEnvironment env) throws Exception {
		Status status = new Status();

		//in case plug-in returns PartialSuccess the hostReachable measure will always return 0
		Collection<MonitorMeasure> hostReachableMeasures = env.getMonitorMeasures(METRIC_GROUP, MSR_HOST_REACHABLE);
		if (status.getStatusCode().getBaseCode() == Status.StatusCode.Success.getBaseCode() && hostReachableMeasures != null) {
			for (MonitorMeasure measure : hostReachableMeasures)
				measure.setValue(0);
		}

		// measurement variables
		int httpStatusCode = 0;
		int headerSize = 0;
		long firstResponseTime = 0;
		long responseCompleteTime = 0;
		int inputSize = 0;
		long connectionCloseDelay = 0;
		boolean verified = false;
		long time;
		//modified by Robert Kühn, robert.kuehn@t-systems.com
		//default values for socket/connection timeout
		int connectionTimedOut = 0;
		int socketTimedOut = 0;

		if (config.url == null || (!config.url.getProtocol().equals("http") && !config.url.getProtocol().equals("https"))) {
		    status.setShortMessage("only protocols http and https are allowed.");
		    status.setMessage("only protocols http and https are allowed." );
		    status.setStatusCode(Status.StatusCode.PartialSuccess);
		    return status;
		}

		// create a HTTP client and method
		HttpUriRequest httpRequest = createHttpMethod(config);
		if (httpRequest == null) {
			status.setMessage("Unknown HTTP method: " + config.method);
			status.setStatusCode(Status.StatusCode.ErrorInternal);
		    return status;
		}

		// try to set parameters
		try {
			setHttpParameters(httpRequest, config);
		} catch (Exception ex) {
			status.setStatusCode(Status.StatusCode.ErrorInternal);
			status.setMessage("Setting HTTP client parameters failed");
			status.setShortMessage(ex == null ? "" : ex.getClass().getSimpleName());
			status.setMessage(ex == null ? "" : ex.getMessage());
			status.setException(ex);
			return status;
		}

		StringBuilder messageBuffer = new StringBuilder("URL: ");
		messageBuffer.append(config.url).append("\r\n");

		HttpContext localContext = new BasicHttpContext();
		BasicHttpResponse response = null;
		try {
			if (log.isLoggable(Level.INFO))
				log.info("Executing method: " + config.method + ", URI: " + httpRequest.getURI());

			// connect
			time = System.nanoTime();
			response = (BasicHttpResponse)httpClient.execute(httpRequest, localContext);
			httpStatusCode = response.getStatusLine().getStatusCode();
			firstResponseTime = System.nanoTime() - time;

			// calculate header size
			headerSize = calculateHeaderSize(response.getAllHeaders());

			// read response data
			InputStream inputStream = response.getEntity().getContent();
			if (inputStream != null) {
				int bytesRead;
				byte[] data = new byte[READ_CHUNK_SIZE];
				String charset = response.getEntity().getContentEncoding() == null ? "utf-8" : response.getEntity().getContentEncoding().getValue();
				StringBuilder buf = new StringBuilder();
				while ((bytesRead = inputStream.read(data)) > 0) {
					if (config.matchContent != MatchContent.disabled && config.matchContent != MatchContent.bytesMatch) {
						//FIXME may cause excessive memory usage
						buf.append(EncodingUtils.getString(data, 0, bytesRead, charset));
					}
					inputSize += bytesRead;
				}
				inputStream.close();
				responseCompleteTime = System.nanoTime() - time;

				if (config.matchContent == MatchContent.bytesMatch) {
					verified = (inputSize == config.compareBytes);
					if(!verified) {
						messageBuffer.append("Expected ").append(config.compareBytes).append(" bytes, but was ").append(inputSize).append(" bytes");
					}
				} else if (config.matchContent != MatchContent.disabled) {
					try {
						boolean found = buf.toString().contains(config.searchString);
						if (config.matchContent == MatchContent.successIfMatch) {
							verified = found;
							if(!verified)
								messageBuffer.append("Expected string \"").append(config.searchString).append("\" didn't match.");
						} else { // error if match
							verified = !found;
							if(!verified)
								messageBuffer.append("Expected string \"").append(config.searchString).append("\" matched.");
						}
					} catch (Exception ex) {
						messageBuffer.append("Verifying the response content failed");
						status.setException(ex);
						if (log.isLoggable(Level.SEVERE))
							log.severe(status.getMessage() + ": " + ex);
					    status.setStatusCode(Status.StatusCode.PartialSuccess);
						status.setMessage(messageBuffer.toString());
					    return status;
					}
				}
				connectionCloseDelay = System.nanoTime();
			} // end read response
		} catch (ConnectException ce) {
			status.setException(ce);
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			status.setShortMessage(ce == null ? "" : ce.getClass().getSimpleName());
			messageBuffer.append(ce == null ? "" : ce.getMessage());
		} catch (SSLException e) {
			status.setException(e);
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			status.setShortMessage(e == null ? "" : e.getClass().getSimpleName());
			messageBuffer.append("SSL handshake failed, this may be caused by an incorrect certificate. Check 'Disable certificate validation' to override this.\nException message: ");
			messageBuffer.append(e == null ? "" : e.getMessage());
			//modified by Robert Kühn, robert.kuehn@t-systems.com
			//ConnectTimeoutException
		} catch (ConnectTimeoutException cte) {
			connectionTimedOut = 1;
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			status.setShortMessage(cte == null ? "" : cte.getClass().getSimpleName());
			messageBuffer.append("Connection Timeout: URL " + httpRequest.getURI());
			//modified by Robert Kühn, robert.kuehn@t-systems.com
			//SocketTimeoutException
		} catch (SocketTimeoutException ste) {
			socketTimedOut = 1;
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			status.setShortMessage(ste == null ? "" : ste.getClass().getSimpleName());
			messageBuffer.append("Socket Timeout: URL " + httpRequest.getURI());
		} catch (IOException ioe) {
			status.setException(ioe);
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			status.setShortMessage(ioe == null ? "" : ioe.getClass().getSimpleName());
			messageBuffer.append(ioe == null ? "" : ioe.getMessage());
			if (log.isLoggable(Level.SEVERE))
				log.severe("Requesting URL " + httpRequest.getURI() + " caused exception: " + ioe);
		} catch (IllegalArgumentException iae) {
			status.setException(iae);
			status.setStatusCode(Status.StatusCode.ErrorInternal);
			status.setShortMessage(iae == null ? "" : iae.getClass().getSimpleName());
			messageBuffer.append(iae == null ? "" : iae.getMessage());
			if (log.isLoggable(Level.SEVERE))
				log.severe("Requesting URL " + httpRequest.getURI() + " caused exception: " + iae);
		} finally {
			// always release the connection
			if (response != null && response.getEntity() != null) {
				EntityUtils.consume(response.getEntity());
			}
			if (connectionCloseDelay > 0)
				connectionCloseDelay = System.nanoTime() - connectionCloseDelay;
		}

		// calculate and set the measurements
		Collection<MonitorMeasure> measures;
		if (status.getStatusCode().getBaseCode() == Status.StatusCode.Success.getBaseCode() && (measures = env.getMonitorMeasures(METRIC_GROUP, MSR_HOST_REACHABLE)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(httpStatusCode > 0 ? 1 : 0);
		}
		if (status.getStatusCode().getCode() == Status.StatusCode.Success.getCode()) {
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_HEADER_SIZE)) != null) {
				for (MonitorMeasure measure : measures)
					measure.setValue(headerSize);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_FIRST_RESPONSE_DELAY)) != null) {
				double firstResponseTimeMillis = firstResponseTime * MILLIS;
				for (MonitorMeasure measure : measures)
					measure.setValue(firstResponseTimeMillis);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_RESPONSE_COMPLETE_TIME)) != null) {
				double responseCompleteTimeMillis = responseCompleteTime * MILLIS;
				for (MonitorMeasure measure : measures)
					measure.setValue(responseCompleteTimeMillis);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_RESPONSE_SIZE)) != null) {
				for (MonitorMeasure measure : measures)
					measure.setValue(inputSize);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_THROUGHPUT)) != null) {
				double throughput = 0;
				if (responseCompleteTime > 0) {
					double responseCompleteTimeSecs = responseCompleteTime * SECS;
					double contentSizeKibiByte = inputSize / 1024.0;
					throughput = contentSizeKibiByte / responseCompleteTimeSecs;
				}
				for (MonitorMeasure measure : measures)
					measure.setValue(throughput);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_HTTP_STATUS_CODE)) != null) {
				for (MonitorMeasure measure : measures)
					measure.setValue(httpStatusCode);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_CONN_CLOSE_DELAY)) != null) {
				double connectionCloseDelayMillis = connectionCloseDelay * MILLIS;
				for (MonitorMeasure measure : measures)
					measure.setValue(connectionCloseDelayMillis);
			}
			if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_CONTENT_VERIFIED)) != null) {
				for (MonitorMeasure measure : measures)
					measure.setValue(verified ? 1 : 0);
			}
		}
		//modified by Robert Kühn, robert.kuehn@t-systems.com
		//set connection timeout
		if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_CONNECT_TIMEOUT)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(connectionTimedOut);
		}
		//modified by Robert Kühn, robert.kuehn@t-systems.com
		//set socket timeout
		if ((measures = env.getMonitorMeasures(METRIC_GROUP, MSR_SOCKET_TIMEOUT)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(socketTimedOut);
		}

		status.setMessage(messageBuffer.toString());
		return status;
	}

	private Config readConfig(MonitorEnvironment env) throws MalformedURLException {
		Config config = new Config();

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
		config.ignorecert = env.getConfigBoolean(CONFIG_IGNORE_CERTIFICATE);
		//changed by Rick B -- richard.boyd@dynatrace.com
		//config.url = new URL(protocol, env.getHost().getAddress(), port, path);
		
		if(port==80||port==443){
			config.url = new URL(protocol, env.getHost().getAddress(), path);
		} else {
			config.url = new URL(protocol, env.getHost().getAddress(), port, path);
		}
		
		config.method = env.getConfigString(CONFIG_METHOD) == null ? "GET" : env.getConfigString(CONFIG_METHOD).toUpperCase();
		config.postData = env.getConfigString(CONFIG_POST_DATA);
		config.httpVersion = env.getConfigString(CONFIG_HTTP_VERSION);
		config.userAgent = env.getConfigString(CONFIG_USER_AGENT);
		config.tagging = env.getConfigBoolean(CONFIG_DT_TAGGING) == null ? false : env.getConfigBoolean(CONFIG_DT_TAGGING);
		if(config.tagging) {
			config.timerName = env.getConfigString(CONFIG_DT_TIMER_NAME) == null ? "" : env.getConfigString(CONFIG_DT_TIMER_NAME);
		}
		config.maxRedirects = env.getConfigLong(CONFIG_MAX_REDIRECTS) == null ? 0 : env.getConfigLong(CONFIG_MAX_REDIRECTS).intValue();
		//modified by Robert Kühn, robert.kuehn@t-systems.com
		//connection/socket timeout
		config.maxSocketTimeout = env.getConfigLong(CONFIG_MAX_SOCKET_TIMEOUT) == null ? 0 : env.getConfigLong(CONFIG_MAX_SOCKET_TIMEOUT).intValue();
		config.maxConnectionTimeout = env.getConfigLong(CONFIG_MAX_CONNECTION_TIMEOUT) == null ? 0 : env.getConfigLong(CONFIG_MAX_CONNECTION_TIMEOUT).intValue();

		String matchContent = env.getConfigString(CONFIG_MATCH_CONTENT);
		if ("Success if match".equals(matchContent))
			config.matchContent = MatchContent.successIfMatch;
		else if ("Error if match".equals(matchContent))
			config.matchContent = MatchContent.errorIfMatch;
		else if ("Expected size in bytes".equals(matchContent))
			config.matchContent = MatchContent.bytesMatch;
		else
			config.matchContent = MatchContent.disabled;
		config.searchString = env.getConfigString(CONFIG_SEARCH_STRING) == null ? "" : env.getConfigString(CONFIG_SEARCH_STRING);
		config.compareBytes = env.getConfigLong(CONFIG_COMPARE_BYTES) == null ? 0 : env.getConfigLong(CONFIG_COMPARE_BYTES);

		config.serverAuth = env.getConfigBoolean(CONFIG_SERVER_AUTH) == null ? false : env.getConfigBoolean(CONFIG_SERVER_AUTH);
		if (config.serverAuth) {
			config.serverUsername = env.getConfigString(CONFIG_SERVER_USERNAME);
			config.serverPassword = env.getConfigPassword(CONFIG_SERVER_PASSWORD);
		}

		config.useProxy = env.getConfigBoolean(CONFIG_USE_PROXY) == null ? false : env.getConfigBoolean(CONFIG_USE_PROXY);
		if (config.useProxy) {
			config.proxyHost = env.getConfigString(CONFIG_PROXY_HOST);
			config.proxyPort = env.getConfigLong(CONFIG_PROXY_PORT) == null ? 0 : env.getConfigLong(CONFIG_PROXY_PORT).intValue();
		}
		config.proxyAuth = env.getConfigBoolean(CONFIG_PROXY_AUTH) == null ? false : env.getConfigBoolean(CONFIG_PROXY_AUTH);
		if (config.proxyAuth) {
			config.proxyUsername = env.getConfigString(CONFIG_PROXY_USERNAME);
			config.proxyPassword = env.getConfigPassword(CONFIG_PROXY_PASSWORD);
		}
		return config;
	}

	private String fixPath(String path) {
		if (path == null) return "/";
		if (!path.startsWith("/")) return "/" + path;
		return path;
	}

	private HttpUriRequest createHttpMethod(Config config) {
		String url = config.url.toString();

		if (config.ignorecert && url.startsWith(PROTOCOL_HTTPS)) {
			url = PROTOCOL_HTTPS_IGNORECERT + url.substring(PROTOCOL_HTTPS.length());
		}

		HttpUriRequest httpRequest = null;
		if ("GET".equals(config.method)) {
			httpRequest = new HttpGet(url);
		} else if ("HEAD".equals(config.method)) {
			httpRequest = new HttpHead(url);
		} else if ("POST".equals(config.method)) {
			httpRequest = new HttpPost(url);
			// set the POST data
			if (config.postData != null && config.postData.length() > 0) {
				try {
					StringEntity requestEntity = new StringEntity(config.postData, "application/x-www-form-urlencoded", "UTF-8");
					((HttpPost) httpRequest).setEntity(requestEntity);
				} catch (UnsupportedEncodingException uee) {
					if (log.isLoggable(Level.WARNING))
						log.warning("Encoding POST data failed: " + uee);
				}
			}
		}
		return httpRequest;
	}

	private void setHttpParameters(HttpUriRequest httpRequest, Config config) throws IllegalStateException, UnknownHostException {
		HttpVersion httpVersion;

		if (HTTP_1_0.equals(config.httpVersion)) {
			httpVersion = HttpVersion.HTTP_1_0;
		} else {
			httpVersion = HttpVersion.HTTP_1_1;
		}

		httpClient.getParams().setParameter(CoreProtocolPNames.PROTOCOL_VERSION, httpVersion);
		httpClient.getParams().setParameter(CoreProtocolPNames.USER_AGENT, config.userAgent);
		httpClient.getParams().setParameter(ClientPNames.MAX_REDIRECTS, config.maxRedirects);

		// set server authentication credentials

		if (config.serverAuth) {

			List<String> authpref = new ArrayList<String>();
			authpref.add(AuthPolicy.BASIC);
			authpref.add(AuthPolicy.NTLM);
			httpClient.getParams().setParameter(AuthPNames.TARGET_AUTH_PREF, authpref);

			String user = config.serverUsername;
			String domain = "";
			if (config.serverUsername != null) {
			  int idx = config.serverUsername.indexOf("\\");
			  if (idx > 0) {
				user = config.serverUsername.substring(idx + 1);
				domain = config.serverUsername.substring(0, idx);
			  }
			}

			httpClient.getAuthSchemes().register("ntlm", new NTLMSchemeFactory());
			String localhostname = java.net.InetAddress.getLocalHost().getHostName();

			NTCredentials credentials = new NTCredentials(user, config.serverPassword, localhostname, domain);
			httpClient.getCredentialsProvider().setCredentials(new AuthScope(config.url.getHost(), config.url.getPort()), credentials);
		}

		// set proxy and credentials
		if (config.useProxy) {
			HttpHost proxy = new HttpHost(config.proxyHost, config.proxyPort);
			httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);

			if (config.proxyAuth) {
				UsernamePasswordCredentials proxyCredentials = new UsernamePasswordCredentials(config.proxyUsername, config.proxyPassword);
				httpClient.getCredentialsProvider().setCredentials(new AuthScope(config.proxyHost, config.proxyPort), proxyCredentials);
			}
		}

		// set dynaTrace tagging header (only timer name)
		if(config.tagging) {
		  httpRequest.setHeader(Constants.HEADER_DYNATRACE, "NA=" + config.timerName);
		}
		
		//modified by Robert Kühn, robert.kuehn@t-systems.com
		//set socket timeout
		if(config.maxSocketTimeout > 0) {
			httpRequest.getParams().setParameter("http.socket.timeout", config.maxSocketTimeout);
		}
		
		//modified by Robert Kühn, robert.kuehn@t-systems.com
		//set socket timeout
		if(config.maxConnectionTimeout > 0) {
			httpRequest.getParams().setParameter("http.connection.timeout", config.maxConnectionTimeout);
		}

		httpClient.setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler(0, false));
	}

	private int calculateHeaderSize(Header[] headers) {
		int headerLength = 0;
		for (Header header : headers) {
			headerLength += header.getName().getBytes().length;
			headerLength += header.getValue().getBytes().length;
		}
		return headerLength;
	}

	@Override
	public void migrate(PropertyContainer properties, int major, int minor, int micro, String qualifier) {
		//JLT-41859: change protocol value from http:// and https:// to http and https
		Property prop = properties.getProperty(CONFIG_PROTOCOL);
		if (prop != null) {
			if (prop.getValue() != null && prop.getValue().contains("https")) {
				prop.setValue("https");
			} else {
				prop.setValue("http");
			}
		}
	}

	private static class Config {
	    URL url;
		String method;
		String postData;
		String httpVersion;
		String userAgent;
		int maxRedirects;
		int maxSocketTimeout;
		int maxConnectionTimeout;
		boolean tagging;
		boolean ignorecert;
		String timerName;
		// verification
		MatchContent matchContent;
		String searchString;
//		int minOccurrences;
//		int maxOccurrences;
		// server authentification
		boolean serverAuth;
		String serverUsername;
		String serverPassword;
		// proxy
		boolean useProxy;
		String proxyHost;
		int proxyPort;
		boolean proxyAuth;
		String proxyUsername;
		String proxyPassword;
		long compareBytes;
	}

	private static class DumbTrustStrategy implements TrustStrategy {

		public boolean isTrusted(
				final X509Certificate[] chain, final String authType) throws CertificateException {
			return true;
		}

	}

	private static class NTLMSchemeFactory implements AuthSchemeFactory {

		public AuthScheme newInstance(final HttpParams params) {
			return new NTLMScheme(new JCIFSEngine());
		}

	}
}
