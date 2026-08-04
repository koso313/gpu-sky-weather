package com.gpuv2.config;

/**
 * Screen-space precipitation drawn over the scene.
 */
public enum WeatherMode
{
	OFF("Off"),
	RAIN("Rain"),
	SNOW("Snow");

	private final String name;

	WeatherMode(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
