package com.gpuv2.ext;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.api.GpuApi;

/**
 * Registers the sky as an extension of the core GPU plugin.
 *
 * <p>The whole plugin is this: inject the API, hand it an extension, take it back on
 * shutdown. No renderer, no draw callbacks, no conflict with anything else drawing the
 * world - which is the point of the exercise.
 */
/*
 * Required, and not obviously so. GpuApi comes from the GPU plugin's public module, and
 * RuneLite only puts that in scope for plugins that declare the dependency - without this
 * the plugin fails to instantiate with "No implementation for GpuApi was bound".
 */
@PluginDependency(GpuPlugin.class)
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
	private ClientThread clientThread;

	@Inject
	private GpuApi gpuApi;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private SkyExtensionConfig config;

	private SkyExtension extension;
	private boolean registered;

	@Provides
	SkyExtensionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SkyExtensionConfig.class);
	}

	@Override
	protected void startUp()
	{
		extension = new SkyExtension(client, config);
		registerIfGpuRunning();
	}

	@Override
	protected void shutDown()
	{
		if (registered && gpuRunning())
		{
			gpuApi.unregisterExtension(this, extension);
			log.info("gpu-v2 sky extension unregistered");
		}
		else if (registered)
		{
			// Unregistering recompiles the GPU plugin's shaders, and doing that with no GL
			// context takes the JVM down. The extension is left in place, switched off.
			extension.setEnabled(false);
			log.info("gpu-v2 sky extension left registered but switched off: GPU plugin is not running");
		}

		registered = false;
		extension = null;
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() instanceof GpuPlugin && event.isLoaded())
		{
			registerIfGpuRunning();
		}
	}

	/**
	 * Registering triggers a shader recompile in the GPU plugin, which deletes its programs
	 * first. Before the GPU plugin has a GL context - switched off, or switched on but still
	 * waiting for the canvas - that call crashes the JVM outright. So registration waits on
	 * the client thread until the GPU plugin has installed itself as the renderer, which it
	 * does only once its context exists.
	 */
	private void registerIfGpuRunning()
	{
		final SkyExtension pending = extension;
		clientThread.invokeLater(() ->
		{
			if (registered || extension != pending)
			{
				return true;
			}

			if (!gpuPluginActive())
			{
				log.info("gpu-v2 sky extension waiting: GPU plugin is not running");
				return true;
			}

			if (!gpuRunning())
			{
				return false;
			}

			gpuApi.registerExtension(this, pending);
			registered = true;
			log.info("gpu-v2 sky extension registered");
			return true;
		});
	}

	/** Whether the stock GPU plugin currently owns the renderer, and so has a GL context. */
	private boolean gpuRunning()
	{
		return client.getDrawCallbacks() instanceof GpuPlugin;
	}

	private boolean gpuPluginActive()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof GpuPlugin)
			{
				return pluginManager.isPluginActive(plugin);
			}
		}
		return false;
	}
}
