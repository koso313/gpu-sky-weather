package com.gpuv2;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GpuV2PluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GpuPlugin.class);
		RuneLite.main(args);
	}
}
