/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gpuv2;

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import java.awt.Color;
import java.time.LocalTime;
import com.gpuv2.config.GraphicsPreset;
import com.gpuv2.config.SkyMode;
import com.gpuv2.config.WeatherMode;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.ChatMessageType;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.Player;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import com.gpuv2.config.AntiAliasingMode;
import com.gpuv2.config.UIScalingMode;
import com.gpuv2.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;

@PluginDescriptor(
	name = "GPU v2",
	description = "GPU renderer with a lightweight suite of graphical enhancements",
	tags = {"gpu", "hd", "fog", "skybox", "lighting", "draw distance", "retro"},
	/*
	 * Required. PluginManager stores a plugin's enabled state under configName, falling
	 * back to the class's simple name - and this class is called GpuPlugin, exactly like
	 * RuneLite's built-in one. Sharing that key meant enabling GPU v2 also re-enabled the
	 * built-in GPU plugin, which then raced us for the renderer.
	 */
	configName = "gpuv2",
	loadInSafeMode = false
)
@Slf4j
public class GpuPlugin extends Plugin implements DrawCallbacks
{
	static final int MAX_DISTANCE = 184;
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	private static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private RegionManager regionManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_frag.glsl");

	// Reuses the sky pass's fullscreen-triangle vertex shader.
	static final Shader BLOOM_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "bloom_frag.glsl");

	static final Shader WEATHER_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "weather_frag.glsl");

	static final Shader GODRAY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "godray_frag.glsl");

	static int glProgram;
	private int glUiProgram;
	private int glSkyProgram;
	private int glBloomProgram;

	private int vaoSkyHandle;

	/** Single-sampled resolve of the multisampled scene FBO, so it can be sampled. */
	private int fboResolve = -1;
	private int texResolve;
	/** Half-resolution ping-pong targets for the bright pass and separable blur. */
	private final int[] fboBloom = {-1, -1};
	private final int[] texBloom = new int[2];
	private int bloomW;
	private int bloomH;

	private int uniBloomSrc;
	private int uniBloomPass;
	private int uniBloomBlurDir;
	private int uniBloomThreshold;
	private int uniBloomIntensity;

	private int glWeatherProgram;
	private int uniWeatherType;
	private int uniWeatherTime;
	private int uniWeatherAmount;
	private int uniWeatherAspect;
	private int uniWeatherHeavy;
	private int uniWeatherLightning;
	private int uniWeatherWind;
	private int uniWeatherLight;

	private int glGodrayProgram;
	private int uniRaySrc;
	private int uniRayPass;
	private int uniRaySunUv;
	private int uniRayThreshold;
	private int uniRayDecay;
	private int uniRayDensity;
	private int uniRayIntensity;

	/** Sun position in 0..1 screen space, filled by {@link #updateSunScreenPos}. */
	private final float[] sunScreen = new float[2];
	/** Fades shafts out as the sun sets. */
	private float sunRayFade;
	/** Camera angles in radians from the last scene draw, for projecting the sun. */
	private float lastCameraPitchRad;
	private float lastCameraYawRad;

	private int uniSkyColor;
	private int uniSkyNight;
	private int uniSkyStarDensity;
	private int uniSkyHalfW;
	private int uniSkyHalfH;
	private int uniSkyCosPitch;
	private int uniSkySinPitch;
	private int uniSkyCosYaw;
	private int uniSkySinYaw;
	private int uniSkyStarTime;
	private int uniSkyCloudTime;
	private int uniSkySunDir;
	private int uniSkyMoonDir;
	private int uniSkyShowMoon;
	private int uniSkyShowSun;
	private int uniSkyCloudAmount;
	private int uniSkyCloudOpacity;
	private int uniSkyBoltStrength;
	private int uniSkyBoltSeed;
	private int uniSkyBoltDirXZ;

	/**
	 * Peak sun elevation, in radians. Kept shallow on purpose: OSRS limits upward camera
	 * pitch, so a steeper arc puts the sun and moon permanently out of view.
	 */
	private static final double MAX_SUN_ELEVATION = Math.toRadians(22);

	/**
	 * Fraction of ambient light retained at full night, so the world stays playable
	 * instead of going black.
	 */
	private static final float NIGHT_AMBIENT_FLOOR = 0.4f;

	/** Reused per-frame scratch for {@link #computeSunDirection}. */
	private final float[] sunDir = new float[3];

	/**
	 * Reference point for {@link #skySeconds}. Cloud drift is measured as elapsed time
	 * from here rather than read off the clock each frame, so it advances smoothly and
	 * never wraps mid-session.
	 */
	private long skyClockStartNanos = System.nanoTime();
	private float skyClockStartSeconds;

	/** How far either side of the view a bolt can land, in radians (~55 degrees). */
	private static final float BOLT_SPREAD = 0.95f;

	/** What an overcast sky darkens to at full night. */
	private static final int NIGHT_OVERCAST = 0x141922;

	/** Ceiling on how much cloud weather may force, so the sky is never fully sealed. */
	private static final float MAX_WEATHER_CLOUD = 0.70f;

	/** Strike the current bearing was chosen for, so it is picked once and then held. */
	private int lastLightningSlot = -1;
	private float lightningBearing;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene;
	private boolean sceneFboValid;
	private int rboColorBuffer;
	private int rboDepthBuffer;

	private int textureArrayId;

	private final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	private int lastAnisotropicFilteringLevel = -1;

	private GpuFloatBuffer uniformBuffer;

	private int cameraYaw, cameraPitch;

	static class RenderThread
	{
		VAOList vaoO, vaoA;
		float[] tmp = new float[3];
		ModelUploader modelUploader;
	}

	private RenderThread[] rts;

	private SceneUploader clientUploader, mapUploader;

	static class SceneContext
	{
		final float[] projection = Mat4.identity();

		final int sizeX, sizeZ;
		Zone[][] zones;

		private int cameraX, cameraY, cameraZ;
		private int minLevel, level, maxLevel;
		private Set<Integer> hideRoofIds;

		SceneContext(int sizeX, int sizeZ)
		{
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			zones = new Zone[sizeX][sizeZ];
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z] = new Zone();
				}
			}
		}

		void free()
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z].free();
				}
			}
		}
	}

	SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	private SceneContext root;
	private SceneContext[] subs;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	// Uniforms
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
	private int uniExpandedMapLoadingChunks;
	private int uniSmoothBanding;
	private int uniWorldProj;
	static int uniEntityProj;
	static int uniEntityTint;
	private int uniBrightness;
	private int uniTex;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextures;
	private int uniTextureAnimations;
	private int uniBlockMain;
	private int uniTextureLightMode;
	private int uniTick;
	private int uniColorblindIntensity;
	private int uniUiColorblindIntensity;
	private int uniGradeGamma;
	private int uniGradeContrast;
	private int uniGradeSaturation;
	private int uniGradeTemperature;
	private int uniTextureDefaultColors;
	private int uniRetroNoTextures;
	private int uniRetroPosterize;
	private int uniLightStrength;
	private int uniLightAmbient;
	private int uniLightSunColor;
	private int uniLightSunDir;
	private int uniGroundSnow;
	private int uniGroundWet;
	private int uniCloudShadow;
	private int uniCloudShadowTime;
	private int uniWaterFlags;
	private int uniWaterStrength;
	private int uniWaterChoppiness;
	private int uniWaterTime;
	private int uniWaterTint;
	private int uniCameraPos;

	/**
	 * Per-texture-id water lookup, rebuilt from config rather than parsed every frame.
	 * Null means it needs rebuilding.
	 */
	private float[] waterFlags;
	static int uniBase;

	static final float[] IDENTITY = Mat4.identity();

	private static final String BUILTIN_GPU_CLASS = "net.runelite.client.plugins.gpu.GpuPlugin";

	/**
	 * One-shot so a built-in GPU plugin that refuses to release the slot can't put us in a
	 * stop/start loop.
	 */
	private boolean triedDisablingBuiltinGpu;

	/** Bounded so a renderer that keeps reclaiming the slot can't loop us forever. */
	private static final int MAX_BUILTIN_RECLAIMS = 3;
	private int builtinGpuReclaims;

	@Override
	protected void startUp()
	{
		// Anchor the cloud clock to where the real clock is now; it advances from here.
		skyClockStartNanos = System.nanoTime();
		LocalTime now = LocalTime.now();
		skyClockStartSeconds = now.getHour() * 3600f + now.getMinute() * 60f
			+ now.getSecond() + now.getNano() / 1e9f;

		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		int numThreads = config.numThreads();
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = rts[i] = new RenderThread();
			rt.modelUploader = new ModelUploader();
		}
		clientUploader = new SceneUploader(renderCallbackManager);
		mapUploader = new SceneUploader(renderCallbackManager);
		clientThread.invoke(() ->
		{
			DrawCallbacks active = client.getDrawCallbacks();
			if (active != null && active != this)
			{
				// GPU v2 is a drop-in replacement for the built-in GPU plugin, so take the
				// slot from it. Any other renderer (117HD etc.) is someone's deliberate
				// choice, so stand down instead.
				if (BUILTIN_GPU_CLASS.equals(active.getClass().getName()) && !triedDisablingBuiltinGpu)
				{
					triedDisablingBuiltinGpu = true;
					log.info("Built-in GPU plugin holds the renderer; disabling it and restarting");
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"[GPU v2] Disabled the built-in GPU plugin - only one renderer can run at a time.", null);
					replaceBuiltinGpu();
					return true;
				}

				log.warn("Standing down: another renderer is already active ({})", active.getClass().getName());
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"[GPU v2] Another GPU renderer is already active (" + active.getClass().getSimpleName()
						+ "). Disable it first - only one renderer can run at a time.", null);
				standDown();
				return true;
			}

			try
			{
				fboScene = -1;
				lastAnisotropicFilteringLevel = -1;

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCapabilities = GL.createCapabilities();

				log.info("Using device: {}", glGetString(GL_RENDERER));
				log.info("Using driver: {}", glGetString(GL_VERSION));

				if (!glCapabilities.OpenGL33)
				{
					throw new RuntimeException("OpenGL 3.3 is required but not available");
				}

				lwjglInitted = true;

				checkGLErrors();
				if (log.isDebugEnabled() && glCapabilities.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						// [LWJGL] OpenGL debug message
						//	ID: 0x20071
						//	Source: API
						//	Type: OTHER
						//	Severity: NOTIFICATION
						//	Message: Buffer detailed info: Buffer object 2 (bound to GL_PIXEL_UNPACK_BUFFER_ARB, usage hint is GL_STREAM_DRAW) has been mapped WRITE_ONLY in SYSTEM HEAP memory (fast).
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false);

						// [LWJGL] OpenGL debug message
						//	ID: 0x20052
						//	Source: API
						//	Type: PERFORMANCE
						//	Severity: MEDIUM
						//	Message: Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false);
					}
				}

				setupSyncMode();

				initBuffers();
				initVao();
				initProgram();
				initInterfaceTexture();
				if (glCapabilities.OpenGL45)
				{
					glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
				}

				client.setDrawCallbacks(this);
				setupGpuFlags();
				client.setExpandedMapLoading(config.expandedMapLoadingZones());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					startupWorldLoad();
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				disableSelf();

				shutDown();
			}
			return true;
		});
	}

	/**
	 * Stops RuneLite's built-in GPU plugin so it releases the renderer, then bounces this
	 * plugin so {@link #startUp()} runs again with the slot free.
	 *
	 * <p>Guarded by {@link #triedDisablingBuiltinGpu} so that if the built-in plugin
	 * somehow keeps the slot, we warn and stand down rather than restarting forever.
	 */
	private void replaceBuiltinGpu()
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				for (Plugin p : pluginManager.getPlugins())
				{
					if (BUILTIN_GPU_CLASS.equals(p.getClass().getName()))
					{
						pluginManager.setPluginEnabled(p, false);
						pluginManager.stopPlugin(p);
						break;
					}
				}

				// Bounce ourselves so startUp() re-runs against the now-free slot.
				// setPluginEnabled(true) is required: an earlier stand-down may have
				// persisted us as disabled, and startPlugin() on a disabled plugin is a
				// no-op - which would leave the client with no renderer at all.
				pluginManager.stopPlugin(this);
				pluginManager.setPluginEnabled(this, true);
				pluginManager.startPlugin(this);
			}
			catch (PluginInstantiationException ex)
			{
				log.error("error replacing built-in GPU plugin", ex);
			}
		});
	}

	/**
	 * Stops this plugin without persisting it as disabled.
	 *
	 * <p>Deliberately different from {@link #disableSelf()}: yielding to another renderer
	 * is a temporary condition, and writing "disabled" to config would mean the plugin no
	 * longer loads at all on the next launch - so it could never notice the conflict had
	 * gone, and any fix in startUp() would be unreachable.
	 */
	private void standDown()
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				pluginManager.stopPlugin(this);
			}
			catch (PluginInstantiationException ex)
			{
				log.error("error stopping plugin", ex);
			}
		});
	}

	/**
	 * Stops this plugin and persists it as disabled. Used only when startup genuinely
	 * failed, where retrying every launch would just reproduce the failure.
	 */
	private void disableSelf()
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				pluginManager.setPluginEnabled(this, false);
				pluginManager.stopPlugin(this);
			}
			catch (PluginInstantiationException ex)
			{
				log.error("error stopping plugin", ex);
			}
		});
	}

	private void setupGpuFlags()
	{
		int cpus = Runtime.getRuntime().availableProcessors();
		int threads = Math.min(cpus - 1, config.numThreads());
		log.debug("Using {} render threads", threads);
		client.setGpuFlags(DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| DrawCallbacks.ZBUF
			| DrawCallbacks.RENDER_THREADS(threads)
		);
	}

	private void startupWorldLoad()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			// Only tear down client renderer state if we actually own it. If we bailed out
			// because another renderer was active, clearing these would break that renderer.
			if (client.getDrawCallbacks() == this)
			{
				client.setGpuFlags(0);
				client.setDrawCallbacks(null);
				client.setUnlockedFps(false);
				client.setExpandedMapLoading(0);
			}

			if (lwjglInitted)
			{
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				root.free();

				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				shutdownFbo();
			}

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (debugCallback != null)
			{
				debugCallback.free();
				debugCallback = null;
			}

			glCapabilities = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	GpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuPluginConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(GpuPluginConfig.GROUP))
		{
			if (configChanged.getKey().equals("unlockFps")
				|| configChanged.getKey().equals("vsyncMode")
				|| configChanged.getKey().equals("fpsTarget"))
			{
				log.debug("Rebuilding sync mode");
				clientThread.invokeLater(this::setupSyncMode);
			}
			else if (configChanged.getKey().equals("expandedMapLoadingChunks"))
			{
				clientThread.invokeLater(() ->
				{
					client.setExpandedMapLoading(config.expandedMapLoadingZones());
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("preset"))
			{
				applyPreset(config.preset());
			}
			else if (configChanged.getKey().equals("waterTextureIds"))
			{
				// Rebuilt lazily on the next frame rather than parsed per frame.
				waterFlags = null;
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				setupGpuFlags();
			}
			else if (configChanged.getKey().equals("uiScalingMode") || configChanged.getKey().equals("colorBlindMode"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					shutdownProgram();
					initProgram();
				});
			}
			else if (configChanged.getKey().equals("numThreads"))
			{
				clientThread.invokeLater(() ->
				{
					for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
					{
						rts[i].vaoO.free();
						rts[i].vaoA.free();
					}

					int numThreads = config.numThreads();
					rts = new RenderThread[numThreads + 1];
					for (int i = 0; i < rts.length; ++i)
					{
						var rt = new RenderThread();
						rt.modelUploader = new ModelUploader();
						rt.vaoO = new VAOList(i > 0);
						rt.vaoA = new VAOList(i > 0);
						rts[i] = rt;
					}

					setupGpuFlags();
				});
			}
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		GpuPluginConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: GpuPluginConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + config.uiScalingMode().ordinal() + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
			}
			return null;
		});
		template.addInclude(GpuPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		glSkyProgram = SKY_PROGRAM.compile(template);
		glBloomProgram = BLOOM_PROGRAM.compile(template);
		glWeatherProgram = WEATHER_PROGRAM.compile(template);
		glGodrayProgram = GODRAY_PROGRAM.compile(template);

		glBindVertexArray(0);

		initUniforms();
	}

	private void initUniforms()
	{
		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
		uniEntityProj = glGetUniformLocation(glProgram, "entityProj");
		uniEntityTint = glGetUniformLocation(glProgram, "entityTint");
		uniSmoothBanding = glGetUniformLocation(glProgram, "smoothBanding");
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");
		uniGradeGamma = glGetUniformLocation(glProgram, "gradeGamma");
		uniGradeContrast = glGetUniformLocation(glProgram, "gradeContrast");
		uniGradeSaturation = glGetUniformLocation(glProgram, "gradeSaturation");
		uniGradeTemperature = glGetUniformLocation(glProgram, "gradeTemperature");
		uniTextureDefaultColors = glGetUniformLocation(glProgram, "textureDefaultColors");
		uniRetroNoTextures = glGetUniformLocation(glProgram, "retroNoTextures");
		uniRetroPosterize = glGetUniformLocation(glProgram, "retroPosterize");
		uniLightStrength = glGetUniformLocation(glProgram, "lightStrength");
		uniLightAmbient = glGetUniformLocation(glProgram, "lightAmbient");
		uniLightSunColor = glGetUniformLocation(glProgram, "lightSunColor");
		uniLightSunDir = glGetUniformLocation(glProgram, "lightSunDir");
		uniGroundSnow = glGetUniformLocation(glProgram, "groundSnow");
		uniGroundWet = glGetUniformLocation(glProgram, "groundWet");
		uniCloudShadow = glGetUniformLocation(glProgram, "cloudShadow");
		uniCloudShadowTime = glGetUniformLocation(glProgram, "cloudShadowTime");
		uniWaterFlags = glGetUniformLocation(glProgram, "waterFlags");
		uniWaterStrength = glGetUniformLocation(glProgram, "waterStrength");
		uniWaterChoppiness = glGetUniformLocation(glProgram, "waterChoppiness");
		uniWaterTime = glGetUniformLocation(glProgram, "waterTime");
		uniWaterTint = glGetUniformLocation(glProgram, "waterTint");
		uniCameraPos = glGetUniformLocation(glProgram, "cameraPos");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");

		uniSkyColor = glGetUniformLocation(glSkyProgram, "skyColor");
		uniSkyNight = glGetUniformLocation(glSkyProgram, "night");
		uniSkyStarDensity = glGetUniformLocation(glSkyProgram, "starDensity");
		uniSkyHalfW = glGetUniformLocation(glSkyProgram, "halfW");
		uniSkyHalfH = glGetUniformLocation(glSkyProgram, "halfH");
		uniSkyCosPitch = glGetUniformLocation(glSkyProgram, "cosPitch");
		uniSkySinPitch = glGetUniformLocation(glSkyProgram, "sinPitch");
		uniSkyCosYaw = glGetUniformLocation(glSkyProgram, "cosYaw");
		uniSkySinYaw = glGetUniformLocation(glSkyProgram, "sinYaw");
		uniSkyStarTime = glGetUniformLocation(glSkyProgram, "starTime");
		uniSkyCloudTime = glGetUniformLocation(glSkyProgram, "cloudTime");
		uniSkySunDir = glGetUniformLocation(glSkyProgram, "sunDir");
		uniSkyMoonDir = glGetUniformLocation(glSkyProgram, "moonDir");
		uniBloomSrc = glGetUniformLocation(glBloomProgram, "src");
		uniBloomPass = glGetUniformLocation(glBloomProgram, "bloomPass");
		uniBloomBlurDir = glGetUniformLocation(glBloomProgram, "blurDir");
		uniBloomThreshold = glGetUniformLocation(glBloomProgram, "threshold");
		uniBloomIntensity = glGetUniformLocation(glBloomProgram, "intensity");

		uniWeatherType = glGetUniformLocation(glWeatherProgram, "weatherType");
		uniWeatherTime = glGetUniformLocation(glWeatherProgram, "weatherTime");
		uniWeatherAmount = glGetUniformLocation(glWeatherProgram, "weatherAmount");
		uniWeatherAspect = glGetUniformLocation(glWeatherProgram, "aspect");
		uniWeatherHeavy = glGetUniformLocation(glWeatherProgram, "weatherHeavy");
		uniWeatherLightning = glGetUniformLocation(glWeatherProgram, "lightning");
		uniWeatherWind = glGetUniformLocation(glWeatherProgram, "weatherWind");
		uniWeatherLight = glGetUniformLocation(glWeatherProgram, "weatherLight");

		uniRaySrc = glGetUniformLocation(glGodrayProgram, "src");
		uniRayPass = glGetUniformLocation(glGodrayProgram, "rayPass");
		uniRaySunUv = glGetUniformLocation(glGodrayProgram, "sunUv");
		uniRayThreshold = glGetUniformLocation(glGodrayProgram, "threshold");
		uniRayDecay = glGetUniformLocation(glGodrayProgram, "decay");
		uniRayDensity = glGetUniformLocation(glGodrayProgram, "density");
		uniRayIntensity = glGetUniformLocation(glGodrayProgram, "intensity");

		uniSkyShowMoon = glGetUniformLocation(glSkyProgram, "showMoon");
		uniSkyShowSun = glGetUniformLocation(glSkyProgram, "showSun");
		uniSkyCloudAmount = glGetUniformLocation(glSkyProgram, "cloudAmount");
		uniSkyCloudOpacity = glGetUniformLocation(glSkyProgram, "cloudOpacity");
		uniSkyBoltStrength = glGetUniformLocation(glSkyProgram, "boltStrength");
		uniSkyBoltSeed = glGetUniformLocation(glSkyProgram, "boltSeed");
		uniSkyBoltDirXZ = glGetUniformLocation(glSkyProgram, "boltDirXZ");
	}

	private void shutdownProgram()
	{
		glDeleteProgram(glProgram);
		glProgram = 0;

		glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		glDeleteProgram(glSkyProgram);
		glSkyProgram = 0;

		glDeleteProgram(glBloomProgram);
		glBloomProgram = 0;

		glDeleteProgram(glWeatherProgram);
		glWeatherProgram = 0;

		glDeleteProgram(glGodrayProgram);
		glGodrayProgram = 0;
	}

	private void initVao()
	{
		// Empty VAO for the procedural sky - its vertex shader builds a fullscreen
		// triangle from gl_VertexID, so it needs no buffers or attributes, but core
		// profile still requires *some* VAO to be bound.
		vaoSkyHandle = glGenVertexArrays();

		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;

		glDeleteVertexArrays(vaoSkyHandle);
		vaoSkyHandle = 0;
	}

	private void initBuffers()
	{
		uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		initGlBuffer(glUniformBuffer);
		Zone.initBuffer();

		for (int i = 0; i < rts.length; ++i)
		{
			rts[i].vaoO = new VAOList(i > 0);
			rts[i].vaoA = new VAOList(i > 0);
		}
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(glUniformBuffer);
		uniformBuffer = null;
		Zone.freeBuffer();

		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			if (rts[i].vaoO != null)
			{
				rts[i].vaoO.free();
				rts[i].vaoO = null;
			}
			if (rts[i].vaoA != null)
			{
				rts[i].vaoA.free();
				rts[i].vaoA = null;
			}
		}
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initFbo(int width, int height, int aaSamples)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		// Create and bind the FBO
		fboScene = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		// Color render buffer
		rboColorBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboColorBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_RGBA, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColorBuffer);

		// Depth render buffer
		rboDepthBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepthBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_DEPTH_COMPONENT32F, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepthBuffer);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("FBO is incomplete. status: " + status);
		}

		initBloomFbos(width, height);

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	/**
	 * Allocates the bloom targets: a single-sampled resolve of the scene at full size
	 * (an MSAA blit cannot scale, so the downsample happens in the bright pass instead),
	 * plus two half-resolution buffers to ping-pong the separable blur through.
	 */
	private void initBloomFbos(int width, int height)
	{
		bloomW = Math.max(1, width / 2);
		bloomH = Math.max(1, height / 2);

		texResolve = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texResolve);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		setBloomTexParams();

		fboResolve = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboResolve);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texResolve, 0);

		for (int i = 0; i < 2; ++i)
		{
			texBloom[i] = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texBloom[i]);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, bloomW, bloomH, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
			setBloomTexParams();

			fboBloom[i] = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboBloom[i]);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texBloom[i], 0);
		}

		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private static void setBloomTexParams()
	{
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// Clamped so the blur doesn't wrap bright pixels around to the opposite edge.
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	private void shutdownBloomFbos()
	{
		if (fboResolve != -1)
		{
			glDeleteFramebuffers(fboResolve);
			fboResolve = -1;
		}
		if (texResolve != 0)
		{
			glDeleteTextures(texResolve);
			texResolve = 0;
		}

		for (int i = 0; i < 2; ++i)
		{
			if (fboBloom[i] != -1)
			{
				glDeleteFramebuffers(fboBloom[i]);
				fboBloom[i] = -1;
			}
			if (texBloom[i] != 0)
			{
				glDeleteTextures(texBloom[i]);
				texBloom[i] = 0;
			}
		}
	}

	private void shutdownFbo()
	{
		shutdownBloomFbos();

		if (fboScene != -1)
		{
			glDeleteFramebuffers(fboScene);
			fboScene = -1;
		}

		if (rboColorBuffer != 0)
		{
			glDeleteRenderbuffers(rboColorBuffer);
			rboColorBuffer = 0;
		}

		if (rboDepthBuffer != 0)
		{
			glDeleteRenderbuffers(rboDepthBuffer);
			rboDepthBuffer = 0;
		}
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		ctx.cameraX = (int) cameraX;
		ctx.cameraY = (int) cameraY;
		ctx.cameraZ = (int) cameraZ;
		ctx.minLevel = minLevel;
		ctx.level = level;
		ctx.maxLevel = maxLevel;
		ctx.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
			{
				rts[i].vaoO.map();
				rts[i].vaoA.map();
			}

			this.cameraYaw = client.getCameraYaw();
			this.cameraPitch = client.getCameraPitch();
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
		else
		{
			System.arraycopy(((FloatProjection) entityProjection).getProjection(), 0, ctx.projection, 0, 16);
			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}
	}

	private void preSceneDrawToplevel(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw)
	{
		scene.setDrawDistance(getDrawDistance());

		// UBO
		uniformBuffer.clear();
		uniformBuffer
			.put(cameraYaw)
			.put(cameraPitch)
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);

		checkGLErrors();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		// Setup FBO and anti-aliasing
		{
			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownFbo();

				// Bind default FBO to check whether anti-aliasing is forced
				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
				final int forcedAASamples = glGetInteger(GL_SAMPLES);
				final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
				final int samples = forcedAASamples != 0 ? forcedAASamples :
					Math.min(antiAliasingMode.getSamples(), maxSamples);

				log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, maxSamples, forcedAASamples);

				initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
				lastAntiAliasingMode = antiAliasingMode;
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		}

		// Setup anisotropic filtering
		final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

		if (textureArrayId != -1 && lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
		{
			textureManager.setAnisotropicFilteringLevel(textureArrayId, anisotropicFilteringLevel);
			lastAnisotropicFilteringLevel = anisotropicFilteringLevel;
		}

		// Setup viewport
		int renderWidthOff = client.getViewportXOffset();
		int renderHeightOff = client.getViewportYOffset();
		int renderCanvasHeight = canvasHeight;
		int renderViewportHeight = viewportHeight;
		int renderViewportWidth = viewportWidth;
		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			renderCanvasHeight = dim.height;

			double scaleFactorY = dim.getHeight() / canvasHeight;
			double scaleFactorX = dim.getWidth() / canvasWidth;

			// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
			final int padding = 1;

			// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
			renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
			renderViewportWidth = (int) Math.ceil(scaleFactorX * (renderViewportWidth)) + padding * 2;

			// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
			renderHeightOff = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
			renderWidthOff = (int) Math.floor(scaleFactorX * (renderWidthOff)) - padding;
		}

		glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final int fogDepth = config.fogDepth();
		// Feeds both the fog uniform below and drawSkybox() further down, so overriding it
		// here keeps the sky and the fog it fades into the same colour.
		final int sky = resolveSkyColor();
		glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glUniform1i(uniFogDepth, fogDepth);
		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());
		glUniform1f(uniGradeGamma, config.gradeGamma() / 100f);
		glUniform1f(uniGradeContrast, config.gradeContrast() / 100f);
		glUniform1f(uniGradeSaturation, config.gradeSaturation() / 100f);
		glUniform1f(uniGradeTemperature, config.gradeTemperature() / 100f);
		glUniform1f(uniRetroNoTextures, config.retroNoTextures() ? 1f : 0f);
		glUniform1f(uniRetroPosterize, config.retroPosterize());
		setupLightingUniforms();
		setupWaterUniforms(cameraX, cameraY, cameraZ);
		setupGroundWeatherUniforms();

		// Brightness happens to also be stored in the texture provider, so we use that
		TextureProvider textureProvider = client.getTextureProvider();
		glUniform1f(uniBrightness, (float) textureProvider.getBrightness());
		glUniform1f(uniSmoothBanding, config.smoothBanding() ? 0f : 1f);
		glUniform1f(uniTextureLightMode, config.brightTextures() ? 1f : 0f);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// avoid textures animating during loading
			glUniform1i(uniTick, client.getGameCycle() & 127);
		}

		// Calculate projection matrix
		float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
		Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
		Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		glUniformMatrix4fv(uniWorldProj, false, projectionMatrix);

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Bind uniforms
		glUniformBlockBinding(glProgram, uniBlockMain, 0);
		glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		// Kept for the god-ray pass, which runs later and needs to project the sun using
		// the same angles this frame was drawn with.
		lastCameraPitchRad = cameraPitch;
		lastCameraYawRad = cameraYaw;

		drawSkybox(scene, sky, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);

		checkGLErrors();
	}

	/**
	 * Draws the starfield and moon over the already-cleared background, before the scene.
	 * Runs with depth test and blending off - it fills every pixel and everything drawn
	 * afterwards should cover it.
	 */
	private void drawProceduralSky(int sky, float cameraPitch, float cameraYaw)
	{
		LocalTime time = skyTime();
		float night = SkyGradient.nightFactorAt(time);

		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		float scale = (float) client.getScale();
		if (viewportWidth <= 0 || viewportHeight <= 0 || scale <= 0f)
		{
			return;
		}

		glUseProgram(glSkyProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);

		glUniform3f(uniSkyColor,
			(sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f);
		glUniform1f(uniSkyNight, night);
		// Density 0 is how the shader is told to skip stars entirely.
		glUniform1f(uniSkyStarDensity, config.nightSky() ? config.starDensity() / 1000f : 0f);
		glUniform1f(uniSkyHalfW, viewportWidth / (2f * scale));
		glUniform1f(uniSkyHalfH, viewportHeight / (2f * scale));
		glUniform1f(uniSkyCosPitch, (float) Math.cos(cameraPitch));
		glUniform1f(uniSkySinPitch, (float) Math.sin(cameraPitch));
		glUniform1f(uniSkyCosYaw, (float) Math.cos(cameraYaw));
		glUniform1f(uniSkySinYaw, (float) Math.sin(cameraYaw));
		glUniform1f(uniSkyStarTime, (System.nanoTime() % 1_000_000_000_000L) / 1e9f);
		glUniform1f(uniSkyCloudTime, skySeconds());
		glUniform1f(uniSkyShowMoon, config.showMoon() ? 1f : 0f);
		glUniform1f(uniSkyShowSun, config.showSun() ? 1f : 0f);
		// Weather thickens the cloud deck as well as greying the sky.
		float clouds = config.cloudAmount() / 100f;
		WeatherMode weather = activeWeather();
		if (weather != WeatherMode.OFF)
		{
			/*
			 * Weather thickens the deck but must not seal it. Overcast runs as high as
			 * 0.95 for a blizzard, and at that cover the cloud layer covers essentially
			 * the whole sky - which takes the sun, moon and stars with it. Capped so
			 * there is always some sky left to see through.
			 */
			float forced = Math.min(weather.overcast() * weatherIntensity(), MAX_WEATHER_CLOUD);
			clouds = Math.max(clouds, forced);
		}
		glUniform1f(uniSkyCloudAmount, clouds);
		glUniform1f(uniSkyCloudOpacity, config.cloudOpacity() / 100f);

		computeSunDirection(time);
		glUniform3f(uniSkySunDir, sunDir[0], sunDir[1], sunDir[2]);
		glUniform3f(uniSkyMoonDir, -sunDir[0], -sunDir[1], -sunDir[2]);

		// Lightning bolt: same strike that drives the frame-wide flash, so they fire together.
		float seconds = weatherSeconds();
		float seed = activeWeather().hasLightning() && config.lightning()
			? lightningSeed(seconds)
			: -1f;
		if (seed < 0f)
		{
			glUniform1f(uniSkyBoltStrength, 0f);
		}
		else
		{
			glUniform1f(uniSkyBoltStrength, lightningBolt(seconds));
			glUniform1f(uniSkyBoltSeed, seed * 100f);

			/*
			 * Bearing is chosen once, when the strike begins, and held for its duration -
			 * so the bolt stays put in the world while it is on screen rather than
			 * swinging around as the camera turns.
			 *
			 * It is biased toward wherever the camera is facing. A uniformly random
			 * bearing puts most strikes behind the player, so the flash fires with no
			 * visible bolt. The camera's forward direction in world XZ is
			 * (-sin(yaw), cos(yaw)), and the bolt direction is (sin(b), cos(b)), so
			 * b = -yaw points it at the centre of the view; the spread scatters it either
			 * side so strikes don't all land dead ahead.
			 */
			int slot = (int) (seconds / lightningPeriod());
			if (slot != lastLightningSlot)
			{
				lastLightningSlot = slot;
				float spread = (seed - 0.5f) * 2f * BOLT_SPREAD;
				lightningBearing = -cameraYaw + spread;
			}

			glUniform2f(uniSkyBoltDirXZ,
				(float) Math.sin(lightningBearing), (float) Math.cos(lightningBearing));
		}

		glDrawArrays(GL_TRIANGLES, 0, 3);

		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBindVertexArray(0);
		glUseProgram(glProgram);
	}

	/**
	 * Fills {@link #sunDir} with the world-space direction toward the sun at the given
	 * time. Shared by the sky pass and the scene lighting so the two always agree on where
	 * the light is coming from.
	 *
	 * <p>The sun arcs east to west: on the horizon at 06:00, highest at noon, back down by
	 * 18:00, below the horizon overnight. MAX_SUN_ELEVATION is deliberately shallow -
	 * OSRS clamps upward camera pitch, so a steeper arc sits outside the visible frustum.
	 *
	 * <p>Written into a reused array rather than returning a new one: this runs every frame.
	 */
	private void computeSunDirection(LocalTime time)
	{
		double dayFraction = (time.getHour() * 60 + time.getMinute()) / 1440d;
		double phase = 2 * Math.PI * (dayFraction - 0.25);
		double elevation = Math.sin(phase) * MAX_SUN_ELEVATION;
		double azimuth = Math.PI / 2 + phase;

		sunDir[0] = (float) (Math.sin(azimuth) * Math.cos(elevation));
		sunDir[1] = (float) -Math.sin(elevation);
		sunDir[2] = (float) (Math.cos(azimuth) * Math.cos(elevation));
	}

	/**
	 * Weather in effect right now - either the manual selection or, with automatic
	 * weather on, whatever the cycle has picked for this moment.
	 */
	private WeatherMode activeWeather()
	{
		WeatherMode selected = config.weather();
		if (selected != WeatherMode.AUTO)
		{
			return selected;
		}
		return WeatherCycle.modeAt(clockMinutes(), config.autoWeatherPeriod());
	}

	/**
	 * Scales how strong the current weather is, 0..1. Always 1 for a manual selection -
	 * only the automatic cycle ramps conditions in and out.
	 */
	private float weatherIntensity()
	{
		WeatherMode selected = config.weather();
		if (selected != WeatherMode.AUTO)
		{
			return selected == WeatherMode.OFF ? 0f : 1f;
		}
		return WeatherCycle.intensityAt(clockMinutes(), config.autoWeatherPeriod());
	}

	/**
	 * Minutes on the wall clock. Using real time rather than a counter means the cycle
	 * keeps running across client restarts instead of resetting to calm every launch.
	 */
	private static double clockMinutes()
	{
		return System.currentTimeMillis() / 60000d;
	}

	/**
	 * Presets are placeholders for now and write nothing.
	 *
	 * <p>The previous version wrote a shared baseline before each preset, which meant
	 * selecting any of them silently reset settings the preset had no real opinion about -
	 * the sky mode among them, which is how a preset could turn a time-of-day sky grey.
	 * When these are filled in, each should write only the settings it actually means to
	 * control.
	 */
	private void applyPreset(GraphicsPreset preset)
	{
		if (preset != GraphicsPreset.CUSTOM)
		{
			log.debug("Preset {} selected; no settings defined for it yet", preset);
		}
	}

	/**
	 * Snow settling and wet ground, driven by whichever weather is running. Both are 0
	 * unless the matching precipitation is active, so clear weather leaves surfaces alone.
	 */
	private void setupGroundWeatherUniforms()
	{
		WeatherMode weather = activeWeather();
		// Ground effects follow the spell's intensity, so cover builds and thaws with it.
		float amount = config.weatherAmount() / 100f * weatherIntensity();

		boolean snowing = weather == WeatherMode.SNOW || weather == WeatherMode.BLIZZARD;
		boolean raining = weather == WeatherMode.RAIN || weather == WeatherMode.STORM;

		glUniform1f(uniGroundSnow, snowing ? config.groundSnow() / 100f * amount : 0f);
		glUniform1f(uniGroundWet, raining ? config.groundWet() / 100f * amount : 0f);

		/*
		 * Cloud shadows track the cloud deck overhead, including the extra cover weather
		 * brings, so an overcast sky darkens the ground. Only meaningful with the
		 * procedural sky running - in game-sky mode there is no deck to cast them.
		 */
		float shadow = 0f;
		if (config.cloudShadows() > 0 && config.skyMode() == SkyMode.TIME_OF_DAY)
		{
			float cover = config.cloudAmount() / 100f;
			if (weather != WeatherMode.OFF)
			{
				cover = Math.max(cover, weather.overcast() * weatherIntensity());
			}

			// Fades out after dark - there is no sunlight left for clouds to block.
			float day = 1f - SkyGradient.nightFactorAt(skyTime());
			shadow = config.cloudShadows() / 100f * cover * day;
		}

		glUniform1f(uniCloudShadow, shadow);
		// Same clock as the sky deck, so the shadows belong to the clouds casting them.
		glUniform1f(uniCloudShadowTime, skySeconds());
	}

	/**
	 * Uploads the water surface uniforms for this frame.
	 */
	private void setupWaterUniforms(float cameraX, float cameraY, float cameraZ)
	{
		// Uploaded unconditionally - puddles need it too, and they run without water on.
		glUniform3f(uniCameraPos, cameraX, cameraY, cameraZ);

		float strength = config.waterEnabled() ? config.waterStrength() / 100f : 0f;
		glUniform1f(uniWaterStrength, strength);
		if (strength < 0.001f)
		{
			// Shader early-outs; skip the rest, including the flag array upload.
			return;
		}

		if (waterFlags == null)
		{
			waterFlags = buildWaterFlags(config.waterTextureIds());
		}
		glUniform1fv(uniWaterFlags, waterFlags);

		glUniform1f(uniWaterChoppiness, config.waterChoppiness() / 100f);
		glUniform1f(uniWaterTime, (System.nanoTime() % 1_000_000_000_000L) / 1e9f);

		Color tint = config.waterTint();
		glUniform3f(uniWaterTint,
			tint.getRed() / 255f, tint.getGreen() / 255f, tint.getBlue() / 255f);
	}

	/**
	 * Turns a comma-separated texture id list into a per-id lookup the shader can index
	 * directly, so water detection is a single array read rather than a loop.
	 */
	private static float[] buildWaterFlags(String csv)
	{
		float[] flags = new float[TextureManager.TEXTURE_COUNT];
		if (csv == null)
		{
			return flags;
		}

		for (String part : csv.split(","))
		{
			String trimmed = part.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}

			try
			{
				int id = Integer.parseInt(trimmed);
				if (id >= 0 && id < TextureManager.TEXTURE_COUNT)
				{
					flags[id] = 1f;
				}
				else
				{
					log.warn("water texture id out of range: {}", id);
				}
			}
			catch (NumberFormatException ex)
			{
				log.warn("ignoring unparseable water texture id: '{}'", trimmed);
			}
		}
		return flags;
	}

	/**
	 * Uploads the ambient and directional light terms for this frame.
	 */
	private void setupLightingUniforms()
	{
		LocalTime time = skyTime();
		computeSunDirection(time);
		// Uploaded regardless of whether scene lighting is enabled - the water glint uses it.
		glUniform3f(uniLightSunDir, sunDir[0], sunDir[1], sunDir[2]);

		float strength = config.lightStrength() / 100f;
		glUniform1f(uniLightStrength, strength);
		if (strength < 0.001f)
		{
			// Shader early-outs; no point computing the rest.
			return;
		}

		// Only follow the clock when the sky is actually running on it - otherwise the
		// world would dim with no matching change in the sky.
		float night = config.lightFollowsTime() && config.skyMode() == SkyMode.TIME_OF_DAY
			? SkyGradient.nightFactorAt(time)
			: 0f;
		float day = 1f - night;

		// Ambient keeps a floor at night so the world stays playable rather than black.
		float ambMul = config.lightAmbientStrength() / 100f * (NIGHT_AMBIENT_FLOOR
			+ (1f - NIGHT_AMBIENT_FLOOR) * day);
		float sunMul = config.lightSunStrength() / 100f * day;

		Color ambient = config.lightAmbientColor();
		glUniform3f(uniLightAmbient,
			ambient.getRed() / 255f * ambMul,
			ambient.getGreen() / 255f * ambMul,
			ambient.getBlue() / 255f * ambMul);

		Color sun = config.lightSunColor();
		glUniform3f(uniLightSunColor,
			sun.getRed() / 255f * sunMul,
			sun.getGreen() / 255f * sunMul,
			sun.getBlue() / 255f * sunMul);
	}

	/**
	 * Sky colour for this frame, packed 0xRRGGBB. Drives both the fog uniform and the
	 * background clear so they always agree.
	 */
	private int resolveSkyColor()
	{
		int sky;
		switch (config.skyMode())
		{
			case CUSTOM:
				sky = config.skyColor().getRGB() & 0xFFFFFF;
				break;
			case TIME_OF_DAY:
				sky = SkyGradient.colorAt(skyTime());
				break;
			case GAME:
			default:
				sky = client.getSkyboxColor();
				break;
		}

		// Weather overcasts the sky, so rain doesn't fall out of clear blue. This also
		// reaches the fog, which shares this colour.
		WeatherMode weather = activeWeather();
		if (weather != WeatherMode.OFF)
		{
			int overcast = weather.overcastColor();

			// Overcast colours describe a daytime sky. Blending toward them at night would
			// light the sky back up, so they're darkened by how dark it currently is.
			if (config.skyMode() == SkyMode.TIME_OF_DAY)
			{
				float night = SkyGradient.nightFactorAt(skyTime());
				overcast = blendRgb(overcast, NIGHT_OVERCAST, night);
			}

			// Scaled by intensity so the sky greys over as weather arrives and clears as
			// it passes, rather than switching overcast the instant the spell begins.
			sky = blendRgb(sky, overcast, weather.overcast() * weatherIntensity());
		}

		return sky;
	}

	private static int blendRgb(int a, int b, float t)
	{
		t = Math.max(0f, Math.min(1f, t));
		int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
		int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return (r & 0xFF) << 16 | (g & 0xFF) << 8 | (bl & 0xFF);
	}

	/**
	 * Local time driving the time-of-day sky, honouring the preview-hour override so a
	 * daytime play session can still see dusk and night.
	 */
	private LocalTime skyTime()
	{
		int preview = config.previewHour();
		return preview < 0 ? LocalTime.now() : LocalTime.of(preview % 24, 0);
	}

	/**
	 * Continuous seconds-of-day for cloud drift.
	 *
	 * <p>Deliberately not the free-running timer the stars twinkle on: that wraps every
	 * ~1000 seconds, which would jump the cloud deck, and it ignores the hour entirely so
	 * scrubbing the preview hour left the clouds sitting still while the sun moved.
	 *
	 * <p>Seconds and nanos are included so it advances smoothly rather than stepping once
	 * a minute like the sky colour does. On a frozen preview hour it anchors to that hour
	 * but keeps real time flowing, so the clouds still move while being looked at.
	 */
	private float skySeconds()
	{
		float elapsed = (System.nanoTime() - skyClockStartNanos) / 1e9f;
		int preview = config.previewHour();

		// Anchor: the hour being previewed, or where the real clock was when we started.
		// Adding elapsed to it keeps the value strictly increasing, so it neither steps at
		// hour boundaries nor jumps at midnight the way seconds-of-day would.
		float base = preview < 0 ? skyClockStartSeconds : (preview % 24) * 3600f;

		/*
		 * Speed scales the clock rather than the drift rates, so it carries into the
		 * domain warp and per-octave drift too - faster clouds also reshape faster, which
		 * is what makes the higher settings read as weather moving through rather than a
		 * static pattern being dragged past more quickly.
		 *
		 * Kept to modest multipliers: the drift offset grows with this, and large offsets
		 * push the noise hash into the range where its precision fails.
		 */
		return (base + elapsed) * cloudSpeedMultiplier();
	}

	private float cloudSpeedMultiplier()
	{
		switch (config.cloudSpeed())
		{
			case 3:
				return 3.5f;
			case 2:
				return 2f;
			default:
				return 1f;
		}
	}

	private void drawSkybox(Scene scene, int sky, float cameraX, float cameraY, float cameraZ,
		float cameraPitch, float cameraYaw)
	{
		// An overridden colour also suppresses the area's skybox model - otherwise the model
		// would still paint the horizon and the fog would fade into a colour that isn't on screen.
		Model skybox = config.skyMode() == SkyMode.GAME ? scene.getSkybox() : null;
		if (skybox == null)
		{
			glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			// Runs day and night now - it draws the sun, clouds and lightning bolts too,
			// not just stars. A storm keeps the pass alive even with everything else off,
			// since the bolt is drawn here.
			if (config.skyMode() == SkyMode.TIME_OF_DAY
				&& (config.nightSky() || config.showSun() || config.cloudAmount() > 0
					|| (activeWeather().hasLightning() && config.lightning())))
			{
				drawProceduralSky(sky, cameraPitch, cameraYaw);
			}
			return;
		}

		glClearColor(0f, 0f, 0f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = rts[0];
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, 0, 0, 0, 0, o.vbo.vb);

		float[] skyboxProjection = Mat4.translate(cameraX, cameraY, cameraZ);
		o.addRange(skyboxProjection, scene, Renderable.RENDERMODE_UNSORTED_NO_DEPTH);

		rt.vaoO.draw();

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
		}
	}

	private void postDrawToplevel()
	{
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
	}

	/**
	 * Resolves the multisampled scene, extracts the bright parts, and blurs them into
	 * texBloom[0], ready for {@link #compositeBloom}.
	 */
	/**
	 * Resolves the multisampled scene into a sampleable texture. Shared by bloom and god
	 * rays so it only happens once per frame when both are on.
	 *
	 * <p>An MSAA blit cannot rescale, so this is full size; the effects downsample when
	 * they read it.
	 */
	private void resolveScene(int width, int height)
	{
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboResolve);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL_COLOR_BUFFER_BIT, GL_NEAREST);
	}

	private void renderBloom(int width, int height)
	{
		glUseProgram(glBloomProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);
		glActiveTexture(GL_TEXTURE0);
		glUniform1i(uniBloomSrc, 0);
		glViewport(0, 0, bloomW, bloomH);

		// Bright extract: resolve -> bloom[0]
		glBindFramebuffer(GL_FRAMEBUFFER, fboBloom[0]);
		glBindTexture(GL_TEXTURE_2D, texResolve);
		glUniform1i(uniBloomPass, 0);
		glUniform1f(uniBloomThreshold, config.bloomThreshold() / 100f);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glUniform1i(uniBloomPass, 1);

		// Blur horizontally: bloom[0] -> bloom[1]
		glBindFramebuffer(GL_FRAMEBUFFER, fboBloom[1]);
		glBindTexture(GL_TEXTURE_2D, texBloom[0]);
		glUniform2f(uniBloomBlurDir, 1f / bloomW, 0f);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		// Blur vertically: bloom[1] -> bloom[0]
		glBindFramebuffer(GL_FRAMEBUFFER, fboBloom[0]);
		glBindTexture(GL_TEXTURE_2D, texBloom[1]);
		glUniform2f(uniBloomBlurDir, 0f, 1f / bloomH);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glDepthMask(true);
	}

	/**
	 * Projects the sun onto the screen, storing it in {@link #sunScreen}.
	 *
	 * <p>Inverts what the sky shader does: that reconstructs a world ray from a pixel,
	 * this takes the sun's world direction forward through the same rotations and
	 * projection to find its pixel.
	 *
	 * @return false when the sun is behind the camera or too far outside the view for
	 *         shafts to make sense, in which case the pass is skipped entirely
	 */
	private boolean updateSunScreenPos()
	{
		if (config.skyMode() != SkyMode.TIME_OF_DAY)
		{
			// Without the procedural sky there is no sun on screen to radiate from.
			return false;
		}

		LocalTime time = skyTime();
		// No shafts after dark, and they ease off as the sun sets.
		sunRayFade = 1f - SkyGradient.nightFactorAt(time);
		if (sunRayFade < 0.02f)
		{
			return false;
		}

		computeSunDirection(time);

		float cp = (float) Math.cos(lastCameraPitchRad);
		float sp = (float) Math.sin(lastCameraPitchRad);
		float cy = (float) Math.cos(lastCameraYawRad);
		float sy = (float) Math.sin(lastCameraYawRad);

		// Ry then Rx, the forward direction of the inverse used in the sky shader.
		float ax = cy * sunDir[0] + sy * sunDir[2];
		float ay = sunDir[1];
		float az = -sy * sunDir[0] + cy * sunDir[2];

		float bx = ax;
		float by = cp * ay - sp * az;
		float bz = sp * ay + cp * az;

		if (bz <= 0.0001f)
		{
			// Behind the camera.
			return false;
		}

		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		float scale = (float) client.getScale();
		if (vw <= 0 || vh <= 0 || scale <= 0f)
		{
			return false;
		}

		float ndcX = scale * (2f / vw) * bx / bz;
		float ndcY = -scale * (2f / vh) * by / bz;

		sunScreen[0] = ndcX * 0.5f + 0.5f;
		sunScreen[1] = ndcY * 0.5f + 0.5f;

		// Well off-screen contributes nothing but still costs two full passes.
		return sunScreen[0] > -0.6f && sunScreen[0] < 1.6f
			&& sunScreen[1] > -0.6f && sunScreen[1] < 1.6f;
	}

	/**
	 * Extracts light near the sun and smears it radially outward, leaving the result in
	 * texBloom[0].
	 */
	private void renderGodRays()
	{
		glUseProgram(glGodrayProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);
		glActiveTexture(GL_TEXTURE0);
		glUniform1i(uniRaySrc, 0);
		glUniform2f(uniRaySunUv, sunScreen[0], sunScreen[1]);
		glViewport(0, 0, bloomW, bloomH);

		// Bright extract near the sun: resolve -> bloom[1]
		glBindFramebuffer(GL_FRAMEBUFFER, fboBloom[1]);
		glBindTexture(GL_TEXTURE_2D, texResolve);
		glUniform1i(uniRayPass, 0);
		glUniform1f(uniRayThreshold, config.godRayThreshold() / 100f);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		// Radial smear: bloom[1] -> bloom[0]
		glBindFramebuffer(GL_FRAMEBUFFER, fboBloom[0]);
		glBindTexture(GL_TEXTURE_2D, texBloom[1]);
		glUniform1i(uniRayPass, 1);
		glUniform1f(uniRayDecay, 0.92f);
		glUniform1f(uniRayDensity, config.godRayLength() / 100f);
		glUniform1f(uniRayIntensity, 1f);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glDepthMask(true);
	}

	/**
	 * Adds a half-res buffer over the already-blitted scene with additive blending.
	 * Shared by god rays; bloom has its own variant for its extra uniforms.
	 */
	private void compositeAdditive(int defaultFbo, int width, int height, int tex,
		float intensity, int program)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFbo);
		glViewport(0, 0, width, height);

		glUseProgram(program);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, tex);
		glUniform1i(uniRaySrc, 0);
		// Pass 1 with the sun at the centre and no decay degenerates to a plain read,
		// which is what compositing needs.
		glUniform1i(uniRayPass, 1);
		glUniform2f(uniRaySunUv, sunScreen[0], sunScreen[1]);
		glUniform1f(uniRayDecay, 1f);
		glUniform1f(uniRayDensity, 0f);
		glUniform1f(uniRayIntensity, intensity * sunRayFade);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glDepthMask(true);
	}

	/**
	 * Adds the blurred bloom over the already-blitted scene.
	 */
	private void compositeBloom(int defaultFbo, int width, int height)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFbo);
		glViewport(0, 0, width, height);

		glUseProgram(glBloomProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, texBloom[0]);
		glUniform1i(uniBloomSrc, 0);
		glUniform1i(uniBloomPass, 2);
		glUniform1f(uniBloomIntensity, config.bloomIntensity() / 100f);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		// Restore the blend function the UI pass expects.
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glDepthMask(true);
	}

	private static float weatherSeconds()
	{
		return (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
	}

	/**
	 * Seconds between candidate strikes, by frequency setting. Shortening the gap alone
	 * would make strikes metronomic, so the skip rate drops alongside it.
	 */
	private float lightningPeriod()
	{
		switch (config.lightningFrequency())
		{
			case 3:
				return 2.4f;
			case 2:
				return 4.2f;
			default:
				return 7f;
		}
	}

	/**
	 * Slots below this random value are skipped, keeping the cadence irregular.
	 */
	private float lightningSkip()
	{
		switch (config.lightningFrequency())
		{
			case 3:
				return 0.12f;
			case 2:
				return 0.30f;
			default:
				return 0.45f;
		}
	}

	/**
	 * Per-strike random in 0..1, or -1 when this slot has no strike. The value also seeds
	 * the bolt's shape and bearing, so one strike is consistent across the flash and bolt.
	 */
	private float lightningSeed(float seconds)
	{
		int slot = (int) (seconds / lightningPeriod());
		float r = fract(slot * 0.6180339887f);
		return r < lightningSkip() ? -1f : r;
	}

	private float lightningPhase(float seconds)
	{
		float period = lightningPeriod();
		return seconds / period - (int) (seconds / period);
	}

	/**
	 * Whole-frame flash brightness, 0..1. A fast double-flash with exponential falloff -
	 * a single even pulse reads as a screen glitch rather than lightning.
	 */
	private float lightningFlash(float seconds)
	{
		float r = lightningSeed(seconds);
		if (r < 0f)
		{
			return 0f;
		}

		float phase = lightningPhase(seconds);
		float first = (float) Math.exp(-phase * 55f);
		float second = phase > 0.045f ? (float) Math.exp(-(phase - 0.045f) * 45f) * 0.55f : 0f;

		return Math.min(1f, (first + second) * (0.6f + 0.4f * r));
	}

	/**
	 * Brightness of the drawn bolt, 0..1. Held slightly longer than the flash so the bolt
	 * is still visible as the frame-wide wash fades.
	 */
	private float lightningBolt(float seconds)
	{
		float r = lightningSeed(seconds);
		if (r < 0f)
		{
			return 0f;
		}

		float phase = lightningPhase(seconds);
		float first = (float) Math.exp(-phase * 34f);
		float second = phase > 0.045f ? (float) Math.exp(-(phase - 0.045f) * 30f) * 0.6f : 0f;

		return Math.min(1f, first + second);
	}

	private static float fract(float v)
	{
		return v - (float) Math.floor(v);
	}

	/**
	 * Draws precipitation over the composed scene with normal alpha blending.
	 */
	private void drawWeather(int defaultFbo, int width, int height)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFbo);
		glViewport(0, 0, width, height);

		glUseProgram(glWeatherProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

		WeatherMode mode = activeWeather();
		glUniform1i(uniWeatherType, mode.isRainLike() ? 1 : 2);
		glUniform1f(uniWeatherTime, weatherSeconds());
		glUniform1f(uniWeatherAmount, config.weatherAmount() / 100f * weatherIntensity());
		glUniform1f(uniWeatherHeavy, mode.heavy());
		glUniform1f(uniWeatherWind, config.weatherWind() / 100f);

		// Precipitation is lit by the sky, so it has to dim after dark - otherwise rain
		// glows white against a night scene and reads as screen damage.
		float night = config.skyMode() == SkyMode.TIME_OF_DAY
			? SkyGradient.nightFactorAt(skyTime())
			: 0f;
		glUniform1f(uniWeatherLight, 1f - night * 0.82f);
		glUniform1f(uniWeatherLightning,
			mode.hasLightning() && config.lightning() ? lightningFlash(weatherSeconds()) : 0f);
		// Keeps drops and flakes from stretching with the window's aspect ratio.
		glUniform1f(uniWeatherAspect, height > 0 ? (float) width / height : 1f);

		glDrawArrays(GL_TRIANGLES, 0, 3);

		glDisable(GL_BLEND);
		glBindVertexArray(0);
		glUseProgram(0);
		glDepthMask(true);
	}

	private void blitSceneFbo()
	{
		int width = lastStretchedCanvasWidth;
		int height = lastStretchedCanvasHeight;

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		int defaultFbo = awtContext.getFramebuffer(false);

		boolean canPost = fboResolve != -1;
		boolean bloom = canPost && config.bloomEnabled() && glBloomProgram != 0;
		boolean rays = canPost && config.godRays() > 0 && glGodrayProgram != 0
			&& updateSunScreenPos();

		if (bloom || rays)
		{
			// Must run before the scene is blitted out, while fboScene still holds it.
			resolveScene(width, height);
		}

		/*
		 * God rays go first because both effects ping-pong through the same two half-res
		 * buffers. Rays blur, then composite after the blit; only then does bloom reuse
		 * those buffers. Both read texResolve, which neither of them writes.
		 */
		if (rays)
		{
			renderGodRays();
		}

		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL_COLOR_BUFFER_BIT, GL_NEAREST);

		if (rays)
		{
			compositeAdditive(defaultFbo, width, height, texBloom[0],
				config.godRays() / 100f, glGodrayProgram);
		}

		if (bloom)
		{
			renderBloom(width, height);
			compositeBloom(defaultFbo, width, height);
		}

		// After the scene is on the default framebuffer but before the UI is composited,
		// so precipitation falls in front of the world and behind the interface.
		if (activeWeather() != WeatherMode.OFF && glWeatherProgram != 0)
		{
			drawWeather(defaultFbo, width, height);
		}

		// Reset
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

		checkGLErrors();
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);

		checkGLErrors();
	}

	private static final int ALPHA_ZSORT_CLOSE = 2048;

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// this is a noop after the first zone
		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			rts[i].vaoA.unmap();
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		int dx = ctx.cameraX - ((zx - offset) << 10);
		int dz = ctx.cameraZ - ((zz - offset) << 10);
		boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0)
		{
			z.alphaSort(zx - offset, zz - offset, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, ctx.cameraX, ctx.cameraZ, ctx.zones);
		}

		RenderThread rt = rts[0];
		z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, !close || (scene.getOverrideAmount() > 0));

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
				{
					rts[i].vaoO.draw();
				}
			}
			else
			{
				glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
			}
		}
		else if (pass == DrawCallbacks.PASS_ALPHA)
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
			}
		}
		else if (pass == DrawCallbacks.PRE_PASS_ALPHA)
		{
			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}

		checkGLErrors();
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
			o.addRange(ctx.projection, scene, 0);
		}
		else
		{
			m.calculateBoundsCylinder();

			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			if (o == null || a == null)
			{
				return;
			}

			ModelUploader sorter = rt.modelUploader;

			int start = a.vbo.vb.position();
			try
			{
				sorter.uploadSortedModel(rt, worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb, false);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
				int zx = (x >> 10) + (offset >> 3);
				int zz = (z >> 10) + (offset >> 3);
				Zone zone = ctx.zones[zx][zz];

				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0)
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			ModelUploader uploader = rt.modelUploader;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try
			{
				uploader.uploadSortedModel(rt, worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH ? renderMode : 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023);
			}
		}
		else
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			ModelUploader uploader = rt.modelUploader;
			uploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
			o.addRange(ctx.projection, scene, 0);
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate)
		{
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	private void rebuild(WorldView wv)
	{
		SceneContext ctx = context(wv);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
				{
					continue;
				}

				assert zone.initialized;
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				clientUploader.zoneSize(scene, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					o = new VBO(sz);
					o.init(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					a = new VBO(sz);
					a.init(GL_STATIC_DRAW);
					a.map();
				}

				zone.init(o, a);

				clientUploader.uploadZone(scene, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer interfaceBuf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		}
		else
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			return;
		}
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureArrayId == -1 && textureProvider != null)
		{
			// lazy init textures as they may not be loaded at plugin start.
			// this will return -1 and retry if not all textures are loaded yet, too.
			textureArrayId = textureManager.initTextureArray(textureProvider);
			if (textureArrayId > -1)
			{
				// if texture upload is successful, compute and set texture animations
				float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
				float[] texColors = textureManager.computeTextureDefaultColors(textureProvider);
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUniform3fv(uniTextureDefaultColors, texColors);
				glUseProgram(0);
			}
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		prepareInterfaceTexture(canvasWidth, canvasHeight);

		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if (sceneFboValid)
		{
			blitSceneFbo();
		}

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		try
		{
			awtContext.swapBuffers();
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}

		drawManager.processDrawComplete(this::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		glUseProgram(glUiProgram);
		glUniform1i(uniTex, 0);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);
		glUniform1f(uniUiColorblindIntensity, config.colorBlindIntensity());

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, dim.width, dim.height);
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
			final AffineTransform t = graphicsConfiguration.getDefaultTransform();
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), canvasWidth), getScaledValue(t.getScaleY(), canvasHeight));
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/hybrid isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR || uiScalingMode == UIScalingMode.HYBRID ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);

		// glReadPixels returns rows bottom-up, flip them to top-down
		int[] row = new int[width];
		for (int y0 = 0, y1 = height - 1; y0 < y1; ++y0, --y1)
		{
			System.arraycopy(pixels, y0 * width, row, 0, width);
			System.arraycopy(pixels, y1 * width, pixels, y0 * width, width);
			System.arraycopy(row, 0, pixels, y1 * width, width);
		}

		return image;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState())
		{
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
		if (state == GameState.STARTING)
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
			}
			textureArrayId = -1;
			lastAnisotropicFilteringLevel = -1;
		}
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSubScene(worldView, scene);
			return;
		}

		if (nextZones != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = nextZones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed
						if (!zone.initialized)
						{
							zone.unmap();
							zone.initialized = true;
							zone.free();
						}
					}
				}
				latch.countDown();
			});
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			nextZones = null;
			nextRoofChanges = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		regionManager.prepare(scene);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				ctx.zones[x][z].cull = true;
			}
		}

		Map<Integer, Integer> roofChanges = new HashMap<>();

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		final GameState gameState = client.getGameState();
		if (prev.isInstance() == scene.isInstance()
			&& gameState == GameState.LOGGED_IN)
		{
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();

			for (int x = 0; x < SCENE_ZONES; ++x)
			{
				next:
				for (int z = 0; z < SCENE_ZONES; ++z)
				{
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz))
					{
						if (scene.isInstance())
						{
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8)
							{
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8)
								{
									for (int level = 0; level < 4; ++level)
									{
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate)
										{
											log.error("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty)
						{
							continue;
						}

						assert old.sizeO > 0 || old.sizeA > 0;

						// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
						// Sometimes groups split or merge, so we can't copy the zone in that case
						for (int level = 0; level < 4; level++)
						{
							for (int tx = 0; tx < 8; tx++)
							{
								for (int tz = 0; tz < 8; tz++)
								{
									int prid = prids[level][(ox << 3) + tx][(oz << 3) + tz];
									int nrid = nrids[level][(x << 3) + tx][(z << 3) + tz];

									if (prid != nrid && (prid == 0 || nrid == 0))
									{
										log.trace("Roof mismatch: {} -> {}", prid, nrid);
										continue next;
									}

									Integer orid = roofChanges.putIfAbsent(prid, nrid);
									if (orid == null)
									{
										log.trace("Roof change: {} -> {}", prid, nrid);
									}
									else if (orid != nrid)
									{
										log.trace("Roof mismatch: {} -> {} vs {}", prid, nrid, orid);
										continue next;
									}
								}
							}
						}

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				if (newZones[x][z] == null)
				{
					newZones[x][z] = new Zone();
				}
			}
		}

		// size the zones which require upload
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x)
		{
			for (int z = 0; z < NUM_ZONES; ++z)
			{
				Zone zone = newZones[x][z];
				if (!zone.initialized)
				{
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					mapUploader.zoneSize(scene, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				}
				else
				{
					reused++;
				}
			}
		}
		log.debug("Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, (len * Zone.VERT_SIZE * 3) / 1024,
			lena, (lena * Zone.VERT_SIZE * 3) / 1024);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
			{
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
				{
					Zone zone = newZones[x][z];

					if (zone.initialized)
					{
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
		{
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
			{
				Zone zone = newZones[x][z];

				if (!zone.initialized)
				{
					mapUploader.uploadZone(scene, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz)
	{
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x)
		{
			if (x < 0 || x >= NUM_ZONES)
			{
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z)
			{
				if (z < 0 || z >= NUM_ZONES)
				{
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized)
				{
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		SceneContext ctx0 = subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final SceneContext ctx = new SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, zone, x, z);
			}
		}

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				mapUploader.uploadZone(scene, zone, x, z);
			}
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		SceneContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					zone.free();
				}
				else
				{
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) // NOPMD: ForLoopCanBeForeach
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		checkGLErrors();
	}

	private void swapSub(Scene scene)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}

	private int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	/**
	 * Watches for another renderer taking the draw callbacks out from under us.
	 *
	 * <p>The check in {@link #startUp()} only covers the case where something else already
	 * holds the slot. If the built-in GPU plugin starts *after* we do - a plain startup
	 * race, since both are enabled at launch - it calls setDrawCallbacks on itself and we
	 * are silently displaced: still enabled, still initialised, but never drawn. None of
	 * this plugin's effects would appear, with nothing in the log to say why.
	 */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!lwjglInitted)
		{
			return;
		}

		DrawCallbacks active = client.getDrawCallbacks();
		if (active == this || active == null)
		{
			return;
		}

		if (!BUILTIN_GPU_CLASS.equals(active.getClass().getName()))
		{
			// Another renderer entirely (117HD); that is a deliberate choice, so yield.
			log.warn("Lost the renderer to {}; standing down", active.getClass().getName());
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[GPU v2] Another renderer took over (" + active.getClass().getSimpleName()
					+ "). Stopping.", null);
			standDown();
			return;
		}

		if (++builtinGpuReclaims > MAX_BUILTIN_RECLAIMS)
		{
			log.warn("Built-in GPU keeps reclaiming the renderer; giving up");
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[GPU v2] The built-in GPU plugin keeps taking the renderer back. "
					+ "Disable it manually and re-enable GPU v2.", null);
			standDown();
			return;
		}

		log.info("Built-in GPU plugin took the renderer after startup; reclaiming");
		replaceBuiltinGpu();
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("gpumem"))
		{
			int totalSzKb = 0;
			for (int i = 0; i < rts.length; ++i)
			{
				RenderThread rt = rts[i];
				int szKb = rt.vaoO.size() + rt.vaoA.size();
				totalSzKb += szKb;
				log.info("RenderThread{}: {}kb", i, szKb);
			}
			log.info("Total: {}kb", totalSzKb);
		}
		else if (event.getCommand().equals("watertex"))
		{
			reportTileTexture();
		}
	}

	/**
	 * Reports what the surrounding tiles are actually made of, so water can be identified
	 * from real data rather than guessed. Covers both flat-painted and shaped tiles, and
	 * reports overlay/underlay ids as well as texture ids - if water turns out to be
	 * untextured, texture-id matching cannot work and detection has to move to overlays.
	 */
	private void reportTileTexture()
	{
		WorldView wv = client.getTopLevelWorldView();
		Player player = client.getLocalPlayer();
		if (wv == null || player == null)
		{
			say("[GPU v2] Not logged in.");
			return;
		}

		Scene scene = wv.getScene();
		LocalPoint lp = player.getLocalLocation();
		int plane = wv.getPlane();
		int cx = lp.getSceneX();
		int cy = lp.getSceneY();

		Tile[][][] tiles = scene.getTiles();
		short[][][] overlays = scene.getOverlayIds();
		short[][][] underlays = scene.getUnderlayIds();

		Set<Integer> textureIds = new TreeSet<>();
		Set<Integer> overlayIds = new TreeSet<>();
		Set<Integer> underlayIds = new TreeSet<>();
		int painted = 0;
		int shaped = 0;

		// Sample a patch around the player rather than a single tile - the tile you stand
		// on is often the bank, not the water.
		final int radius = 5;
		for (int x = Math.max(0, cx - radius); x <= Math.min(tiles[plane].length - 1, cx + radius); ++x)
		{
			for (int y = Math.max(0, cy - radius); y <= Math.min(tiles[plane][x].length - 1, cy + radius); ++y)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}

				SceneTilePaint paint = tile.getSceneTilePaint();
				if (paint != null)
				{
					++painted;
					textureIds.add(paint.getTexture());
				}

				SceneTileModel model = tile.getSceneTileModel();
				if (model != null)
				{
					++shaped;
					int[] tri = model.getTriangleTextureId();
					if (tri != null)
					{
						for (int t : tri)
						{
							textureIds.add(t);
						}
					}
				}

				overlayIds.add((int) overlays[plane][x][y]);
				underlayIds.add((int) underlays[plane][x][y]);
			}
		}

		say("[GPU v2] Within " + radius + " tiles: " + painted + " painted, " + shaped + " shaped");
		say("[GPU v2] texture ids: " + textureIds);
		say("[GPU v2] overlay ids: " + overlayIds);
		say("[GPU v2] underlay ids: " + underlayIds);
		say("[GPU v2] (texture id -1 means untextured - those tiles cannot be matched by texture)");
	}

	private void say(String msg)
	{
		log.info(msg);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
	}
}
