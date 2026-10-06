/*
 * Copyright (c) 2026, markp
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

import static org.lwjgl.opengl.GL33C.*;

import com.gpuv2.config.GraphicsPreset;
import com.gpuv2.config.SkyMode;
import com.gpuv2.config.WeatherMode;
import com.gpuv2.template.Template;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Random;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.gpu.api.GpuExtension;

/**
 * The plugin's sky, weather and lighting, built as an extension of the core GPU plugin
 * instead of as a renderer of its own.
 *
 * <p>Three things are drawn or changed, each through a different part of the API:
 *
 * <ul>
 * <li>The sky, in {@link #drawSkybox()}, with a program of its own.
 * <li>The world's lighting, fog and ground weather, by injecting code into the renderer's
 * scene shader and setting its uniforms each frame on the program handed over by
 * {@link #onProgramCreate(int)}.
 * <li>Precipitation, in {@link #onPostDrawToplevel()}, over the finished scene.
 * </ul>
 *
 * <p>The logic for what the weather is, where the sun stands and how dark it should be is
 * the full plugin's, moved across as it stood. Only how the results reach the screen
 * differs.
 */
@Slf4j
class EnhancementExtension extends GpuExtension
{
	private static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_frag.glsl");

	// Reuses the sky pass's fullscreen-triangle vertex shader.
	private static final Shader WEATHER_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "weather_frag.glsl");

	private static final String FRAG_DEFINITIONS = resource("ext_frag_defs.glsl");
	private static final String FRAG_MAIN = resource("ext_frag_main.glsl");
	private static final String VERT_DEFINITIONS = resource("ext_vert_defs.glsl");
	private static final String VERT_MAIN = resource("ext_vert_main.glsl");

	// Used when the renderer's own draw distance cannot be read.
	private static final int FALLBACK_DRAW_DISTANCE = 50;

	private static final float HEIGHT_FOG_EYE_OFFSET = 180f;
	private static final int UNDERGROUND_Y = 6400;
	private static final double KNOWN_NEW_MOON_EPOCH_DAYS = 18219.0;
	private static final float METEOR_SLOT = 3f;
	private static final float METEOR_SPREAD = 0.70f;
	private static final double MAX_SUN_ELEVATION = Math.toRadians(22);
	private static final float NIGHT_AMBIENT_FLOOR = 0.4f;
	private static final float BOLT_SPREAD = 0.95f;
	private static final int NIGHT_OVERCAST = 0x141922;
	private static final float MAX_WEATHER_CLOUD = 0.70f;

	private final Client client;
	private final GpuV2ExtensionConfig config;
	private final ConfigManager configManager;

	// Cleared when the owning plugin stops but the extension could not be unregistered.
	@Setter
	private volatile boolean enabled = true;

	// The renderer's scene program, handed over by onProgramCreate.
	private int sceneProgram;
	private int glSkyProgram;
	private int glWeatherProgram;
	private int vaoSkyHandle;

	// Set by drawSkybox, which is skipped in areas with a skybox model. When it has not run,
	// onPostDrawToplevel does the frame's work instead.
	private boolean frameDone;

	private int lastSkyColor;
	private float lastCameraYawRad;
	private float undergroundBlend;
	private float skyBlackout;
	private int lastMeteorSlot = -1;
	private boolean meteorActive;
	private float meteorTravel;
	private final float[] meteorPath = new float[4];
	private final float[] sunDir = new float[3];
	private final float[] moonDir = new float[3];
	private long skyClockStartNanos = System.nanoTime();
	private float skyClockStartSeconds;
	private int lastLightningSlot = -1;
	private float lightningBearing;
	private LightScanner lightScanner;
	private final float[] lightColours = new float[LightScanner.MAX_LIGHTS * 3];
	private final float[] lightRadii = new float[LightScanner.MAX_LIGHTS];

	private int uniGradeGamma = -1;
	private int uniGradeContrast = -1;
	private int uniGradeSaturation = -1;
	private int uniGradeTemperature = -1;
	private int uniToneMap = -1;
	private int uniLightningFlash = -1;
	private int uniLightStrength = -1;
	private int uniLightAmbient = -1;
	private int uniLightSunColor = -1;
	private int uniLightSunDir = -1;
	private int uniLightMoonColor = -1;
	private int uniLightMoonDir = -1;
	private int uniGroundSnow = -1;
	private int uniGroundWet = -1;
	private int uniCloudShadow = -1;
	private int uniCloudShadowTime = -1;
	private int uniAerial = -1;
	private int uniUnderground = -1;
	private int uniLightCount = -1;
	private int uniLightPos = -1;
	private int uniLightColor = -1;
	private int uniLightRadius = -1;
	private int uniLightFlicker = -1;
	private int uniHeightFog = -1;
	private int uniHeightFogDepth = -1;
	private int uniHeightFogEye = -1;
	private int uniEnabled = -1;
	private int uniFogColor = -1;
	private int uniFogDepth = -1;
	private int uniSkyColor = -1;
	private int uniSkyZenithColor = -1;
	private int uniSkyNight = -1;
	private int uniSkyStarDensity = -1;
	private int uniSkyHalfW = -1;
	private int uniSkyHalfH = -1;
	private int uniSkyCosPitch = -1;
	private int uniSkySinPitch = -1;
	private int uniSkyCosYaw = -1;
	private int uniSkySinYaw = -1;
	private int uniSkyStarTime = -1;
	private int uniSkyCloudTime = -1;
	private int uniSkySunDir = -1;
	private int uniSkyMoonDir = -1;
	private int uniSkyShowMoon = -1;
	private int uniSkyShowSun = -1;
	private int uniSkySunGlow = -1;
	private int uniSkySunGlare = -1;
	private int uniSkyMoonGlow = -1;
	private int uniSkyMoonPhase = -1;
	private int uniSkyCloudAmount = -1;
	private int uniSkyCloudOpacity = -1;
	private int uniSkyCloudSeal = -1;
	private int uniSkyDim = -1;
	private int uniSkySunOcclusion = -1;
	private int uniSkyToneMap = -1;
	private int uniSkyMeteorSamples = -1;
	private int uniSkyCloudOctaves = -1;
	private int uniSkyMeteorActive = -1;
	private int uniSkyMeteorTravel = -1;
	private int uniSkyMeteorPath = -1;
	private int uniSkyAuroraStrength = -1;
	private int uniSkyAuroraTime = -1;
	private int uniSkyBoltStrength = -1;
	private int uniSkyBoltSeed = -1;
	private int uniSkyBoltDirXZ = -1;
	private int uniWeatherType = -1;
	private int uniWeatherTime = -1;
	private int uniWeatherAmount = -1;
	private int uniWeatherAspect = -1;
	private int uniWeatherHeavy = -1;
	private int uniWeatherLightning = -1;
	private int uniWeatherWind = -1;
	private int uniWeatherLight = -1;
	private int uniWeatherSkyColor = -1;

	private final GlState glState = new GlState();

	// How long one spell of weather takes to give way to another when the cause is the
	// player walking into a different climate rather than the cycle moving on.
	private static final float REGION_FADE_SECONDS = 6f;

	// With fog down, the world is clear this many tiles out from the camera and then fades.
	private static final int FOG_WEATHER_CLEAR_TILES = 8;

	// The game's own set of rolling thunder, used when no particular sound is chosen.
	private static final int[] THUNDER_SOUNDS = {4364, 4343, 4416, 4353, 4391, 4375, 4379};

	// Moonlight is cool and far weaker than the sun: enough to give a full moon night a lit
	// side, not enough to read as a blue day.
	private static final float MOON_R = 0.62f;
	private static final float MOON_G = 0.72f;
	private static final float MOON_B = 1.00f;
	private static final float MOONLIGHT_SCALE = 0.45f;

	// Automatic weather as it is being shown, which lags what the cycle asks for while one
	// spell fades out and the next fades in.
	private WeatherMode autoMode = WeatherMode.OFF;
	private float autoBase;
	private float autoFade = 1f;
	private long lastAutoNanos;

	private int cachedDrawDistance = FALLBACK_DRAW_DISTANCE;

	private long thunderDueNanos;
	private float thunderSeed;

	private final PostEffects post = new PostEffects();
	private final FrameStats frameStats;

	// Kept for the god-ray pass, which projects the sun with the angles the frame was drawn at.
	private float lastCameraPitchRad;
	private final float[] sunScreen = new float[2];
	private float sunRayFade;

	EnhancementExtension(Client client, GpuV2ExtensionConfig config, ConfigManager configManager,
		FrameStats frameStats)
	{
		this.frameStats = frameStats;
		this.client = client;
		this.config = config;
		this.configManager = configManager;

		skyClockStartNanos = System.nanoTime();
		LocalTime now = LocalTime.now();
		skyClockStartSeconds = now.getHour() * 3600f + now.getMinute() * 60f
			+ now.getSecond() + now.getNano() / 1e9f;
	}

	// ------------------------------------------------------------------ lifecycle

	@Override
	public void onContextCreate()
	{
		createPrograms();
	}

	@Override
	public void onContextDestroy()
	{
		if (glSkyProgram != 0)
		{
			glDeleteProgram(glSkyProgram);
			glSkyProgram = 0;
		}
		if (glWeatherProgram != 0)
		{
			glDeleteProgram(glWeatherProgram);
			glWeatherProgram = 0;
		}
		if (vaoSkyHandle != 0)
		{
			glDeleteVertexArrays(vaoSkyHandle);
			vaoSkyHandle = 0;
		}
		post.destroy();
		sceneProgram = 0;
	}

	/**
	 * Called with the scene program after the context is created and again after every
	 * shader recompile, so the uniform locations are looked up afresh each time.
	 *
	 * <p>Also builds this extension's own programs if they do not exist yet. An extension
	 * registered while the GPU plugin is already running gets this call but not
	 * onContextCreate.
	 */
	@Override
	public void onProgramCreate(int program)
	{
		sceneProgram = program;
		uniGradeGamma = glGetUniformLocation(sceneProgram, "gv2_gradeGamma");
		uniGradeContrast = glGetUniformLocation(sceneProgram, "gv2_gradeContrast");
		uniGradeSaturation = glGetUniformLocation(sceneProgram, "gv2_gradeSaturation");
		uniGradeTemperature = glGetUniformLocation(sceneProgram, "gv2_gradeTemperature");
		uniToneMap = glGetUniformLocation(sceneProgram, "gv2_toneMap");
		uniLightningFlash = glGetUniformLocation(sceneProgram, "gv2_lightningFlash");
		uniLightStrength = glGetUniformLocation(sceneProgram, "gv2_lightStrength");
		uniLightAmbient = glGetUniformLocation(sceneProgram, "gv2_lightAmbient");
		uniLightSunColor = glGetUniformLocation(sceneProgram, "gv2_lightSunColor");
		uniLightSunDir = glGetUniformLocation(sceneProgram, "gv2_lightSunDir");
		uniLightMoonColor = glGetUniformLocation(sceneProgram, "gv2_lightMoonColor");
		uniLightMoonDir = glGetUniformLocation(sceneProgram, "gv2_lightMoonDir");
		uniGroundSnow = glGetUniformLocation(sceneProgram, "gv2_groundSnow");
		uniGroundWet = glGetUniformLocation(sceneProgram, "gv2_groundWet");
		uniCloudShadow = glGetUniformLocation(sceneProgram, "gv2_cloudShadow");
		uniCloudShadowTime = glGetUniformLocation(sceneProgram, "gv2_cloudShadowTime");
		uniAerial = glGetUniformLocation(sceneProgram, "gv2_aerial");
		uniUnderground = glGetUniformLocation(sceneProgram, "gv2_underground");
		uniLightCount = glGetUniformLocation(sceneProgram, "gv2_lightCount");
		uniLightPos = glGetUniformLocation(sceneProgram, "gv2_lightPos");
		uniLightColor = glGetUniformLocation(sceneProgram, "gv2_lightColor");
		uniLightRadius = glGetUniformLocation(sceneProgram, "gv2_lightRadius");
		uniLightFlicker = glGetUniformLocation(sceneProgram, "gv2_lightFlicker");
		uniHeightFog = glGetUniformLocation(sceneProgram, "gv2_heightFog");
		uniHeightFogDepth = glGetUniformLocation(sceneProgram, "gv2_heightFogDepth");
		uniHeightFogEye = glGetUniformLocation(sceneProgram, "gv2_heightFogEye");
		uniEnabled = glGetUniformLocation(sceneProgram, "gv2_enabled");
		uniFogColor = glGetUniformLocation(sceneProgram, "gv2_fogColor");
		uniFogDepth = glGetUniformLocation(sceneProgram, "gv2_fogDepth");
		log.info("gpu-v2 extension: scene program {}, enabled uniform at {}", program, uniEnabled);

		createPrograms();
	}

	@Override
	public String injectShaderExtension(String hook)
	{
		if (!enabled)
		{
			return null;
		}

		switch (hook)
		{
			case "rlst_vert_definitions":
				return VERT_DEFINITIONS;
			case "rlst_vert_main_post":
				return VERT_MAIN;
			case "rlst_frag_definitions":
				return FRAG_DEFINITIONS;
			case "rlst_frag_main_post":
				return FRAG_MAIN;
			default:
				return null;
		}
	}

	private void createPrograms()
	{
		if (glSkyProgram != 0)
		{
			return;
		}

		try
		{
			Template template = new Template();
			template.addInclude(EnhancementExtension.class);
			glSkyProgram = SKY_PROGRAM.compile(template);
			glWeatherProgram = WEATHER_PROGRAM.compile(template);
			post.create(template);
		}
		catch (ShaderException ex)
		{
			log.error("gpu-v2 extension: shader compilation failed", ex);
			glSkyProgram = 0;
			glWeatherProgram = 0;
			return;
		}

		// The vertex shader builds its triangle from gl_VertexID, so the VAO carries no
		// attributes and exists only because core profile refuses to draw without one.
		vaoSkyHandle = glGenVertexArrays();

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
		uniWeatherType = glGetUniformLocation(glWeatherProgram, "weatherType");
		uniWeatherTime = glGetUniformLocation(glWeatherProgram, "weatherTime");
		uniWeatherAmount = glGetUniformLocation(glWeatherProgram, "weatherAmount");
		uniWeatherAspect = glGetUniformLocation(glWeatherProgram, "aspect");
		uniWeatherHeavy = glGetUniformLocation(glWeatherProgram, "weatherHeavy");
		uniWeatherLightning = glGetUniformLocation(glWeatherProgram, "lightning");
		uniWeatherWind = glGetUniformLocation(glWeatherProgram, "weatherWind");
		uniWeatherLight = glGetUniformLocation(glWeatherProgram, "weatherLight");
		uniWeatherSkyColor = glGetUniformLocation(glWeatherProgram, "weatherSkyColor");

		log.info("gpu-v2 extension: sky program {}, weather program {}", glSkyProgram, glWeatherProgram);
	}

	// ------------------------------------------------------------------ per frame

	/**
	 * Called by the renderer after it has cleared the frame and before it draws the scene,
	 * with the scene program bound - but only in areas that have no skybox model.
	 */
	@Override
	public boolean drawSkybox()
	{
		if (!enabled || sceneProgram == 0)
		{
			return false;
		}

		glState.capture();
		try
		{
			beginFrame();
			frameDone = true;

			if (!enhancements())
			{
				return false;
			}

			SkyMode mode = effectiveSkyMode();
			if (mode == SkyMode.GAME)
			{
				// The renderer has already cleared to the game's own colour.
				return false;
			}

			int sky = lastSkyColor;
			glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			glClear(GL_COLOR_BUFFER_BIT);

			// Runs day and night - it draws the sun, clouds and lightning bolts too, not
			// just stars. A storm keeps the pass alive even with everything else off, since
			// the bolt is drawn here, and so does overcast, whose deck is all it has to show.
			WeatherMode skyWeather = activeWeather();
			if (mode == SkyMode.TIME_OF_DAY && glSkyProgram != 0
				&& (config.nightSky() || config.showSun() || config.cloudAmount() > 0
					|| skyWeather.overcast() > 0f
					|| (skyWeather.hasLightning() && config.lightning())))
			{
				drawProceduralSky(sky, client.getCameraFpPitch(), client.getCameraFpYaw());
			}
			return true;
		}
		finally
		{
			glState.restore();
		}
	}

	/**
	 * Called once the top-level scene has been drawn, with its framebuffer still bound.
	 */
	@Override
	public void onPostDrawToplevel()
	{
		// Timed here, once the frame's world is drawn, so what is measured is the interval
		// between frames the player actually sees.
		frameStats.frame(System.nanoTime());

		if (!enabled || sceneProgram == 0)
		{
			return;
		}

		glState.capture();
		try
		{
			if (!frameDone)
			{
				// No drawSkybox call this frame, so the uniforms set here take effect on
				// the next one. They persist in the program, and a frame's lag in the
				// lighting is not visible.
				beginFrame();
			}
			frameDone = false;

			if (enhancements() && config.postProcessing() && post.ready())
			{
				// Only worked out when shafts are wanted - it is the one input here that
				// costs anything to produce.
				boolean sunInView = config.godRays() > 0 && updateSunScreenPos();
				post.run(config, vaoSkyHandle, glState.viewport, sunInView ? sunScreen : null, sunRayFade);
			}

			// After the effects, so precipitation falls in front of the finished image
			// rather than being blurred and bloomed along with it.
			if (enhancements() && activeWeather().hasPrecipitation() && glWeatherProgram != 0)
			{
				int[] viewport = glState.viewport;
				drawWeather(viewport[2], viewport[3]);
			}
		}
		finally
		{
			glState.restore();
		}
	}

	/** Rescans for light sources. Called on the game tick, on the client thread. */
	void onGameTick()
	{
		// Read here rather than per frame: it is a settings lookup, and it changes when the user
		// moves a slider, not between frames.
		cachedDrawDistance = stockDrawDistance();

		if (enabled && enhancements())
		{
			updateLights();
		}
	}

	/**
	 * Advances everything that is eased per frame, exactly once, then uploads the scene
	 * uniforms. Leaves the scene program bound.
	 */
	private void beginFrame()
	{
		lastCameraYawRad = client.getCameraFpYaw();
		lastCameraPitchRad = client.getCameraFpPitch();
		updateAutoWeather();
		playDueThunder();
		// Advanced once here, then read from the field everywhere else this frame - easing
		// that stepped on every read would settle at a rate depending on how many callers
		// happened to ask.
		undergroundSkyFade();
		// Feeds both the fog and the sky, so the sky and the fog it fades into agree.
		lastSkyColor = resolveSkyColor();

		pushSceneUniforms();
	}

	private void pushSceneUniforms()
	{
		glUseProgram(sceneProgram);

		final boolean fx = enhancements();
		glUniform1f(uniEnabled, fx ? 1f : 0f);
		if (!fx)
		{
			// Default preset: the shader hands the renderer's own output straight back.
			return;
		}

		final int sky = lastSkyColor;
		glUniform3f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f);
		/*
		 * Fog as weather pulls the haze in close, whatever the fog settings say - it is the
		 * weather, not the fog slider, that is asking. The renderer measures fog depth in
		 * from the edge of the drawn world, so the depth that leaves a fixed clear distance
		 * around the camera depends on how far the world is being drawn.
		 */
		final float mist = weatherMist();
		float fogDepth = config.fogEnabled() ? config.fogDepth() : 0f;
		if (mist > 0f)
		{
			float closedIn = Math.max(fogDepth, cachedDrawDistance - FOG_WEATHER_CLEAR_TILES);
			fogDepth += (closedIn - fogDepth) * mist;
		}
		glUniform1f(uniFogDepth, fogDepth);

		glUniform1f(uniGradeGamma, config.gradeGamma() / 100f);
		glUniform1f(uniGradeContrast, config.gradeContrast() / 100f);
		glUniform1f(uniGradeSaturation, config.gradeSaturation() / 100f);
		/*
		 * Colour temperature either comes from the slider or follows the sky clock, in
		 * which case the slider becomes an offset so it can still be nudged either way.
		 * Only meaningful with the time-of-day sky - there is no clock to follow otherwise.
		 */
		float temperature = config.gradeTemperature() / 100f;
		if (config.autoTemperature() && effectiveSkyMode() == SkyMode.TIME_OF_DAY)
		{
			temperature = Math.max(-1f, Math.min(1f,
				temperature + SkyGradient.temperatureAt(skyTime())));
		}
		glUniform1f(uniGradeTemperature, temperature);
		glUniform1f(uniToneMap, config.toneMapping() / 100f);

		/*
		 * The same flash value the weather pass draws, so the wash over the screen and the
		 * light on the ground come from one strike rather than two effects that happen to
		 * fire near each other.
		 */
		WeatherMode flashWeather = activeWeather();
		float flash = flashWeather.hasLightning() && config.lightning()
			? lightningFlash(weatherSeconds())
			: 0f;
		glUniform1f(uniLightningFlash, flash);

		// Fog thickens the air between here and there as well as at the edge of the world.
		glUniform1f(uniAerial, Math.min(1f, config.aerialPerspective() / 100f + mist * 0.85f));
		glUniform1f(uniUnderground, undergroundFactor());
		setupPointLights();
		setupLightingUniforms();
		setupGroundWeatherUniforms();
	}

	/**
	 * The core GPU plugin's draw distance, in tiles. Lights are searched for as far as the
	 * world is drawn when no search distance is set.
	 */
	private int stockDrawDistance()
	{
		try
		{
			Integer distance = configManager.getConfiguration("gpu", "drawDistance", Integer.class);
			return distance != null && distance > 0 ? distance : FALLBACK_DRAW_DISTANCE;
		}
		catch (RuntimeException ex)
		{
			return FALLBACK_DRAW_DISTANCE;
		}
	}

	private static String resource(String name)
	{
		try (InputStream in = EnhancementExtension.class.getResourceAsStream(name))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing shader resource " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("unreadable shader resource " + name, ex);
		}
	}

	/**
	 * The GL state this extension touches, saved on the way in and put back on the way out.
	 *
	 * <p>The renderer leaves this to extensions, and its own drawing carries on straight
	 * after each callback with whatever is bound. Putting back guessed defaults instead of
	 * what was actually there leaves it drawing the world with the wrong program or none.
	 */
	private static final class GlState
	{
		final int[] viewport = new int[4];
		private int program;
		private int vao;
		private int activeTexture;
		private int texture2d;
		private int blendSrcRgb;
		private int blendDstRgb;
		private int blendSrcAlpha;
		private int blendDstAlpha;
		private boolean blend;
		private boolean depthTest;
		private boolean cullFace;
		private boolean depthMask;

		void capture()
		{
			program = glGetInteger(GL_CURRENT_PROGRAM);
			vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
			activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
			texture2d = glGetInteger(GL_TEXTURE_BINDING_2D);
			blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
			blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
			blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
			blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
			blend = glIsEnabled(GL_BLEND);
			depthTest = glIsEnabled(GL_DEPTH_TEST);
			cullFace = glIsEnabled(GL_CULL_FACE);
			depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
			glGetIntegerv(GL_VIEWPORT, viewport);
		}

		void restore()
		{
			glUseProgram(program);
			glBindVertexArray(vao);
			glActiveTexture(activeTexture);
			glBindTexture(GL_TEXTURE_2D, texture2d);
			glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
			set(GL_BLEND, blend);
			set(GL_DEPTH_TEST, depthTest);
			set(GL_CULL_FACE, cullFace);
			glDepthMask(depthMask);
			glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		}

		private static void set(int cap, boolean on)
		{
			if (on)
			{
				glEnable(cap);
			}
			else
			{
				glDisable(cap);
			}
		}
	}

	// ------------------------------------------------------------------ carried over
	//
	// Everything below is the full plugin's own logic, unchanged except where it used to
	// bind a framebuffer or restore GL state itself - the callbacks above own that now.

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
		computeBodyDirection(dayFractionOf(time), sunDir);
	}

	/**
	 * Direction toward the moon, which depends on its phase.
	 *
	 * <p>A moon's phase <i>is</i> its angle from the sun: full means directly opposite,
	 * new means alongside. So placing the moon at a fixed opposite vector - as this used to
	 * - silently claims every moon is full, and a crescent sitting opposite the sun is not a
	 * configuration the sky can actually produce.
	 *
	 * <p>Offsetting the time of day by the phase gives that angle for free, and the rest
	 * falls out correctly: a full moon rises at sunset, a new moon rises with the sun and is
	 * lost in daylight, and the quarters sit half a sky apart.
	 */
	private void computeMoonDirection(LocalTime time)
	{
		computeBodyDirection(dayFractionOf(time) + moonPhase(), moonDir);
	}

	/**
	 * Position through the day, 0 at midnight.
	 *
	 * <p>Seconds included for the same reason the sky gradient includes them: without the
	 * fraction the sun advances a quarter of a degree once a minute and holds, which is a
	 * step rather than a movement.
	 */
	private static double dayFractionOf(LocalTime time)
	{
		return (time.getHour() * 60 + time.getMinute() + time.getSecond() / 60d) / 1440d;
	}

	/**
	 * Position of a body on its daily arc, written into {@code out} as a unit direction.
	 *
	 * @param dayFraction 0 at midnight, 0.25 at dawn - values outside 0..1 wrap, which is
	 *                    what lets the moon be given an offset rather than its own maths
	 */
	private void computeBodyDirection(double dayFraction, float[] out)
	{
		double phase = 2 * Math.PI * (dayFraction - 0.25);
		double elevation = Math.sin(phase) * MAX_SUN_ELEVATION;
		double azimuth = Math.PI / 2 + phase;

		out[0] = (float) (Math.sin(azimuth) * Math.cos(elevation));
		out[1] = (float) -Math.sin(elevation);
		out[2] = (float) (Math.cos(azimuth) * Math.cos(elevation));
	}

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
		return autoMode;
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
		return autoBase * autoFade;
	}

	/**
	 * Advances the automatic weather, once per frame.
	 *
	 * <p>The cycle and the climate between them say what the weather should be; this is
	 * what stops it snapping there. When the cycle itself moves on there is nothing to do,
	 * since each spell already ramps to nothing at its own ends. Walking into another
	 * climate is different: the desert has no rain to hand over to, so the rain that was
	 * falling a step ago is faded out and whatever the new place offers is faded in.
	 */
	private void updateAutoWeather()
	{
		long now = System.nanoTime();
		float dt = lastAutoNanos == 0 ? 0f : Math.min(0.25f, (now - lastAutoNanos) / 1e9f);
		lastAutoNanos = now;

		if (!enhancements() || config.weather() != WeatherMode.AUTO)
		{
			autoMode = WeatherMode.OFF;
			autoBase = 0f;
			autoFade = 1f;
			return;
		}

		double minutes = clockMinutes();
		int period = config.autoWeatherPeriod();
		WeatherMode wanted;
		float wantedIntensity;
		if (config.weatherFollowsRegion())
		{
			Climate climate = currentClimate();
			wanted = WeatherCycle.modeAt(minutes, period, climate);
			wantedIntensity = WeatherCycle.intensityAt(minutes, period, climate);
		}
		else
		{
			wanted = WeatherCycle.modeAt(minutes, period);
			wantedIntensity = WeatherCycle.intensityAt(minutes, period);
		}

		float step = dt / REGION_FADE_SECONDS;
		if (wanted == autoMode)
		{
			autoBase = wantedIntensity;
			autoFade = Math.min(1f, autoFade + step);
			return;
		}

		// Something else is wanted. Let what is showing go first, then swap with nothing on
		// screen, so the new weather has to build rather than appear.
		autoFade = Math.max(0f, autoFade - step);
		if (autoMode.isClear() || autoBase * autoFade <= 0.01f)
		{
			autoMode = wanted;
			autoBase = wantedIntensity;
			autoFade = wanted.isClear() ? 1f : 0f;
		}
	}

	private Climate currentClimate()
	{
		Player player = client.getLocalPlayer();
		WorldPoint wp = player == null ? null : player.getWorldLocation();
		return wp == null ? Climate.TEMPERATE : Climate.at(wp.getX(), wp.getY());
	}

	/** How much fog the weather is putting in the air right now, 0..1. None underground. */
	private float weatherMist()
	{
		return activeWeather().mist() * weatherIntensity() * (1f - skyBlackout);
	}

	/**
	 * Queues the thunder for a strike that has just begun. Sound trails light, by longer
	 * for some strikes than others, which is most of what makes a storm sound like weather
	 * rather than like an effect firing.
	 */
	private void scheduleThunder(float seed)
	{
		if (!config.thunderSound() || skyBlackout > 0.5f)
		{
			return;
		}

		thunderSeed = seed;
		thunderDueNanos = System.nanoTime() + (long) ((0.4f + seed * 1.6f) * 1e9);
	}

	private void playDueThunder()
	{
		if (thunderDueNanos == 0 || System.nanoTime() < thunderDueNanos)
		{
			return;
		}
		thunderDueNanos = 0;

		if (!config.thunderSound() || !activeWeather().hasLightning())
		{
			return;
		}

		int id = config.thunderSoundId();
		if (id <= 0)
		{
			// A different roll each strike; the same one every few seconds is a loop.
			id = THUNDER_SOUNDS[(int) (thunderSeed * 7919f) % THUNDER_SOUNDS.length];
		}
		client.playSoundEffect(id);
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
		// Fog weather lays its own mist down on top of whatever the slider asks for.
		mist = Math.max(mist, weatherMist() * 0.9f);
		glUniform1f(uniHeightFog, mist);
		glUniform1f(uniHeightFogEye, HEIGHT_FOG_EYE_OFFSET);
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
		/*
		 * How much moon there is to see by: its phase, whether it is up, and whether the
		 * weather has put it away along with the sun. A full moon lifts the night floor a
		 * little and a new moon lowers it, so the nights are not all the same night.
		 */
		float moon = moonlight(time) * (1f - activeWeather().sunHiding() * weatherIntensity());
		float floor = NIGHT_AMBIENT_FLOOR - 0.06f + 0.12f * moon;
		float ambMul = config.lightAmbientStrength() / 100f * (floor
			+ (1f - floor) * day) * (1f - gloom * 0.5f);
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

		// The moon's own light, from where the moon is. Zero by day, under cloud and at
		// new moon, in which case the shader skips the term.
		float moonMul = config.lightMoonStrength() / 100f * MOONLIGHT_SCALE * moon * night;
		computeMoonDirection(time);
		glUniform3f(uniLightMoonDir, moonDir[0], moonDir[1], moonDir[2]);
		glUniform3f(uniLightMoonColor, MOON_R * moonMul, MOON_G * moonMul, MOON_B * moonMul);
	}

	/**
	 * How much light the moon is giving, 0..1: how much of its face is lit, and how far it
	 * has risen.
	 */
	private float moonlight(LocalTime time)
	{
		if (!config.showMoon())
		{
			return 0f;
		}

		// 0 and 1 are new, 0.5 is full.
		float lit = 0.5f * (1f - (float) Math.cos(2 * Math.PI * moonPhase()));

		computeMoonDirection(time);
		// World Y is negative-up. Eased in over the first few degrees above the horizon.
		float up = Math.max(0f, Math.min(1f, -moonDir[1] / 0.12f));
		return lit * up;
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
		int preview = config.previewMinute();
		if (preview < 0)
		{
			return LocalTime.now();
		}

		// Minutes past midnight rather than a whole hour, so a drag through dawn passes
		// through every colour on the way instead of landing on the hour marks.
		int m = preview % 1440;
		return LocalTime.of(m / 60, m % 60);
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
			: stockDrawDistance();
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

		final boolean ownColours = config.lightColourFromSource();
		for (int i = 0; i < count; ++i)
		{
			// Dimmed towards the edge of the scan patch, so a light that is about to fall
			// out of range is already dark when it goes rather than snapping off.
			float s = strength * lightScanner.fade[i];

			// A light that plainly is not fire - a blue flame, a green lantern - lights the
			// ground its own colour. Everything else is firelight and takes the setting.
			int own = ownColours ? lightScanner.colour[i] : -1;
			if (own >= 0)
			{
				lightColours[i * 3] = (own >> 16 & 0xFF) / 255f * s;
				lightColours[i * 3 + 1] = (own >> 8 & 0xFF) / 255f * s;
				lightColours[i * 3 + 2] = (own & 0xFF) / 255f * s;
			}
			else
			{
				lightColours[i * 3] = tint.getRed() / 255f * s;
				lightColours[i * 3 + 1] = tint.getGreen() / 255f * s;
				lightColours[i * 3 + 2] = tint.getBlue() / 255f * s;
			}
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
		int preview = config.previewMinute();

		// Anchor: the time being previewed, or where the real clock was when we started.
		// Adding elapsed to it keeps the value strictly increasing, so it neither steps at
		// hour boundaries nor jumps at midnight the way seconds-of-day would.
		float base = preview < 0 ? skyClockStartSeconds : (preview % 1440) * 60f;

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

				// 0 is the "no sound" id rather than a real effect, so the sound can be
				// switched on without something arbitrary playing until an id is picked.
				if (config.shootingStarSound() && config.shootingStarSoundId() > 0)
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
		computeMoonDirection(time);
		glUniform3f(uniSkySunDir, sunDir[0], sunDir[1], sunDir[2]);
		glUniform3f(uniSkyMoonDir, moonDir[0], moonDir[1], moonDir[2]);

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
				scheduleThunder(seed);
				float spread = (seed - 0.5f) * 2f * BOLT_SPREAD;
				lightningBearing = -cameraYaw + spread;
			}

			glUniform2f(uniSkyBoltDirXZ,
				(float) Math.sin(lightningBearing), (float) Math.cos(lightningBearing));
		}

		glDrawArrays(GL_TRIANGLES, 0, 3);

	}

	/**
	 * Draws precipitation over the composed scene with normal alpha blending.
	 */
	private void drawWeather(int width, int height)
	{
		glUseProgram(glWeatherProgram);
		glBindVertexArray(vaoSkyHandle);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		// Alpha in the target is left alone: it is the scene's own framebuffer here.
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

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
}
