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
import java.awt.Window;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import net.runelite.api.DynamicObject;
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
import java.util.TreeMap;
import java.util.TreeSet;
import net.runelite.api.Player;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.GameObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.client.ui.overlay.OverlayManager;
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
	tags = {"gpu", "hd", "fog", "skybox", "lighting", "draw distance", "weather"},
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

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PerformanceOverlay performanceOverlay;

	@Inject
	private FrameStats frameStats;

	@Inject
	private GpuMonitor gpuMonitor;

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

	// Stretches the half-resolution sky back to full size.
	static final Shader UPSCALE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "upscale_frag.glsl");

	static final Shader GODRAY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "godray_frag.glsl");

	static final Shader POST_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "post_frag.glsl");

	static int glProgram;
	private int glUiProgram;
	private int glSkyProgram;
	private int glBloomProgram;

	private int vaoSkyHandle;

	/** Single-sampled resolve of the multisampled scene FBO, so it can be sampled. */
	private int fboResolve = -1;
	private int texResolve;

	/**
	 * Pixel size of the scene framebuffer, which is the window size times the render scale.
	 *
	 * <p>Recorded rather than recomputed, because everything reading the scene has to agree
	 * with what was actually allocated - the post chain reads it, the final composite scales
	 * out of it, and a mismatch between any two of those is a stretched or cropped frame.
	 */
	private int sceneFboWidth;
	private int sceneFboHeight;
	private int lastRenderScale = -1;

	/** Half-size target the sky is drawn into when the low-resolution sky is on. */
	private int fboSky = -1;
	private int texSky;
	private int skyW;
	private int skyH;
	private int glUpscaleProgram;
	private int uniUpscaleSrc;
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
	private int uniWeatherSkyColor;

	/**
	 * Sky colour from the last scene draw. The weather pass runs later, in its own
	 * program, so it cannot read the scene shader's fog uniform.
	 */
	private int lastSkyColor;

	private int glPostProgram;
	private int uniPostSrc;
	private int uniPostTexel;
	private int uniPostFxaa;
	private int uniPostSharpen;
	private int uniPostVignette;

	private int glGodrayProgram;
	private int uniRaySrc;
	private int uniRayPass;
	private int uniRaySunUv;
	private int uniRayThreshold;
	private int uniRayDecay;
	private int uniRayDensity;
	private int uniRayIntensity;
	private int uniRayCount;

	/** Sun position in 0..1 screen space, filled by {@link #updateSunScreenPos}. */
	private final float[] sunScreen = new float[2];
	/** Fades shafts out as the sun sets. */
	private float sunRayFade;
	/** Camera angles in radians from the last scene draw, for projecting the sun. */
	private float lastCameraPitchRad;
	private float lastCameraYawRad;
	/** Camera height from the last scene draw, for anchoring the mist level. */
	private float lastCameraY;

	/**
	 * How far above the camera the mist tops out. World Y is negative-up, so subtracting
	 * raises it - putting the top above eye level, so standing in mist means being inside
	 * it rather than looking down on a sheet.
	 */
	private static final float HEIGHT_FOG_EYE_OFFSET = 180f;

	private int uniSkyColor;
	private int uniSkyZenithColor;
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
	private int uniSkySunGlow;
	private int uniSkySunGlare;
	private int uniSkyMoonGlow;
	private int uniSkyMoonPhase;

	/** World y at which OSRS places underground areas. */
	private static final int UNDERGROUND_Y = 6400;
	/** Eased underground factor, so entering a cave fades rather than snaps. */
	private float undergroundBlend;

	/** Eased 0..1 blackout of the sky underground, advanced once per frame. */
	private float skyBlackout;

	/** Synodic month in days - one new moon to the next. */
	private static final double LUNAR_CYCLE_DAYS = 29.530588;
	/** A known new moon, as epoch days, to count cycles from. */
	private static final double KNOWN_NEW_MOON_EPOCH_DAYS = 18219.0;
	private int uniSkyCloudAmount;
	private int uniSkyCloudOpacity;
	private int uniSkyCloudSeal;
	private int uniSkyDim;
	private int uniSkySunOcclusion;
	private int uniSkyToneMap;
	private int uniToneMap;
	private int uniSkyMeteorSamples;
	private int uniSkyCloudOctaves;
	private int uniSkyMeteorActive;
	private int uniSkyMeteorTravel;
	private int uniSkyMeteorPath;

	/** Seconds between candidate meteors; whether one flies is decided per slot. */
	private static final float METEOR_SLOT = 3f;

	/** How far either side of the view a meteor can start, in radians (~40 degrees). */
	private static final float METEOR_SPREAD = 0.70f;

	private int lastMeteorSlot = -1;
	private boolean meteorActive;
	private float meteorTravel;
	/** Start bearing, start height, bearing arc, height drop. */
	private final float[] meteorPath = new float[4];
	private int uniSkyAuroraStrength;
	private int uniSkyAuroraTime;
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
	/** Texture array the current filter settings were applied to. */
	private int lastFilterArrayId = -1;

	/** Last FPS target pushed to the client, to avoid setting it every tick. */
	private int lastAppliedFpsTarget = -1;

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
	private int uniLightStrength;
	private int uniLightAmbient;
	private int uniLightSunColor;
	private int uniLightSunDir;
	private int uniGroundSnow;
	private int uniGroundWet;
	private int uniCloudShadow;
	private int uniCloudShadowTime;
	private int uniAerial;
	private int uniUnderground;
	private int uniLightCount;
	private int uniLightPos;
	private int uniLightColor;
	private int uniLightRadius;
	private int uniLightFlicker;

	private LightScanner lightScanner;
	/** Object ids treated as lights, rebuilt from config rather than parsed per frame. */
	private final float[] lightColours = new float[LightScanner.MAX_LIGHTS * 3];
	private final float[] lightRadii = new float[LightScanner.MAX_LIGHTS];

	private int uniHeightFog;
	private int uniHeightFogTop;
	private int uniHeightFogDepth;
	private int uniCameraPos;
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

		overlayManager.add(performanceOverlay);
		if (config.perfOverlay() && config.perfShowGpu())
		{
			gpuMonitor.start();
		}

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
				lastAnisotropicFilteringLevel = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					startupWorldLoad();
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				/*
				 * Stops without writing "disabled" to config. A startup failure is
				 * usually a shader that needs fixing, and persisting the disable means
				 * the plugin no longer loads at all - so the next build cannot prove
				 * itself and has to be re-enabled by hand every time. It only attempts
				 * startup once per launch, so there is no crash loop to guard against.
				 */
				standDown();

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
		overlayManager.remove(performanceOverlay);
		gpuMonitor.stop();
		frameStats.reset();

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
			// No "preset" case: it is read every frame rather than applied, which is what
			// makes it a flip switch instead of an edit to the user's settings.
			else if (configChanged.getKey().equals("perfShowGpu")
				|| configChanged.getKey().equals("perfOverlay"))
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
		glUpscaleProgram = UPSCALE_PROGRAM.compile(template);
		uniUpscaleSrc = glGetUniformLocation(glUpscaleProgram, "src");
		glPostProgram = POST_PROGRAM.compile(template);

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
		uniToneMap = glGetUniformLocation(glProgram, "toneMap");
		uniLightStrength = glGetUniformLocation(glProgram, "lightStrength");
		uniLightAmbient = glGetUniformLocation(glProgram, "lightAmbient");
		uniLightSunColor = glGetUniformLocation(glProgram, "lightSunColor");
		uniLightSunDir = glGetUniformLocation(glProgram, "lightSunDir");
		uniGroundSnow = glGetUniformLocation(glProgram, "groundSnow");
		uniGroundWet = glGetUniformLocation(glProgram, "groundWet");
		uniCloudShadow = glGetUniformLocation(glProgram, "cloudShadow");
		uniCloudShadowTime = glGetUniformLocation(glProgram, "cloudShadowTime");
		uniAerial = glGetUniformLocation(glProgram, "aerial");
		uniUnderground = glGetUniformLocation(glProgram, "underground");
		uniLightCount = glGetUniformLocation(glProgram, "lightCount");
		uniLightPos = glGetUniformLocation(glProgram, "lightPos");
		uniLightColor = glGetUniformLocation(glProgram, "lightColor");
		uniLightRadius = glGetUniformLocation(glProgram, "lightRadius");
		uniLightFlicker = glGetUniformLocation(glProgram, "lightFlicker");
		uniHeightFog = glGetUniformLocation(glProgram, "heightFog");
		uniHeightFogTop = glGetUniformLocation(glProgram, "heightFogTop");
		uniHeightFogDepth = glGetUniformLocation(glProgram, "heightFogDepth");
		uniCameraPos = glGetUniformLocation(glProgram, "cameraPos");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");

		uniSkyColor = glGetUniformLocation(glSkyProgram, "skyColor");
		uniSkyZenithColor = glGetUniformLocation(glSkyProgram, "zenithColor");
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
		uniWeatherSkyColor = glGetUniformLocation(glWeatherProgram, "weatherSkyColor");

		uniPostSrc = glGetUniformLocation(glPostProgram, "src");
		uniPostTexel = glGetUniformLocation(glPostProgram, "texel");
		uniPostFxaa = glGetUniformLocation(glPostProgram, "useFxaa");
		uniPostSharpen = glGetUniformLocation(glPostProgram, "sharpen");
		uniPostVignette = glGetUniformLocation(glPostProgram, "vignette");

		uniRaySrc = glGetUniformLocation(glGodrayProgram, "src");
		uniRayPass = glGetUniformLocation(glGodrayProgram, "rayPass");
		uniRaySunUv = glGetUniformLocation(glGodrayProgram, "sunUv");
		uniRayThreshold = glGetUniformLocation(glGodrayProgram, "threshold");
		uniRayDecay = glGetUniformLocation(glGodrayProgram, "decay");
		uniRayDensity = glGetUniformLocation(glGodrayProgram, "density");
		uniRayIntensity = glGetUniformLocation(glGodrayProgram, "intensity");
		uniRayCount = glGetUniformLocation(glGodrayProgram, "rayCount");

		uniSkyShowMoon = glGetUniformLocation(glSkyProgram, "showMoon");
		uniSkyShowSun = glGetUniformLocation(glSkyProgram, "showSun");
		uniSkySunGlow = glGetUniformLocation(glSkyProgram, "sunGlow");
		uniSkySunGlare = glGetUniformLocation(glSkyProgram, "sunGlare");
		uniSkyMoonGlow = glGetUniformLocation(glSkyProgram, "moonGlow");
		uniSkyMoonPhase = glGetUniformLocation(glSkyProgram, "moonPhase");
		uniSkyCloudAmount = glGetUniformLocation(glSkyProgram, "cloudAmount");
		uniSkyCloudOpacity = glGetUniformLocation(glSkyProgram, "cloudOpacity");
		uniSkyCloudSeal = glGetUniformLocation(glSkyProgram, "cloudSeal");
		uniSkyDim = glGetUniformLocation(glSkyProgram, "skyDim");
		uniSkySunOcclusion = glGetUniformLocation(glSkyProgram, "sunOcclusion");
		uniSkyToneMap = glGetUniformLocation(glSkyProgram, "toneMap");
		uniSkyMeteorSamples = glGetUniformLocation(glSkyProgram, "meteorSamples");
		uniSkyCloudOctaves = glGetUniformLocation(glSkyProgram, "cloudOctaves");
		uniSkyMeteorActive = glGetUniformLocation(glSkyProgram, "meteorActive");
		uniSkyMeteorTravel = glGetUniformLocation(glSkyProgram, "meteorTravel");
		uniSkyMeteorPath = glGetUniformLocation(glSkyProgram, "meteorPath");
		uniSkyAuroraStrength = glGetUniformLocation(glSkyProgram, "auroraStrength");
		uniSkyAuroraTime = glGetUniformLocation(glSkyProgram, "auroraTime");
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

		glDeleteProgram(glPostProgram);
		glPostProgram = 0;

		glDeleteProgram(glUpscaleProgram);
		glUpscaleProgram = 0;
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

		/*
		 * Render scale, applied once here so every target sized from this call - the scene,
		 * the resolve, the bloom chain - lands at the same resolution automatically.
		 */
		width = Math.max(1, width * renderScalePercent() / 100);
		height = Math.max(1, height * renderScalePercent() / 100);
		sceneFboWidth = width;
		sceneFboHeight = height;

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

		/*
		 * Half the scene in each axis, so a quarter of the pixels. Sized here with the
		 * other targets because this is what runs on a resize, and a stale size would
		 * stretch the sky across the wrong shape of screen.
		 */
		skyW = Math.max(1, width / 2);
		skyH = Math.max(1, height / 2);

		texSky = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texSky);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, skyW, skyH, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		// Linear magnification is what makes the upscale invisible on a smooth gradient.
		setBloomTexParams();

		fboSky = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboSky);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texSky, 0);

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

		if (fboSky != -1)
		{
			glDeleteFramebuffers(fboSky);
			fboSky = -1;
		}
		if (texSky != 0)
		{
			glDeleteTextures(texSky);
			texSky = 0;
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
			// Render scale joins the rebuild trigger: it changes the size of every target
			// allocated below, so changing it has to reallocate them.
			final int renderScale = renderScalePercent();

			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode
				|| lastRenderScale != renderScale)
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
				lastRenderScale = renderScale;
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		}

		/*
		 * Texture filtering. Both settings write the minification filter, so they are
		 * applied together - applying them separately would mean whichever ran last won.
		 */
		final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

		/*
		 * The array id is part of the check, not just the level. Rebuilding the texture
		 * array resets its filters to the defaults, and tracking only the level meant the
		 * cache still claimed it was applied - so anisotropic filtering silently reverted
		 * after any rebuild. Keying off the array itself makes that self-correcting rather
		 * than depending on every teardown path remembering to clear the cache.
		 */
		if (textureArrayId != -1
			&& (lastFilterArrayId != textureArrayId
				|| lastAnisotropicFilteringLevel != anisotropicFilteringLevel))
		{
			textureManager.setTextureFiltering(textureArrayId, anisotropicFilteringLevel);
			lastFilterArrayId = textureArrayId;
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

		// The world's viewport follows the render scale; the interface's does not, which is
		// why this is applied here rather than inside glDpiAwareViewport.
		glSceneViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff,
			renderViewportWidth, renderViewportHeight);

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final boolean fx = enhancements();
		final int fogDepth = fx && config.fogEnabled() ? config.fogDepth() : 0;
		// Advanced once here, then read from the field everywhere else this frame - easing
		// that stepped on every read would settle at a rate depending on how many callers
		// happened to ask.
		undergroundSkyFade();

		// Feeds both the fog uniform below and drawSkybox() further down, so overriding it
		// here keeps the sky and the fog it fades into the same colour.
		final int sky = resolveSkyColor();
		// Kept for the weather pass, which runs later in its own program.
		lastSkyColor = sky;
		glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glUniform1i(uniFogDepth, fogDepth);
		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
		// Colourblindness correction is an accessibility aid, not an atmospheric effect, so
		// it stays on in Default - turning it off would make the game harder to read, which
		// is the opposite of what a "plain" mode should do.
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());

		// 1.0 is the identity for all three, so Default grades nothing.
		glUniform1f(uniGradeGamma, fx ? config.gradeGamma() / 100f : 1f);
		glUniform1f(uniGradeContrast, fx ? config.gradeContrast() / 100f : 1f);
		glUniform1f(uniGradeSaturation, fx ? config.gradeSaturation() / 100f : 1f);
		/*
		 * Colour temperature either comes from the slider or follows the sky clock, in
		 * which case the slider becomes an offset so it can still be nudged either way.
		 * Only meaningful with the time-of-day sky - there is no clock to follow otherwise.
		 */
		float temperature = fx ? config.gradeTemperature() / 100f : 0f;
		if (fx && config.autoTemperature() && effectiveSkyMode() == SkyMode.TIME_OF_DAY)
		{
			temperature = Math.max(-1f, Math.min(1f,
				temperature + SkyGradient.temperatureAt(skyTime())));
		}
		glUniform1f(uniGradeTemperature, temperature);
		glUniform1f(uniToneMap, fx ? config.toneMapping() / 100f : 0f);

		glUniform1f(uniAerial, fx ? config.aerialPerspective() / 100f : 0f);
		glUniform1f(uniUnderground, fx ? undergroundFactor() : 0f);
		setupPointLights();
		setupLightingUniforms();
		setupCameraUniform(cameraX, cameraY, cameraZ);
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
		lastCameraY = cameraY;

		drawSkybox(scene, sky, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);

		checkGLErrors();
	}

	/**
	 * Draws the starfield and moon over the already-cleared background, before the scene.
	 * Runs with depth test and blending off - it fills every pixel and everything drawn
	 * afterwards should cover it.
	 */
	/**
	 * Draws the sky, either straight into the scene or by way of a smaller target.
	 *
	 * <p>The sky is a fullscreen pass of four-octave noise with a domain warp on top, run
	 * for every pixel of every frame - by some distance the most expensive thing here. It is
	 * also a smooth gradient, so drawing it at half size and stretching it back is very
	 * close to free visually, which is not true of anything else on screen.
	 *
	 * <p>The pass itself is resolution-independent: direction comes from the fullscreen
	 * triangle's NDC and the viewport shape arrives as uniforms, so a smaller target yields
	 * the same image with fewer samples rather than a differently framed one.
	 */
	private void drawSkyPass(int sky, float cameraPitch, float cameraYaw)
	{
		boolean lowRes = config.lowResSky();
		if (!lowRes || fboSky == -1 || glUpscaleProgram == 0)
		{
			drawProceduralSky(sky, cameraPitch, cameraYaw);
			return;
		}

		/*
		 * Both the target and the viewport are read back rather than assumed. The scene's
		 * viewport is not the whole canvas, and restoring it from the canvas size drew the
		 * world into a small patch in the middle of the screen.
		 */
		int dst = glGetInteger(GL_FRAMEBUFFER_BINDING);
		int[] viewport = new int[4];
		glGetIntegerv(GL_VIEWPORT, viewport);

		glBindFramebuffer(GL_FRAMEBUFFER, fboSky);
		glViewport(0, 0, skyW, skyH);
		drawProceduralSky(sky, cameraPitch, cameraYaw);

		glBindFramebuffer(GL_FRAMEBUFFER, dst);
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

		/*
		 * Drawn rather than blitted: the scene framebuffer is multisampled, and
		 * glBlitFramebuffer rejects a single-sampled source into a multisampled destination.
		 */
		glUseProgram(glUpscaleProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, texSky);
		glUniform1i(uniUpscaleSrc, 0);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		/*
		 * Hand the scene program back.
		 *
		 * This is the one that matters, and it is easy to miss because nothing here uses it.
		 * The client calls drawZoneOpaque after the sky, and Zone.renderOpaque sets uniforms
		 * and draws without ever binding a program - it assumes the scene's is still current
		 * from drawScene. Leaving the upscale program bound meant every zone was setting a
		 * scene uniform on a program that has no such uniform: INVALID_OPERATION, and a world
		 * that did not draw.
		 *
		 * drawProceduralSky ends with exactly these two lines for the same reason. Running
		 * after it means repeating them, along with the depth and blend state it also
		 * restores.
		 */
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE1);

		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBindVertexArray(0);
		glUseProgram(glProgram);
	}

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

		/*
		 * Overhead colour. Only the time-of-day sky has a separate zenith - a fixed or
		 * game-supplied colour is a single value, so the zenith matches the horizon there
		 * and the gradient collapses to nothing rather than inventing a hue.
		 */
		int zenith = sky;
		if (effectiveSkyMode() == SkyMode.TIME_OF_DAY)
		{
			zenith = SkyGradient.zenithColorAt(time);
			WeatherMode weatherNow = activeWeather();
			if (!weatherNow.isClear())
			{
				// Overcast flattens the sky - cloud closes the gradient down.
				zenith = blendRgb(zenith, sky, weatherNow.overcast() * weatherIntensity());
			}
		}
		glUniform3f(uniSkyZenithColor,
			(zenith >> 16 & 0xFF) / 255f, (zenith >> 8 & 0xFF) / 255f, (zenith & 0xFF) / 255f);
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
		glUniform1f(uniSkySunGlow, config.sunGlow() / 100f);
		glUniform1f(uniSkySunGlare, config.showSun() ? config.sunGlare() / 100f : 0f);
		glUniform1f(uniSkyMoonGlow, config.moonGlow() / 100f);
		glUniform1f(uniSkyMoonPhase, moonPhase());
		// Weather thickens the cloud deck as well as greying the sky.
		float clouds = config.cloudAmount() / 100f;
		WeatherMode weather = activeWeather();
		if (!weather.isClear())
		{
			/*
			 * Weather thickens the deck but must not seal it. Cover runs as high as 0.95
			 * for a blizzard, and at that thickness the cloud layer covers essentially the
			 * whole sky - which takes the sun, moon and stars with it. Capped so there is
			 * always some sky left to see through.
			 *
			 * Overcast is the exception, being the one condition whose entire purpose is to
			 * put the sky away. It skips the cap and seals separately below.
			 */
			float raw = weather.overcast() * weatherIntensity();
			float forced = weather.cloudSealing() > 0f ? raw : Math.min(raw, MAX_WEATHER_CLOUD);
			clouds = Math.max(clouds, forced);
		}
		glUniform1f(uniSkyCloudAmount, clouds);

		/*
		 * Thickening the noise is not enough on its own - it only shifts the coverage
		 * threshold, so gaps survive wherever the field falls below it and the sun comes
		 * straight through one. Overcast lifts the whole deck toward solid instead.
		 *
		 * Scaled by intensity so it closes over as the weather arrives, and short of 1.0 so
		 * the deck keeps some structure rather than becoming a flat grey ceiling.
		 */
		glUniform1f(uniSkyCloudSeal, weather.cloudSealing() * weatherIntensity());
		glUniform1f(uniSkyDim, skyBlackout);
		// Scaled by intensity so the sun goes behind the cloud as the weather rolls in
		// rather than snapping out the moment the spell begins.
		glUniform1f(uniSkySunOcclusion, weather.sunHiding() * weatherIntensity());
		// Same value as the scene, so the sky and the world it sits behind roll off together
		// rather than meeting at a visible seam on the horizon.
		glUniform1f(uniSkyToneMap, config.toneMapping() / 100f);

		// Shooting stars need a clear night sky, for the same reason the stars do.
		float clearSky = Math.max(0f, 1f - clouds * 1.2f);
		boolean meteorsVisible = config.nightSky() && clearSky > 0.35f
			&& SkyGradient.nightFactorAt(time) >= 0.35f;
		updateMeteor(meteorsVisible ? config.shootingStars() : 0);

		int quality = config.effectQuality();
		glUniform1i(uniSkyMeteorSamples, quality >= 3 ? 14 : quality == 2 ? 10 : 6);
		glUniform1i(uniSkyCloudOctaves, quality >= 3 ? 5 : quality == 2 ? 4 : 3);

		glUniform1f(uniSkyMeteorActive, meteorActive ? 1f : 0f);
		glUniform1f(uniSkyMeteorTravel, meteorTravel);
		glUniform4f(uniSkyMeteorPath, meteorPath[0], meteorPath[1], meteorPath[2], meteorPath[3]);

		// Aurora only on a clear night - cloud covers it, the same way it covers stars.
		float aurora = config.aurora() ? config.auroraStrength() / 100f : 0f;
		glUniform1f(uniSkyAuroraStrength, aurora * Math.max(0f, 1f - clouds * 1.2f));

		// Its own clock, not the sky one: the aurora shouldn't speed up or slow down
		// because the cloud speed changed, and it needs no tie to the hour.
		glUniform1f(uniSkyAuroraTime, monotonicSeconds() * auroraSpeedMultiplier());
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
	/**
	 * Whether the plugin's own effects run at all.
	 *
	 * <p>The Default preset gates them here, at read time, instead of writing anything to
	 * config - so flipping back to Custom restores the user's settings intact rather than
	 * handing them a pile of overwritten sliders to rebuild.
	 */
	private boolean enhancements()
	{
		return config.preset() == GraphicsPreset.CUSTOM;
	}

	/**
	 * The sky mode actually in force. Default reports GAME, which is vanilla behaviour: the
	 * area's own skybox model draws, and every effect keyed to TIME_OF_DAY switches itself
	 * off without needing a separate check.
	 */
	private SkyMode effectiveSkyMode()
	{
		return enhancements() ? config.skyMode() : SkyMode.GAME;
	}

	private WeatherMode activeWeather()
	{
		if (!enhancements())
		{
			return WeatherMode.OFF;
		}

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
			return selected.isClear() ? 0f : 1f;
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
		if (config.cloudShadows() > 0 && effectiveSkyMode() == SkyMode.TIME_OF_DAY)
		{
			float cover = config.cloudAmount() / 100f;
			if (!weather.isClear())
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

		/*
		 * Ground mist. Fog weather brings its own on top of the slider, so mist rolls in
		 * with the weather and lifts as it passes.
		 *
		 * The mist level follows the camera because absolute ground height varies hugely
		 * between regions - any fixed world Y would submerge some areas and miss others.
		 * The offset puts the top a little above eye level, so standing in it you are
		 * inside the mist rather than looking down on a flat sheet.
		 */
		float mist = enhancements() && config.fogEnabled() ? config.heightFog() / 100f : 0f;
		glUniform1f(uniHeightFog, mist);
		glUniform1f(uniHeightFogTop, lastCameraY - HEIGHT_FOG_EYE_OFFSET);
		glUniform1f(uniHeightFogDepth, config.heightFogDepth() * Perspective.LOCAL_TILE_SIZE);
	}

	/**
	 * How much firelight the time of day allows, 0..1.
	 *
	 * <p>Torches lighting up the ground under a midday sun is the thing that gives dynamic
	 * lighting away as an effect, so by default they follow the sky clock and fade in
	 * through dusk. Reuses the same night curve the scene lighting does, so the two arrive
	 * together instead of on separate schedules.
	 *
	 * <p>Returns 1 whenever there is no clock to follow - the game sky and a fixed custom
	 * colour have no time of day, and guessing one would leave lights off with nothing on
	 * screen to explain why.
	 */
	private float pointLightTimeFactor()
	{
		if (config.daytimeLights() || effectiveSkyMode() != SkyMode.TIME_OF_DAY)
		{
			return 1f;
		}

		return SkyGradient.nightFactorAt(skyTime());
	}

	/**
	 * Camera position, used by wet-ground puddles, height fog and aerial perspective.
	 */
	private void setupCameraUniform(float cameraX, float cameraY, float cameraZ)
	{
		glUniform3f(uniCameraPos, cameraX, cameraY, cameraZ);
	}

	/**
	 * Uploads the ambient and directional light terms for this frame.
	 */
	private void setupLightingUniforms()
	{
		LocalTime time = skyTime();
		computeSunDirection(time);
		// Uploaded regardless of whether scene lighting is enabled - god rays need it too.
		glUniform3f(uniLightSunDir, sunDir[0], sunDir[1], sunDir[2]);

		float strength = enhancements() ? config.lightStrength() / 100f : 0f;
		glUniform1f(uniLightStrength, strength);
		if (strength < 0.001f)
		{
			// Shader early-outs; no point computing the rest.
			return;
		}

		// Only follow the clock when the sky is actually running on it - otherwise the
		// world would dim with no matching change in the sky.
		float night = config.lightFollowsTime() && effectiveSkyMode() == SkyMode.TIME_OF_DAY
			? SkyGradient.nightFactorAt(time)
			: 0f;
		float day = 1f - night;

		/*
		 * Bad weather darkens the world under it, not just the sky over it.
		 *
		 * Hiding the sun on its own left the ground still lit as though the sun were out,
		 * which reads as a sunny day with rain falling through it. The sunlight term takes
		 * the larger share of the gloom, since that is the light the cloud is actually
		 * blocking, while ambient keeps most of its strength so the scene stays readable.
		 */
		float gloom = activeWeather().gloom() * weatherIntensity();

		// Ambient keeps a floor at night so the world stays playable rather than black.
		float ambMul = config.lightAmbientStrength() / 100f * (NIGHT_AMBIENT_FLOOR
			+ (1f - NIGHT_AMBIENT_FLOOR) * day) * (1f - gloom * 0.5f);
		float sunMul = config.lightSunStrength() / 100f * day * (1f - gloom);

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
		switch (effectiveSkyMode())
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
		if (!weather.isClear())
		{
			int overcast = weather.overcastColor();

			// Overcast colours describe a daytime sky. Blending toward them at night would
			// light the sky back up, so they're darkened by how dark it currently is.
			if (effectiveSkyMode() == SkyMode.TIME_OF_DAY)
			{
				float night = SkyGradient.nightFactorAt(skyTime());
				overcast = blendRgb(overcast, NIGHT_OVERCAST, night);
			}

			// Scaled by intensity so the sky greys over as weather arrives and clears as
			// it passes, rather than switching overcast the instant the spell begins.
			sky = blendRgb(sky, overcast, weather.overcast() * weatherIntensity());
		}

		/*
		 * Under a roof there is no sky, so it goes black - and because this colour is also
		 * the fog colour, the far end of a cave fades into darkness rather than into a
		 * daylight blue that has no business being down there.
		 */
		return blendRgb(sky, 0x000000, skyBlackout);
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
	 * Rescans for light sources.
	 *
	 * <p>On the game tick rather than per frame: the scan walks a patch of the scene, and
	 * objects do not move between ticks anyway.
	 */
	private void updateLights()
	{
		/*
		 * Broad daylight skips the walk entirely. The scan is the expensive half of this
		 * feature - thousands of tiles every tick - and in full sun nothing it finds will be
		 * drawn. Uses a threshold rather than exactly zero so the scan is already running
		 * before dusk brings the lights up.
		 */
		if (config.dynamicLights() <= 0 || pointLightTimeFactor() < 0.02f)
		{
			if (lightScanner != null)
			{
				lightScanner.clearScenery();
			}
			return;
		}

		if (lightScanner == null)
		{
			lightScanner = new LightScanner(client);
		}
		// 0 means follow the draw distance, so lit scenery reaches as far as the world does
		// without the user having to keep two numbers in step by hand.
		int search = config.lightSearchDistance() > 0
			? config.lightSearchDistance()
			: getDrawDistance();
		lightScanner.scan(config.lightRadius(),
			config.maxLights(), search);
	}

	/**
	 * Uploads the point lights found by the last scan.
	 *
	 * <p>Colour and radius are shared rather than per-light: without a database saying
	 * what each object is, there is nothing to vary them by, and inventing differences
	 * would look arbitrary rather than informed.
	 */
	private void setupPointLights()
	{
		// Everything follows the same clock, so full daylight skips the pass outright rather
		// than uploading a set of lights whose colour has been multiplied to nothing.
		boolean on = enhancements() && config.dynamicLights() > 0 && lightScanner != null
			&& pointLightTimeFactor() >= 0.02f;
		if (on)
		{
			lightScanner.collectFrame();
		}

		int count = on ? lightScanner.count : 0;
		glUniform1i(uniLightCount, count);
		if (count <= 0)
		{
			return;
		}

		Color tint = config.lightColour();
		// 0-10 scale, where 10 matches the brightest the old 0-200 scale reached.
		// Torches and spell effects follow the same clock - one rule, so there is never a
		// time of day where some things glow and others do not for no visible reason.
		float strength = config.dynamicLights() / 5f * pointLightTimeFactor();
		float radius = config.lightRadius() * Perspective.LOCAL_TILE_SIZE;

		for (int i = 0; i < count; ++i)
		{
			// Dimmed towards the edge of the scan patch, so a light that is about to fall
			// out of range is already dark when it goes rather than snapping off.
			float s = strength * lightScanner.fade[i];
			lightColours[i * 3] = tint.getRed() / 255f * s;
			lightColours[i * 3 + 1] = tint.getGreen() / 255f * s;
			lightColours[i * 3 + 2] = tint.getBlue() / 255f * s;
			lightRadii[i] = radius;
		}

		glUniform3fv(uniLightPos, lightScanner.positions);
		glUniform3fv(uniLightColor, lightColours);
		glUniform1fv(uniLightRadius, lightRadii);

		/*
		 * Flicker from two out-of-step sines rather than random noise: firelight wavers
		 * continuously, and per-frame randomness reads as a strobe instead.
		 */
		float t = monotonicSeconds();
		float flicker = 1f + config.lightFlicker() / 100f
			* (0.10f * (float) Math.sin(t * 7.3) + 0.06f * (float) Math.sin(t * 11.9 + 1.7));
		glUniform1f(uniLightFlicker, flicker);
	}

	/**
	 * How enclosed the player is, 0 open sky to 1 fully underground.
	 *
	 * <p>Detected from world coordinates: OSRS puts underground areas in a band starting
	 * at y 6400, which is a stable property of the map rather than something that needs a
	 * per-region list. Eased rather than switched, so stepping into a cave fades down
	 * instead of snapping.
	 */
	private float undergroundFactor()
	{
		float target = 0f;

		if (config.undergroundDarkening() > 0 && isUnderground())
		{
			target = config.undergroundDarkening() / 100f;
		}

		// Roughly a second to settle at 50fps.
		undergroundBlend += (target - undergroundBlend) * 0.02f;
		return undergroundBlend;
	}

	/**
	 * Whether the player is in a cave, dungeon, raid or any other enclosed area.
	 *
	 * <p>OSRS puts them all in a band of the world map starting at y 6400, which is a stable
	 * property of the map rather than something needing a per-region list to maintain.
	 */
	private boolean isUnderground()
	{
		Player player = client.getLocalPlayer();
		WorldPoint wp = player == null ? null : player.getWorldLocation();
		return wp != null && wp.getY() >= UNDERGROUND_Y;
	}

	/**
	 * How far the sky should be blacked out, 0 open air to 1 fully enclosed.
	 *
	 * <p>Separate from {@link #undergroundFactor()}, which is gated behind the darkening
	 * slider - a roof over your head is a fact about where you are standing, not an effect
	 * to be turned down, so a sun setting through the ceiling of a dungeon should not depend
	 * on an unrelated setting being switched on.
	 *
	 * <p>Advanced once per frame by the caller rather than on read, since easing that runs
	 * per call would settle at a different rate depending on how many things asked.
	 *
	 * <p>Asymmetric on purpose. Going in is instant: entering a cave is a hard cut in the
	 * game itself, and a second of daylight bleeding through the ceiling afterwards reads as
	 * the effect failing to keep up rather than as a transition. Coming out eases, where the
	 * same second reads as stepping into the light.
	 */
	private float undergroundSkyFade()
	{
		float target = !config.undergroundSky() && isUnderground() ? 1f : 0f;

		if (target > skyBlackout)
		{
			skyBlackout = target;
		}
		else
		{
			// Roughly a second to settle at 50fps.
			skyBlackout += (target - skyBlackout) * 0.02f;
		}

		return skyBlackout;
	}

	/**
	 * Where the moon is in its cycle: 0 and 1 are new, 0.5 is full.
	 *
	 * <p>Follows the real lunar calendar rather than an arbitrary loop, so the moon
	 * outside matches the one in game. The preview override forces a phase for testing,
	 * since waiting a fortnight to see the other half of the cycle is not practical.
	 */
	private float moonPhase()
	{
		int preview = config.moonPhasePreview();
		if (preview >= 0)
		{
			return preview / 100f;
		}

		if (!config.moonPhases())
		{
			// Phases off: a permanently full moon, which is the whole disc lit.
			return 0.5f;
		}

		/*
		 * Anchored to a real new moon so the default cycle length lines up with the actual
		 * lunar calendar. A shorter cycle keeps the same anchor and simply runs faster -
		 * at the real 29.5 days the change from one night to the next is only a few
		 * percent, which is accurate but too slow to notice while playing.
		 */
		double epochDays = System.currentTimeMillis() / 86400000d;
		double cycleDays = Math.max(1, config.moonCycleDays());
		double cycles = (epochDays - KNOWN_NEW_MOON_EPOCH_DAYS) / cycleDays;
		return (float) (cycles - Math.floor(cycles));
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
	/**
	 * Seconds since the plugin started. Strictly increasing and free of wrapping, unlike
	 * the free-running counter the stars twinkle on.
	 */
	private float monotonicSeconds()
	{
		return (System.nanoTime() - skyClockStartNanos) / 1e9f;
	}

	private float skySeconds()
	{
		float elapsed = monotonicSeconds();
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

	/**
	 * Decides whether a meteor is flying and advances it.
	 *
	 * <p>Time is cut into fixed slots and each slot is seeded from its own index, so the
	 * flight is reproducible without storing anything between frames. Deciding it here
	 * rather than in the shader is what lets a sound fire at the moment of spawn.
	 *
	 * @param mode 0 off, 1 rare, 2 showcase
	 */
	private void updateMeteor(int mode)
	{
		if (mode <= 0)
		{
			meteorActive = false;
			// Forget the slot, so re-enabling mid-slot doesn't skip the next spawn.
			lastMeteorSlot = -1;
			return;
		}

		float seconds = monotonicSeconds();
		int slot = (int) (seconds / METEOR_SLOT);

		if (slot != lastMeteorSlot)
		{
			lastMeteorSlot = slot;

			Random rng = new Random(slot * 0x9E3779B97F4A7C15L);
			// Rare by default. Showcase fires nearly every slot so the speed setting can
			// be judged without waiting minutes for a real one.
			float chance = mode > 1 ? 0.9f : 0.001f;
			meteorActive = rng.nextFloat() < chance;

			if (meteorActive)
			{
				/*
				 * Aimed at wherever the camera is facing, with a spread either side so
				 * they don't all cross dead centre. A uniformly random bearing puts most
				 * meteors behind the player, and given how rarely they fire, one that
				 * spawns out of view is one nobody ever sees.
				 *
				 * The camera's forward direction in world XZ is (-sin(yaw), cos(yaw)) and
				 * a bearing b points along (sin(b), cos(b)), so b = -yaw faces the centre
				 * of the view. Chosen once here and held for the flight, so the meteor
				 * stays fixed in the world rather than following the camera.
				 */
				float spread = (rng.nextFloat() - 0.5f) * 2f * METEOR_SPREAD;
				meteorPath[0] = -lastCameraYawRad + spread;
				meteorPath[1] = 0.30f + rng.nextFloat() * 0.45f;
				meteorPath[2] = (rng.nextFloat() - 0.5f) * 1.5f;
				meteorPath[3] = 0.18f + rng.nextFloat() * 0.22f;

				if (config.shootingStarSound())
				{
					client.playSoundEffect(config.shootingStarSoundId());
				}
			}
		}

		if (meteorActive)
		{
			float phase = seconds / METEOR_SLOT - slot;
			meteorTravel = phase * shootingStarSpeedMultiplier();
		}
	}

	/**
	 * Scales how fast a meteor crosses the sky. Higher means it covers its arc in less of
	 * its time slot, so it streaks past rather than drifting.
	 */
	private float shootingStarSpeedMultiplier()
	{
		switch (config.shootingStarSpeed())
		{
			case 3:
				return 4.5f;
			case 2:
				return 2.4f;
			default:
				return 1.15f;
		}
	}

	private float auroraSpeedMultiplier()
	{
		switch (config.auroraSpeed())
		{
			case 3:
				return 3.5f;
			case 2:
				return 2f;
			default:
				return 1f;
		}
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
		Model skybox = effectiveSkyMode() == SkyMode.GAME ? scene.getSkybox() : null;
		if (skybox == null)
		{
			glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			// Runs day and night now - it draws the sun, clouds and lightning bolts too,
			// not just stars. A storm keeps the pass alive even with everything else off,
			// since the bolt is drawn here, and so does overcast, whose deck is the only
			// thing it has to show.
			WeatherMode skyWeather = activeWeather();
			if (effectiveSkyMode() == SkyMode.TIME_OF_DAY
				&& (config.nightSky() || config.showSun() || config.cloudAmount() > 0
					|| skyWeather.overcast() > 0f
					|| (skyWeather.hasLightning() && config.lightning())))
			{
				drawSkyPass(sky, cameraPitch, cameraYaw);
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
		if (effectiveSkyMode() != SkyMode.TIME_OF_DAY)
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
	 * Draws the resolved scene through anti-aliasing, sharpening and vignette, in place of
	 * the plain blit.
	 */
	/**
	 * Draws a texture over a whole framebuffer, scaling it to fit.
	 *
	 * <p>Used to get the scene out of a render-scaled framebuffer and onto the window, which
	 * a blit cannot do from a multisampled source at a different size.
	 */
	private void drawUpscale(int dstFbo, int width, int height, int tex)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
		glViewport(0, 0, width, height);

		glUseProgram(glUpscaleProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, tex);
		glUniform1i(uniUpscaleSrc, 0);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glDepthMask(true);
	}

	/**
	 * @param width  window size, what gets drawn to
	 * @param srcW   scene size, what gets read from - the two differ under a render scale,
	 *               and the edge filters need the source's texel size rather than the
	 *               destination's or they sample the wrong distance
	 */
	private void renderImagePass(int defaultFbo, int width, int height, int srcW, int srcH)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, defaultFbo);
		glViewport(0, 0, width, height);

		glUseProgram(glPostProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, texResolve);
		glUniform1i(uniPostSrc, 0);
		glUniform2f(uniPostTexel, 1f / Math.max(1, srcW), 1f / Math.max(1, srcH));
		glUniform1f(uniPostFxaa, config.fxaa() ? 1f : 0f);
		glUniform1f(uniPostSharpen, config.sharpen() / 100f * 0.5f);
		glUniform1f(uniPostVignette, config.vignette() / 100f * 0.8f);

		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glDepthMask(true);
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
		int rq = config.effectQuality();
		glUniform1i(uniRayCount, rq >= 3 ? 24 : rq == 2 ? 16 : 10);
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
		// Nothing falls on you under a roof, so precipitation thins out with the sky it
		// comes from rather than raining on the floor of a dungeon.
		glUniform1f(uniWeatherAmount,
			config.weatherAmount() / 100f * weatherIntensity() * (1f - skyBlackout));
		glUniform1f(uniWeatherHeavy, mode.heavy());
		glUniform1f(uniWeatherWind, config.weatherWind() / 100f);

		/*
		 * Precipitation is lit by the sky, so it dims after dark - otherwise rain glows
		 * white against a night scene and reads as screen damage.
		 *
		 * The floor is deliberately well above the real light level. Dimming as far as
		 * physics suggests made rain effectively invisible at night, which is a worse
		 * failure than being a little too bright.
		 */
		float night = effectiveSkyMode() == SkyMode.TIME_OF_DAY
			? SkyGradient.nightFactorAt(skyTime())
			: 0f;
		glUniform1f(uniWeatherLight, 1f - night * 0.55f);
		glUniform3f(uniWeatherSkyColor,
			(lastSkyColor >> 16 & 0xFF) / 255f,
			(lastSkyColor >> 8 & 0xFF) / 255f,
			(lastSkyColor & 0xFF) / 255f);
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
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		/*
		 * Two sizes from here on, and mixing them up is how a frame ends up stretched or
		 * cropped. The window is what gets written to; the scene framebuffer is what gets
		 * read from, and with a render scale set they are different.
		 */
		final int outW = getScaledValue(transform.getScaleX(), lastStretchedCanvasWidth);
		final int outH = getScaledValue(transform.getScaleY(), lastStretchedCanvasHeight);

		int width = sceneFboWidth > 0 ? sceneFboWidth : outW;
		int height = sceneFboHeight > 0 ? sceneFboHeight : outH;
		final boolean rescaling = width != outW || height != outH;

		int defaultFbo = awtContext.getFramebuffer(false);

		// One switch to bypass every post pass, for comparing what they actually cost.
		boolean canPost = fboResolve != -1 && enhancements() && config.postProcessing();
		boolean bloom = canPost && config.bloomEnabled() && glBloomProgram != 0;
		boolean rays = canPost && config.godRays() > 0 && glGodrayProgram != 0
			&& updateSunScreenPos();
		boolean imagePass = canPost && glPostProgram != 0
			&& (config.fxaa() || config.sharpen() > 0 || config.vignette() > 0);

		/*
		 * Rescaling forces the resolve too, even with every effect off. A multisampled blit
		 * cannot resize - source and destination rectangles must match exactly - so the only
		 * way out of a multisampled scene at a different size is to resolve it to a plain
		 * texture first and then draw that texture at the size wanted.
		 */
		if (bloom || rays || imagePass || (rescaling && fboResolve != -1))
		{
			// Must run before the scene leaves fboScene.
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

		if (imagePass)
		{
			// Draws the scene through FXAA/sharpen/vignette instead of blitting it, so
			// bloom and god rays still composite on top afterwards. Reads the resolve, so
			// it scales to the window on its own.
			renderImagePass(defaultFbo, outW, outH, width, height);
		}
		else if (rescaling && fboResolve != -1)
		{
			drawUpscale(defaultFbo, outW, outH, texResolve);
		}
		else
		{
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
			glBlitFramebuffer(0, 0, width, height, 0, 0, outW, outH,
				GL_COLOR_BUFFER_BIT, GL_NEAREST);
		}

		// Composites write to the window, so they take the window's size. They draw
		// fullscreen from half-res buffers already, so scaling costs them nothing.
		if (rays)
		{
			compositeAdditive(defaultFbo, outW, outH, texBloom[0],
				config.godRays() / 100f, glGodrayProgram);
		}

		if (bloom)
		{
			renderBloom(width, height);
			compositeBloom(defaultFbo, outW, outH);
		}

		// After the scene is on the default framebuffer but before the UI is composited,
		// so precipitation falls in front of the world and behind the interface.
		if (activeWeather().hasPrecipitation() && glWeatherProgram != 0)
		{
			// Window size: precipitation is drawn onto the window over the finished scene,
			// so it stays at full resolution regardless of what the world rendered at.
			drawWeather(defaultFbo, outW, outH);
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
			catch (Throwable ex)
			{
				/*
				 * Throwable, not Exception. The depth-sort bucket bounds check in
				 * ModelUploader is an assert, and AssertionError extends Error - so it
				 * slipped straight past a catch meant to contain exactly this, escaped
				 * into the client's render loop and froze it. Upstream never sees this
				 * because production runs without assertions enabled.
				 */
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
			catch (Throwable ex)
			{
				/*
				 * Throwable, not Exception. The depth-sort bucket bounds check in
				 * ModelUploader is an assert, and AssertionError extends Error - so it
				 * slipped straight past a catch meant to contain exactly this, escaped
				 * into the client's render loop and froze it. Upstream never sees this
				 * because production runs without assertions enabled.
				 */
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
		// Timed here, at the end of a frame's work, so what is measured is the interval the
		// player actually sees rather than any one stage of producing it.
		long frameNanos = System.nanoTime();
		frameStats.frame(frameNanos);

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
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
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

	/** Render scale as a percentage, clamped to the range the setting offers. */
	private int renderScalePercent()
	{
		return Ints.constrainToRange(config.renderScale(), 50, 200);
	}

	/**
	 * Viewport for the world, in scene-framebuffer pixels.
	 *
	 * <p>Same as {@link #glDpiAwareViewport} with the render scale folded in. The world is
	 * rasterised into a framebuffer of a different size to the window, so its viewport has
	 * to match that framebuffer rather than the window - while the interface, drawn straight
	 * to the window, must not be scaled at all.
	 */
	private void glSceneViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		final int scale = renderScalePercent();

		glViewport(
			getScaledValue(t.getScaleX(), x) * scale / 100,
			getScaledValue(t.getScaleY(), y) * scale / 100,
			Math.max(1, getScaledValue(t.getScaleX(), width) * scale / 100),
			Math.max(1, getScaledValue(t.getScaleY(), height) * scale / 100));
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
	/**
	 * Drops the frame rate cap while the client window is in the background.
	 *
	 * <p>Only meaningful with unlocked FPS - without it the client runs on its own 20ms
	 * timer and this target is ignored. Checked on the game tick rather than per frame:
	 * focus changes are a human-scale event and this writes client state.
	 */
	private void updateUnfocusedFpsCap()
	{
		if (!config.unlockFps())
		{
			return;
		}

		int cap = config.unfocusedFpsTarget();
		int target = cap > 0 && !isClientFocused() ? cap : config.fpsTarget();
		if (target != lastAppliedFpsTarget)
		{
			lastAppliedFpsTarget = target;
			client.setUnlockedFpsTarget(target);
		}
	}

	/**
	 * Whether the client window is the active one. Uses the window rather than the canvas,
	 * so typing in chat or clicking a side panel still counts as focused.
	 */
	private boolean isClientFocused()
	{
		if (canvas == null)
		{
			return true;
		}
		Window window = SwingUtilities.getWindowAncestor(canvas);
		return window == null || window.isActive();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!lwjglInitted)
		{
			return;
		}

		updateUnfocusedFpsCap();
		updateLights();

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
		else if (event.getCommand().equals("lightids"))
		{
			reportNearbyObjectIds();
		}
	}

	/**
	 * Lists object ids near the player, so light sources can be identified from what is
	 * actually there rather than guessed.
	 */
	private void reportNearbyObjectIds()
	{
		WorldView wv = client.getTopLevelWorldView();
		Player player = client.getLocalPlayer();
		if (wv == null || player == null)
		{
			say("[GPU v2] Not logged in.");
			return;
		}

		LocalPoint origin = player.getLocalLocation();
		Tile[][][] tiles = wv.getScene().getTiles();
		int plane = wv.getPlane();
		if (origin == null || plane < 0 || plane >= tiles.length)
		{
			return;
		}

		// Renderable kept alongside the id: the model test is now what decides most objects,
		// and it cannot be re-derived from an id alone.
		Map<Integer, Renderable> ids = new TreeMap<>();
		// Where each id sits and what kind of object it is, so a specific torch on screen can
		// be matched to a specific id - the names give nothing to go on.
		Map<Integer, String> where = new TreeMap<>();
		final int radius = 6;
		int cx = origin.getSceneX();
		int cy = origin.getSceneY();

		for (int x = Math.max(0, cx - radius); x <= Math.min(tiles[plane].length - 1, cx + radius); ++x)
		{
			for (int y = Math.max(0, cy - radius); y <= Math.min(tiles[plane][x].length - 1, cy + radius); ++y)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}

				if (tile.getGameObjects() != null)
				{
					for (GameObject obj : tile.getGameObjects())
					{
						if (obj != null)
						{
							ids.put(obj.getId(), obj.getRenderable());
							where.put(obj.getId(), bearing(cx, cy, x, y) + " game");
						}
					}
				}
				if (tile.getWallObject() != null)
				{
					ids.put(tile.getWallObject().getId(), tile.getWallObject().getRenderable1());
					where.put(tile.getWallObject().getId(), bearing(cx, cy, x, y) + " wall");
				}
				if (tile.getGroundObject() != null)
				{
					ids.put(tile.getGroundObject().getId(), tile.getGroundObject().getRenderable());
					where.put(tile.getGroundObject().getId(), bearing(cx, cy, x, y) + " ground");
				}
				if (tile.getDecorativeObject() != null)
				{
					ids.put(tile.getDecorativeObject().getId(), tile.getDecorativeObject().getRenderable());
					where.put(tile.getDecorativeObject().getId(), bearing(cx, cy, x, y) + " decor");
				}
			}
		}

		/*
		 * Ids alone never explained a torch staying dark - the question is always what
		 * detection saw and what it made of it. Both tests are run through the scanner's own
		 * code, so this reports the actual judgement rather than a re-creation that could
		 * drift away from it.
		 */
		if (lightScanner == null)
		{
			lightScanner = new LightScanner(client);
		}

		List<String> lit = new ArrayList<>();
		List<String> dark = new ArrayList<>();

		/*
		 * Anything with warm-hued geometry gets its numbers printed whether it passed or
		 * not. "It didn't work" cannot be acted on; "the flame faces are there but at
		 * luminance 55" or "this object isn't animated" both can, and they need opposite
		 * fixes.
		 */
		List<String> evidence = new ArrayList<>();

		for (Map.Entry<Integer, Renderable> e : ids.entrySet())
		{
			String name = lightScanner.resolveName(e.getKey());
			Renderable r = e.getValue();
			Model model = LightScanner.modelOf(r);

			boolean byName = name != null && LightScanner.nameSuggestsLight(name);
			boolean byModel = model != null && LightScanner.isBurning(r, model);
			boolean animated = r instanceof DynamicObject;

			String label = e.getKey() + "=" + (name == null ? "<unresolved>" : name)
				+ " @" + where.getOrDefault(e.getKey(), "?");
			(byName || byModel ? lit : dark).add(label
				+ (byName ? "(name)" : "") + (byModel ? "(model)" : ""));

			if (model == null)
			{
				/*
				 * Two different failures wearing the same label. No renderable at all means
				 * this renderer freed the CPU-side model after uploading it to the GPU, and
				 * nothing can bring it back - that would put static scenery permanently out
				 * of reach. A renderable whose model is null is a transient build, and is
				 * only a matter of asking again later.
				 */
				evidence.add(label + (r == null ? " no renderable (freed after upload?)"
					: " renderable " + r.getClass().getSimpleName() + " but model null"));
			}
			else
			{
				String desc = FlameDetector.describe(model.getFaceColors1());
				// Only worth printing where there is warmth to explain.
				if (!desc.contains("warm=0"))
				{
					evidence.add(label + " anim=" + (animated ? "Y" : "N") + " " + desc);
				}
			}
		}

		/*
		 * The live set, not just what qualifies. Whether the budget is binding, and how
		 * close in it binds, is the difference between "detection is broken" and "there are
		 * more lights here than can be drawn" - which look identical in game and need
		 * opposite fixes.
		 */
		float timeFactor = pointLightTimeFactor();
		if (config.dynamicLights() <= 0)
		{
			say("[GPU v2] Dynamic lights is 0 - nothing will light regardless of what follows.");
		}
		else if (timeFactor < 0.02f)
		{
			say("[GPU v2] Daylight - dynamic lights are off until dusk. Tick 'Lights during "
				+ "the day' to keep them on, or use Preview hour to test at night.");
		}
		else
		{
			String budgetNote = lightScanner.lastCandidates > config.maxLights()
				? " - BUDGET BINDING, raise 'Max lights at once'"
				: " - budget not binding";
			say("[GPU v2] Lights: " + lightScanner.count + " drawn, "
				+ lightScanner.lastCandidates + " found within " + lightScanner.scanRadiusTiles()
				+ " tiles, cap " + config.maxLights()
				+ ", fade edge " + String.format("%.1f", lightScanner.lastEdgeTiles)
				+ " tiles, time factor " + String.format("%.2f", timeFactor)
				+ budgetNote);
		}

		say("[GPU v2] Within " + radius + " tiles - lighting: " + (lit.isEmpty() ? "none" : lit));
		say("[GPU v2] not lighting: " + dark);
		for (String line : evidence)
		{
			say("[GPU v2]   " + line);
		}
		if (evidence.isEmpty())
		{
			say("[GPU v2] Nothing nearby has fire-coloured geometry at all - stand right "
				+ "next to a torch and run this again.");
		}
	}

	/** Compass bearing and distance of a scene tile from the player, for ::lightids. */
	private static String bearing(int cx, int cy, int x, int y)
	{
		int dx = x - cx;
		int dy = y - cy;
		if (dx == 0 && dy == 0)
		{
			return "here";
		}

		// Scene Y increases north.
		String ns = dy > 0 ? "N" : dy < 0 ? "S" : "";
		String ew = dx > 0 ? "E" : dx < 0 ? "W" : "";
		return (int) Math.round(Math.sqrt(dx * dx + dy * dy)) + ns + ew;
	}

	private void say(String msg)
	{
		log.info(msg);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
	}
}
