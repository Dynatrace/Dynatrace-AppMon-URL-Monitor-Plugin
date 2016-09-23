/***************************************************
 * dynaTrace Diagnostics (c) dynaTrace software GmbH
 *
 * @file: MeasureBuilder.java
 * @date: 07.05.2015
 * @author: cwat-alechner
 */
package com.dynatrace.diagnostics.plugin.urlmonitor;

import java.util.Collection;

import com.dynatrace.diagnostics.pdk.MonitorEnvironment;
import com.dynatrace.diagnostics.pdk.MonitorMeasure;


/**
 * Class collects the result of one UrlMonitor execution and apply the measures to the MonitorEnvironment.
 *
 * @author cwat-alechner
 */
public class MeasureCollector {

	static final String METRIC_GROUP = "URL Monitor";
	static final String MSR_HOST_REACHABLE = "HostReachable";
	static final String MSR_HEADER_SIZE = "HeaderSize";
	static final String MSR_FIRST_RESPONSE_DELAY = "FirstResponseDelay";
	static final String MSR_RESPONSE_COMPLETE_TIME = "ResponseCompleteTime";
	static final String MSR_RESPONSE_SIZE = "ResponseSize";
	static final String MSR_THROUGHPUT = "Throughput";
	static final String MSR_HTTP_STATUS_CODE = "HttpStatusCode";
	static final String MSR_CONN_CLOSE_DELAY = "ConnectionCloseDelay";
	static final String MSR_CONTENT_VERIFIED = "ContentVerified";
	static final String MSR_SOCKET_TIMEOUT = "SocketTimedOut";
	static final String MSR_CONNECT_TIMEOUT = "ConnectionTimedOut";

	private static final double MILLIS = 0.000001;
	private static final double SECS = 0.000000001;

	private int httpStatusCode = 0;
	private int headerSize = 0;
	private long firstResponseTime = 0;
	private long responseCompleteTime = 0;
	private long connectionCloseDelay = 0;
	private boolean verified = false;
	private long time;
	private int inputSize = 0;
	private boolean socketTimedOut = false;
	private boolean connectionTimedOut = false;

	private MonitorEnvironment monitorEnvironment;

	/**
	 * Applys Measurevalues to environemnt, which should always be set, even if the plugin crashes.
	 */
	MeasureCollector(MonitorEnvironment env) {
		this.monitorEnvironment = env;
		// setting default values to flag measures, that should alway be returned by the plugin
		applyBinaryMeasuresToEnvironment();
	}

	/**
	 * Applies only flag-like measures to the given environment. This measures will always be added to the environment.
	 * Also if the plugin fails.
	 */
	final void applyBinaryMeasuresToEnvironment() {
		Collection<MonitorMeasure> measures;
		// set host reachable
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_HOST_REACHABLE)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(httpStatusCode > 0 ? 1 : 0);
		}
		// set connection timeout
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_CONNECT_TIMEOUT)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(connectionTimedOut ? 1 : 0);
		}
		// set socket timeout
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_SOCKET_TIMEOUT)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(socketTimedOut ? 1 : 0);
		}
	}

	/**
	 * Apply all collected measures to the given environment.
	 */
	void applyMeasuresToEnvironment() {
		// calculate and set the measurements
		Collection<MonitorMeasure> measures;
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_HOST_REACHABLE)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(httpStatusCode > 0 ? 1 : 0);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_HEADER_SIZE)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(headerSize);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_FIRST_RESPONSE_DELAY)) != null) {
			double firstResponseTimeMillis = firstResponseTime * MILLIS;
			for (MonitorMeasure measure : measures)
				measure.setValue(firstResponseTimeMillis);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_RESPONSE_COMPLETE_TIME)) != null) {
			double responseCompleteTimeMillis = responseCompleteTime * MILLIS;
			for (MonitorMeasure measure : measures)
				measure.setValue(responseCompleteTimeMillis);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_RESPONSE_SIZE)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(inputSize);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_THROUGHPUT)) != null) {
			double throughput = 0;
			if (responseCompleteTime > 0) {
				double responseCompleteTimeSecs = responseCompleteTime * SECS;
				double contentSizeKibiByte = inputSize / 1024.0;
				throughput = contentSizeKibiByte / responseCompleteTimeSecs;
			}
			for (MonitorMeasure measure : measures)
				measure.setValue(throughput);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_HTTP_STATUS_CODE)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(httpStatusCode);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_CONN_CLOSE_DELAY)) != null) {
			double connectionCloseDelayMillis = connectionCloseDelay * MILLIS;
			for (MonitorMeasure measure : measures)
				measure.setValue(connectionCloseDelayMillis);
		}
		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, MSR_CONTENT_VERIFIED)) != null) {
			for (MonitorMeasure measure : measures)
				measure.setValue(verified ? 1 : 0);
		}
	}

	void setHttpStatusCode(int httpStatusCode) {
		this.httpStatusCode = httpStatusCode;
	}

	void setHeaderSize(int headerSize) {
		this.headerSize = headerSize;
	}

	void headerResponseReceived() {
		assert time > 0; // ensure to call setTime first
		this.firstResponseTime = System.nanoTime() - time;
	}

	void loadResponseContentFinished() {
		assert time > 0; // ensure to call setTime first
		this.responseCompleteTime = System.nanoTime() - time;
	}

	void setInputSize(int inputSize) {
		this.inputSize = inputSize;
	}

	void incrementInputSize(int inputSizeInc) {
		this.inputSize += inputSizeInc;
	}

	boolean isInputSizeEqualTo(long compareTo) {
		return inputSize == compareTo;
	}

	void startClosing() {
		this.connectionCloseDelay = System.nanoTime();
	}

	void closingFinished() {
		if (connectionCloseDelay != 0) // startClosing called
			this.connectionCloseDelay = System.nanoTime() - connectionCloseDelay;
	}

	void setVerified(boolean verified) {
		this.verified = verified;
	}

	boolean isVerified() {
		return verified;
	}

	void startMeasurement() {
		this.time = System.nanoTime();
	}

	int getInputSize() {
		return inputSize;
	}

	void setSocketTimedOut()
	{
		this.socketTimedOut = true;
	}

	void setConnectionTimedOut()
	{
		this.connectionTimedOut = true;
	}
}
