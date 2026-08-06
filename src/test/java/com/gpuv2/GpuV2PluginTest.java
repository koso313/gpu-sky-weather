package com.gpuv2;

import com.gpuv2.ext.SkyExtensionPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GpuV2PluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Both are loaded so they can be compared side by side: the renderer fork, and the
		// same sky rebuilt on the core GPU plugin's extension API. Only one can be on at a
		// time in practice - the fork owns the draw callbacks, the extension needs the
		// stock GPU plugin to own them instead.
		ExternalPluginManager.loadBuiltin(GpuPlugin.class, SkyExtensionPlugin.class);
		RuneLite.main(args);
	}
}
