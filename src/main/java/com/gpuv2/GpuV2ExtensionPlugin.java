package com.gpuv2;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.api.GpuApi;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * The plugin as an extension of the core GPU plugin: sky, weather and lighting, with the
 * renderer itself left to the core plugin.
 *
 * <p>Nothing here draws the world. It injects into the renderer's scene shader and is
 * called back around its draw, so it runs alongside whatever else is extending the same
 * renderer instead of replacing it.
 */
/*
 * Required, and not obviously so. GpuApi comes from the GPU plugin's public module, and
 * RuneLite only puts that in scope for plugins that declare the dependency - without this
 * the plugin fails to instantiate with "No implementation for GpuApi was bound".
 */
@PluginDependency(net.runelite.client.plugins.gpu.GpuPlugin.class)
@PluginDescriptor(
	name = "GPU v2 (extension)",
	description = "Day/night sky, weather, fog and dynamic lighting, as an extension of the GPU plugin",
	tags = {"gpu", "sky", "weather", "hd", "fog", "lighting"},
	enabledByDefault = false
)
@Slf4j
public class GpuV2ExtensionPlugin extends Plugin
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
	private ConfigManager configManager;

	@Inject
	private GpuV2ExtensionConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FrameStats frameStats;

	@Inject
	private GpuMonitor gpuMonitor;

	private EnhancementExtension extension;
	private PerformanceOverlay performanceOverlay;
	private boolean registered;
	private boolean listening;

	@Provides
	GpuV2ExtensionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuV2ExtensionConfig.class);
	}

	@Override
	protected void startUp()
	{
		extension = new EnhancementExtension(client, config, configManager, frameStats);
		/*
		 * Built by hand rather than injected. The overlay reads the full plugin's settings
		 * interface, and binding that interface in this plugin's module makes RuneLite show
		 * it as this plugin's settings panel - every renderer setting of the fork, none of
		 * which do anything here. Both interfaces share a group and keys, so the overlay
		 * still follows the switches in the panel that is shown.
		 */
		performanceOverlay = new PerformanceOverlay(
			configManager.getConfig(GpuPluginConfig.class), frameStats, gpuMonitor);
		overlayManager.add(performanceOverlay);
		if (config.perfOverlay() && config.perfShowGpu())
		{
			gpuMonitor.start();
		}
		registerWhenGpuReady();
	}

	@Override
	protected void shutDown()
	{
		if (listening && extension != null)
		{
			gpuApi.unregisterPBOListener(extension.exposureMeter());
			listening = false;
		}

		if (performanceOverlay != null)
		{
			overlayManager.remove(performanceOverlay);
			performanceOverlay = null;
		}
		gpuMonitor.stop();
		frameStats.reset();

		if (registered && gpuRunning())
		{
			gpuApi.unregisterExtension(this, extension);
			log.info("gpu-v2 extension unregistered");
		}
		else if (registered)
		{
			// Unregistering recompiles the GPU plugin's shaders, and doing that with no GL
			// context takes the JVM down. The extension is left in place, switched off.
			extension.setEnabled(false);
			log.info("gpu-v2 extension left registered but switched off: GPU plugin is not running");
		}

		registered = false;
		extension = null;
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() instanceof net.runelite.client.plugins.gpu.GpuPlugin && event.isLoaded())
		{
			registerWhenGpuReady();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GpuV2ExtensionConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("autoExposure".equals(event.getKey()))
		{
			syncExposureListener();
		}

		if ("perfShowGpu".equals(event.getKey()) || "perfOverlay".equals(event.getKey()))
		{
			// Started and stopped with the setting rather than left running, so nothing
			// spawns processes for a readout that is switched off.
			if (config.perfOverlay() && config.perfShowGpu())
			{
				gpuMonitor.start();
			}
			else
			{
				gpuMonitor.stop();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (registered && extension != null)
		{
			extension.onGameTick();
		}
	}

	/**
	 * Registering triggers a shader recompile in the GPU plugin, which deletes its programs
	 * first. Before the GPU plugin has a GL context - switched off, or switched on but still
	 * waiting for the canvas - that call crashes the JVM outright. So registration waits on
	 * the client thread until the GPU plugin has installed itself as the renderer, which it
	 * does only once its context exists.
	 */
	private void registerWhenGpuReady()
	{
		final EnhancementExtension pending = extension;
		clientThread.invokeLater(() ->
		{
			if (registered || extension != pending)
			{
				return true;
			}

			if (!gpuPluginActive())
			{
				log.info("gpu-v2 extension waiting: GPU plugin is not running");
				return true;
			}

			if (!gpuRunning())
			{
				return false;
			}

			gpuApi.registerExtension(this, pending);
			registered = true;
			syncExposureListener();
			log.info("gpu-v2 extension registered");
			return true;
		});
	}

	/**
	 * Listens for frames only while auto exposure is switched on. With a listener
	 * registered the GPU plugin reads every finished frame back off the card, which is not
	 * worth paying for a setting that is off.
	 */
	private void syncExposureListener()
	{
		if (extension == null)
		{
			return;
		}

		boolean wanted = registered && config.autoExposure() > 0;
		if (wanted && !listening)
		{
			extension.exposureMeter().reset();
			gpuApi.registerPBOListener(extension.exposureMeter());
			listening = true;
			log.info("gpu-v2 extension: listening for frames (auto exposure on)");
		}
		else if (!wanted && listening)
		{
			gpuApi.unregisterPBOListener(extension.exposureMeter());
			listening = false;
			log.info("gpu-v2 extension: stopped listening for frames after {} delivered",
				extension.exposureMeter().framesDelivered());
		}
	}

	/** Whether the core GPU plugin currently owns the renderer, and so has a GL context. */
	private boolean gpuRunning()
	{
		return client.getDrawCallbacks() instanceof net.runelite.client.plugins.gpu.GpuPlugin;
	}

	private boolean gpuPluginActive()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof net.runelite.client.plugins.gpu.GpuPlugin)
			{
				return pluginManager.isPluginActive(plugin);
			}
		}
		return false;
	}
}
