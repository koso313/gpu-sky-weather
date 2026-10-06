package com.gpuskyweather;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GpuSkyWeatherPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Only the extension build is loaded while the port is being tested. Loading the
		// fork as well makes the two fight over the renderer slot: the fork disables
		// whatever else holds it on startup, and the core GPU plugin silently refuses to
		// start when it is taken, so the plugin the extension needs can never come up.
		//
		// Swap back to GpuPlugin.class to run the fork.
		ExternalPluginManager.loadBuiltin(GpuSkyWeatherPlugin.class);
		RuneLite.main(args);
	}
}
