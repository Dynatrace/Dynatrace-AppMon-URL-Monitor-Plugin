package com.dynatrace.diagnostics.plugin.urlmonitor;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;

import org.apache.http.Header;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.util.EncodingUtils;

import com.dynatrace.diagnostics.global.Constants;
import com.dynatrace.diagnostics.httpclient.api.DynaTraceHttpClient;
import com.dynatrace.diagnostics.httpclient.api.DynaTraceHttpClientBuilder;
import com.dynatrace.diagnostics.httpclient.api.DynaTraceHttpClientException;
import com.dynatrace.diagnostics.httpclient.api.enums.AuthMode;
import com.dynatrace.diagnostics.httpclient.api.enums.ContentTypeAndEncoding;
import com.dynatrace.diagnostics.httpclient.api.enums.RequestType;
import com.dynatrace.diagnostics.httpclient.api.enums.SSLCertificateMode;
import com.dynatrace.diagnostics.httpclient.api.enums.SSLHostnameVerification;
import com.dynatrace.diagnostics.httpclient.impl.CloseableDynaTraceHttpResponse;
import com.dynatrace.diagnostics.pdk.Monitor;
import com.dynatrace.diagnostics.pdk.MonitorEnvironment;
import com.dynatrace.diagnostics.pdk.Status;

/**
 * Monitor which requests URLs via HTTP GET/POST/HEAD and returns measures for the requests.
 * Redirects ar followed to some extent.
 * possible authentication schemes are basic and NTLM.
 * Proxies are supported.
 */
public class UrlMonitor implements Monitor {

	private static final Logger log = Logger.getLogger(UrlMonitor.class.getName());
	private static final int READ_CHUNK_SIZE = 1024;
	private static final String HTTP_1_0 = "1.0";
	private static final int HTTP_CODE_MOVED_PERMANENT = 301;
	private Config config;
	private DynaTraceHttpClient httpClient;

	@Override
	public Status setup(MonitorEnvironment env) throws Exception {
		Status status = new Status(Status.StatusCode.Success);
		try {
			config = new Config(env);
		} catch (Exception ex) {
			log.log(Level.FINE, "setup configuration failed", ex);
			status.setStatusCode(Status.StatusCode.ErrorInternal);
			status.setMessage("Setting up configuration failed: " + ex.getMessage() + "\nType: " + ex.getClass().getSimpleName() +
					"\n");
			status.setShortMessage("Setting up configuration failed: " + ex.getMessage());
			status.setException(ex);
			return status;
		}

		try {
			setupHttpClient();
		} catch (Exception ex) {
			log.log(Level.FINE, "setup http client failed", ex);
			status.setStatusCode(Status.StatusCode.ErrorInternal);
			status.setMessage("Setting up HTTP client failed: " + ex.getMessage() + "\nType: " + ex.getClass().getSimpleName() +
					"\n");
			status.setShortMessage("Setting up HTTP client failed: " + ex.getMessage());
			status.setException(ex);
			return status;
		}

		try {
			setupAuth(httpClient);
		} catch (Exception ex) {
			log.log(Level.FINE, "setup http authentication failed", ex);
			status.setStatusCode(Status.StatusCode.ErrorInternal);
			status.setMessage("Setting up HTTP authentication failed: " + ex.getMessage() + "\nType: " +
					ex.getClass().getSimpleName() + "\n");
			status.setShortMessage("HTTP authentication setup failed: " + ex.getMessage());
			status.setException(ex);
			return status;
		}

		try {
			setupProxy(httpClient);
		} catch (Exception ex) {
			log.log(Level.FINE, "setup proxy failed", ex);
			status.setStatusCode(Status.StatusCode.ErrorInternal);
			status.setMessage("Settingproxy failed: " + ex.getMessage() + "\n");
			status.setShortMessage("Proxy setup failed: " + ex.getMessage());
			status.setException(ex);
			return status;
		}

		return status;
	}

	@Override
	public void teardown(MonitorEnvironment env) throws Exception {
		if (httpClient == null)
			return;
		httpClient.close();
		httpClient = null;
	}

