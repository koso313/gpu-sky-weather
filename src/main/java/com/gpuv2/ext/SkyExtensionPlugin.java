package com.gpuv2.ext;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.gpu.api.GpuApi;

/**
 * Registers the sky as an extension of the core GPU plugin.
 *
 * <p>The whole plugin is this: inject the API, hand it an extension, take it back on
 * shutdown. No renderer, no draw callbacks, no conflict with anything else drawing the
 * world - which is the point of the exercise.
 */
/*
 * Required, and not obviously so. GpuApi is bound in the GPU plugin's own
 * configure(Binder), and RuneLite gives each plugin a child injector built from its
 * declared dependencies - so without this the binding is simply not in scope and the
 * plugin fails to instantiate with "No implementation for GpuApi was bound".
 */
@PluginDependency(net.runelite.client.plugins.gpu.GpuPlugin.class)
@PluginDescriptor(
	name = "GPU v2 Sky",
	description = "Day/night sky, sun, moon, stars and aurora, drawn as a GPU plugin extension",
	tags = {"gpu", "sky", "weather", "hd"},
	enabledByDefault = false
)
@Slf4j
public class SkyExtensionPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private GpuApi gpuApi;

	@Inject
	private SkyExtensionConfig config;

	private SkyExtension extension;

	@Provides
	SkyExtensionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SkyExtensionConfig.class);
	}

	@Override
	protected void startUp()
	{
		extension = new SkyExtension(client, config);
		gpuApi.registerExtension(this, extension);
		log.info("gpu-v2 sky extension registered");
	}

	@Override
	protected void shutDown()
	{
		if (extension != null)
		{
			gpuApi.unregisterExtension(this, extension);
			extension = null;
			log.info("gpu-v2 sky extension unregistered");
		}
	}
}
