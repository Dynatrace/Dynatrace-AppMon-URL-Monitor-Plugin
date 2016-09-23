/***************************************************
 * dynaTrace Diagnostics (c) dynaTrace software GmbH
 *
 * @file: MatchContent.java
 * @date: 24.04.2015
 * @author: cwat-alechner
 */
package com.dynatrace.diagnostics.plugin.urlmonitor;



/**
 * Enum list available options for the UrlMonitors "matchContnent" parameter.
 * 
 * @author cwat-alechner
 */
enum MatchContent {
	disabled("Disabled"), successIfMatch("Success if match"), errorIfMatch("Error if match"), bytesMatch(
			"Expected size in bytes");

	private String configPropertyValue;

	private MatchContent(String configPropertyValue)
	{
		this.configPropertyValue = configPropertyValue;
	}

	/**
	 *
	 * @return the configPropertyValue used in the PluginConfiguration.
	 */
	String getConfigPropertyValue() {
		return configPropertyValue;
	}

	/**
	 * retrieves the enum to a given property-config-value.
	 *
	 * @param configPropertyValue the configPropertyValue.
	 * @return the enum - if enum doesnt exist, disabled will be returned.
	 */
	static MatchContent getByConfigValue(String configPropertyValue)
	{
		for (MatchContent mc : MatchContent.values())
			if (mc.configPropertyValue.equals(configPropertyValue))
				return mc;
		return disabled; // default
	}
}