	@Override
	public Status execute(MonitorEnvironment env) throws Exception {
		// propagate log level for execution, so no collector restart is needed
		Logger.getLogger("org.apache.http.wire").setLevel(log.getLevel());

		//moved from setupHttpClient, so this is executed every time.
		
		// set dynaTrace tagging header (only timer name)
				if (config.tagging) {
					httpClient.addRequestHeader(Constants.HEADER_DYNATRACE, "NA=" + config.timerName);
				}
				
				//set custom header attributes
				//added by Robert Kühn, T-Systems Multimedia Solutions GmbH, robert.kuehn@t-systems.com
				if (config.useCustomHeader) {
					Iterator keySetIterator = config.customHeaderMap.keySet().iterator();

					while(keySetIterator.hasNext()){
					  String key = (String) keySetIterator.next();
					  String value = (String) config.customHeaderMap.get(key);
					  httpClient.addRequestHeader(key, value);
//					  System.out.println("key: " + key + " value: " + config.customHeaderMap.get(key));
					}
				}
		
		final Status status = new Status();
		final MeasureCollector measureCollector = new MeasureCollector(env);

		final StringBuilder messageBuffer = new StringBuilder("URL: ");
		messageBuffer.append(config.url).append("\n");

		CloseableDynaTraceHttpResponse response = null;
		try {
			log.info("Executing method: " + config.method + ", URI: " + config.url + ", with PostData: " +
					(config.postData != null));

			// connect
			measureCollector.startMeasurement();
			if (config.method == RequestType.POST && config.postData != null) {
				response = httpClient.executeBigRequest(config.method, config.url, null, config.postData,
						ContentTypeAndEncoding.TEXT_PLAIN_UTF8);
			} else {
				response = httpClient.executeBigRequest(config.method, config.url, null);
			}
			// hack to be compatible with earlier versions:
			if (config.maxRedirects == 0 && response.getStatusCode() == HTTP_CODE_MOVED_PERMANENT)
				throw new DynaTraceHttpClientException(new ClientProtocolException(
						"301 redirect reached, but maxRedirect is set to zero."));

			measureCollector.headerResponseReceived();
			measureCollector.setHttpStatusCode(response.getStatusCode());
			measureCollector.setHeaderSize(calculateHeaderSize(response));
			log.fine("http request succeed. code=" + response.getStatusCode());

			// read response data (only if more than the header was requested)
			if (config.method != RequestType.HEAD) {
				try {
					StringBuilder resultContent = loadResultContent(response, measureCollector);
					verifyResultContent(resultContent, measureCollector, messageBuffer);
				} catch (IOException e) {
					log.log(Level.FINE, "reading content failed", e);
					status.setException(e);
					status.setStatusCode(Status.StatusCode.PartialSuccess);
					status.setShortMessage("reading content failed: " + e.getMessage());
					messageBuffer.append("Error while loading content: ").append(e.getClass().getSimpleName()).append(": ").append(
							e.getMessage()).append("\n");
				}
			} else {
				measureCollector.loadResponseContentFinished();
			}
			measureCollector.startClosing();
		} catch (DynaTraceHttpClientException e) {
			log.log(Level.FINE, "executing request failed", e);
			status.setException(e);
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			messageBuffer.append("Connection failed: ").append(e.getClass().getSimpleName()).append(": ").append(
					e.getMessage()).append("\n");
			if (e.getCause() != null) {
				messageBuffer.append("Caused by: ").append(e.getCause().getClass().getSimpleName()).append(": ").append(
						e.getCause().getMessage()).append("\n");
				status.setShortMessage(e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
				if (e.getCause() instanceof SSLException) {
					messageBuffer.append("SSL handshake failed, this may be caused by an incorrect certificate. Check 'Disable certificate validation' parameter to override this.\n");
				} else if (e.getCause() instanceof ConnectTimeoutException) {
					measureCollector.setConnectionTimedOut();
				} else if (e.getCause() instanceof SocketTimeoutException) {
					measureCollector.setSocketTimedOut();
				}
			} else {
				status.setShortMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		} finally {
			// always release the connection
			if (response != null)
				response.close();
			measureCollector.closingFinished();
		}

		measureCollector.applyBinaryMeasuresToEnvironment();
		if (status.getStatusCode() == Status.StatusCode.Success)
			measureCollector.applyMeasuresToEnvironment();

		status.setMessage(messageBuffer.toString());
		return status;
	}

	/**
	 * loads the whole content out of the http response.
	 *
	 * @return null if there was no content in the http response OR config parameter contentMatch is disabled OR contentMatch is
	 *         bytesMatch.
	 */
	private StringBuilder loadResultContent(CloseableDynaTraceHttpResponse response, MeasureCollector measureCollector) throws IOException {
		InputStream inputStream = response.getResponseBody();
		if (inputStream == null) {
			return null;
		}
		int bytesRead;
		byte[] data = new byte[READ_CHUNK_SIZE];
		String charset = response.getCharset();
		StringBuilder buf = new StringBuilder();
		while ((bytesRead = inputStream.read(data)) > 0) {
			if (config.matchContent != MatchContent.disabled && config.matchContent != MatchContent.bytesMatch) {
				buf.append(EncodingUtils.getString(data, 0, bytesRead, charset));
			}
			measureCollector.incrementInputSize(bytesRead);
		}
		inputStream.close();
		measureCollector.loadResponseContentFinished();
		if (config.matchContent != MatchContent.disabled && config.matchContent != MatchContent.bytesMatch) {
			return buf;
		} else {
			return null;
		}
	}

	/**
	 * Verify the Content of the http answer.
	 *
	 * @param buf the loaded content; maybe null, if matchContent == bytesMatch
	 * @param measureCollector verify flag will be set.
	 * @param messageBuffer will receive some diagnostic messages
	 */
	private void verifyResultContent(StringBuilder buf, MeasureCollector measureCollector, StringBuilder messageBuffer) {
		if (config.matchContent == MatchContent.disabled) {
			return; // nothing to verify
		} else if (config.matchContent == MatchContent.bytesMatch) {
			measureCollector.setVerified(measureCollector.isInputSizeEqualTo(config.compareBytes));
			if (!measureCollector.isVerified()) {
				messageBuffer.append("Expected ").append(config.compareBytes).append(" bytes, but was ").append(
						measureCollector.getInputSize()).append(" bytes");
			}
		} else if (buf == null) {
			messageBuffer.append("verifying of content failed, because we didn't got one!\n");
		}
		else {
			boolean found = buf.toString().contains(config.searchString);
			if (config.matchContent == MatchContent.successIfMatch) {
				measureCollector.setVerified(found);
				if (!measureCollector.isVerified())
					messageBuffer.append("Expected string \"").append(config.searchString).append(
							"\" didn't match.");
			} else { // error if match
				measureCollector.setVerified(!found);
				if (!measureCollector.isVerified())
					messageBuffer.append("Expected string \"").append(config.searchString).append("\" matched.");
			}
		}
	}

	private void setupAuth(DynaTraceHttpClient httpClient) throws IllegalStateException, UnknownHostException {
		if (config.serverAuth == AuthMethod.basic) {
			httpClient.setUserCredentials(config.serverUsername, config.serverPassword);
			httpClient.setPreemptiveAuthEnabled(config.serverAuthPreemptive);
			if (config.serverAuthPreemptive) {
				httpClient.setPreemptiveAuthMode(AuthMode.BASIC);
			}
		}
		else if (config.serverAuth == AuthMethod.NTLM) {
			String user = config.serverUsername;
			String domain = "";
			if (config.serverUsername != null) {
				int idx = config.serverUsername.indexOf("\\");
				if (idx > 0) {
					user = config.serverUsername.substring(idx + 1);
					domain = config.serverUsername.substring(0, idx);
				}
			}
			httpClient.setNTLMUserCredentials(config.url.getHost(), config.url.getPort(), user, config.serverPassword, domain,
					java.net.InetAddress.getLocalHost().getHostName());
		}
	}

	private void setupProxy(DynaTraceHttpClient httpClient) {
		if (!config.useProxy) {
			return;
		}
		if (config.proxyAuth) {
			httpClient.setProxy(config.proxyHost, config.proxyPort, config.proxyUsername, config.proxyPassword,
					config.proxyAuthPreemptive);
		} else {
			httpClient.setProxy(config.proxyHost, config.proxyPort);
		}
	}

	private void setupHttpClient() {
		DynaTraceHttpClientBuilder builder = new DynaTraceHttpClientBuilder();
		if (config.ignorecert) {
			builder.certificateMode(SSLCertificateMode.TRUST_ALL);
			builder.hostnameVerificationMode(SSLHostnameVerification.ALLOW_ALL);
		} else {
			builder.certificateMode(SSLCertificateMode.VERIFY_CHAIN);
			builder.hostnameVerificationMode(SSLHostnameVerification.DEFAULT);
		}

		if (HTTP_1_0.equals(config.httpVersion)) {
			builder.protocolVersion("HTTP", 1, 0);
		} else {
			builder.protocolVersion("HTTP", 1, 1);
		}

		if (config.maxRedirects > 0) {
			builder.allowRedirects(true).maxRedirects(config.maxRedirects);
		} else {
			builder.allowRedirects(false).maxRedirects(0);
		}

		builder.userAgent(config.userAgent);
		builder.socketTimeout(config.socketTimeout);
		builder.connectTimeout(config.connectionTimeout);
		builder.connectionRequestTimeout(config.connectionTimeout);
		httpClient = builder.build();

		//removed by Robert Kühn
		//this snippet will only be executed, the first time the monitor runs
//		// set dynaTrace tagging header (only timer name)
//		if (config.tagging) {
//			httpClient.addRequestHeader(Constants.HEADER_DYNATRACE, "NA=" + config.timerName);
//		}
//		
//		//set custom header attributes
//		//added by Robert Kühn, T-Systems Multimedia Solutions GmbH, robert.kuehn@t-systems.com
//		if (config.useCustomHeader) {
//			Iterator keySetIterator = config.customHeaderMap.keySet().iterator();
//
//			while(keySetIterator.hasNext()){
//			  String key = (String) keySetIterator.next();
//			  String value = (String) config.customHeaderMap.get(key);
//			  httpClient.addRequestHeader(key, value);
////			  System.out.println("key: " + key + " value: " + config.customHeaderMap.get(key));
//			}
//		}
	}

	private int calculateHeaderSize(CloseableDynaTraceHttpResponse response) {
		int headerLength = 0;
		for (Header header : response.getResponseHeaders()) {
			headerLength += header.getName().getBytes().length;
			headerLength += header.getValue().getBytes().length;
		}
		return headerLength;
	}
}
