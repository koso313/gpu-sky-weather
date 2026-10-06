package com.gpuskyweather;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GpuSkyWeatherPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GpuSkyWeatherPlugin.class);
		RuneLite.main(args);
	}
}
