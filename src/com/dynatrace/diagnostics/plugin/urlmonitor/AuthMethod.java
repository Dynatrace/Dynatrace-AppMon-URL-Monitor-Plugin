/***************************************************
 * dynaTrace Diagnostics (c) dynaTrace software GmbH
 *
 * @file: AuthMethodEnum.java
 * @date: 24.04.2015
 * @author: cwat-alechner
 */
package com.dynatrace.diagnostics.plugin.urlmonitor;



/**
 * Enum list all valid values for config property serverAuthentication.
 *
 * @author cwat-alechner
 */
public enum AuthMethod {

	disabled("Disabled"), basic("Basic"), NTLM("NTLM");

	private String configPropertyValue;

	private AuthMethod(String configPropertyValue)
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
	public static AuthMethod getByConfigValue(String configPropertyValue)
	{
		if (configPropertyValue == null)
			return disabled;
		for (AuthMethod mc : AuthMethod.values())
			if (mc.configPropertyValue.equals(configPropertyValue))
				return mc;
		return disabled; // default
	}
}
