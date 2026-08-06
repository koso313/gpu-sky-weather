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
package com.gpuv2.ext;

import static org.lwjgl.opengl.GL33C.*;

import com.gpuv2.Shader;
import com.gpuv2.ShaderException;
import com.gpuv2.SkyGradient;
import com.gpuv2.template.Template;
import java.time.LocalTime;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.gpu.api.GpuExtension;

/**
 * The gpu-v2 sky, rebuilt as an extension of the core GPU plugin rather than as a
 * renderer of its own.
 *
 * <p>This exists to answer a question rather than to ship: how much of the plugin can
 * live on the extension API, and where does it stop. The sky was the obvious first thing
 * to try, because it already owned its own program, its own uniforms and a vertex shader
 * that needs no buffer at all - nothing about it reaches into the scene shader, so
 * nothing about it should need the renderer to be forked.
 *
 * <p>That turned out to be true. What does not port is written up in PORTING.md.
 */
@Slf4j
public class SkyExtension extends GpuExtension
{
	private static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_frag.glsl");

	private final Client client;
	private final SkyExtensionConfig config;

	private int program;
	private int vao;

	private int uniSkyColor;
	private int uniZenithColor;
	private int uniNight;
	private int uniStarDensity;
	private int uniHalfW;
	private int uniHalfH;
	private int uniCosPitch;
	private int uniSinPitch;
	private int uniCosYaw;
	private int uniSinYaw;
	private int uniStarTime;
	private int uniCloudTime;
	private int uniSunDir;
	private int uniMoonDir;
	private int uniShowMoon;
	private int uniShowSun;
	private int uniSunGlow;
	private int uniSunGlare;
	private int uniMoonGlow;
	private int uniMoonPhase;
	private int uniCloudAmount;
	private int uniCloudOpacity;
	private int uniCloudSeal;
	private int uniSkyDim;
	private int uniSunOcclusion;
	private int uniToneMap;
	private int uniMeteorSamples;
	private int uniCloudOctaves;
	private int uniMeteorActive;
	private int uniMeteorTravel;
	private int uniMeteorPath;
	private int uniAuroraStrength;
	private int uniAuroraTime;
	private int uniBoltStrength;
	private int uniBoltSeed;
	private int uniBoltDirXZ;

	private final float[] sunDir = new float[3];
	private final float[] moonDir = new float[3];

	public SkyExtension(Client client, SkyExtensionConfig config)
	{
		this.client = client;
		this.config = config;
	}

	@Override
	public void onContextCreate()
	{
		try
		{
			Template template = new Template();
			template.addInclude(com.gpuv2.GpuPlugin.class);
			program = SKY_PROGRAM.compile(template);
		}
		catch (ShaderException ex)
		{
			log.error("gpu-v2 sky extension: shader compilation failed", ex);
			program = 0;
			return;
		}

		// The vertex shader builds its triangle from gl_VertexID, so the VAO carries no
		// attributes and exists only because core profile refuses to draw without one.
		vao = glGenVertexArrays();

		uniSkyColor = uniform("skyColor");
		uniZenithColor = uniform("zenithColor");
		uniNight = uniform("night");
		uniStarDensity = uniform("starDensity");
		uniHalfW = uniform("halfW");
		uniHalfH = uniform("halfH");
		uniCosPitch = uniform("cosPitch");
		uniSinPitch = uniform("sinPitch");
		uniCosYaw = uniform("cosYaw");
		uniSinYaw = uniform("sinYaw");
		uniStarTime = uniform("starTime");
		uniCloudTime = uniform("cloudTime");
		uniSunDir = uniform("sunDir");
		uniMoonDir = uniform("moonDir");
		uniShowMoon = uniform("showMoon");
		uniShowSun = uniform("showSun");
		uniSunGlow = uniform("sunGlow");
		uniSunGlare = uniform("sunGlare");
		uniMoonGlow = uniform("moonGlow");
		uniMoonPhase = uniform("moonPhase");
		uniCloudAmount = uniform("cloudAmount");
		uniCloudOpacity = uniform("cloudOpacity");
		uniCloudSeal = uniform("cloudSeal");
		uniSkyDim = uniform("skyDim");
		uniSunOcclusion = uniform("sunOcclusion");
		uniToneMap = uniform("toneMap");
		uniMeteorSamples = uniform("meteorSamples");
		uniCloudOctaves = uniform("cloudOctaves");
		uniMeteorActive = uniform("meteorActive");
		uniMeteorTravel = uniform("meteorTravel");
		uniMeteorPath = uniform("meteorPath");
		uniAuroraStrength = uniform("auroraStrength");
		uniAuroraTime = uniform("auroraTime");
		uniBoltStrength = uniform("boltStrength");
		uniBoltSeed = uniform("boltSeed");
		uniBoltDirXZ = uniform("boltDirXZ");

		log.info("gpu-v2 sky extension: context created, program {}", program);
	}

	@Override
	public void onContextDestroy()
	{
		if (program != 0)
		{
			glDeleteProgram(program);
			program = 0;
		}
		if (vao != 0)
		{
			glDeleteVertexArrays(vao);
			vao = 0;
		}
	}

	@Override
	public boolean drawSkybox()
	{
		if (program == 0)
		{
			return false;
		}

		int viewportWidth = client.getViewportWidth();
		int viewportHeight = client.getViewportHeight();
		float scale = (float) client.getScale();
		if (viewportWidth <= 0 || viewportHeight <= 0 || scale <= 0f)
		{
			return false;
		}

		LocalTime time = LocalTime.now();
		float night = SkyGradient.nightFactorAt(time);
		int horizon = SkyGradient.colorAt(time);
		int zenith = SkyGradient.zenithColorAt(time);

		float dayFraction = (time.toSecondOfDay()) / 86400f;
		computeBodyDirection(dayFraction, sunDir);
		computeBodyDirection(dayFraction + 0.5f, moonDir);

		glUseProgram(program);
		glBindVertexArray(vao);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_BLEND);

		glUniform3f(uniSkyColor, red(horizon), green(horizon), blue(horizon));
		glUniform3f(uniZenithColor, red(zenith), green(zenith), blue(zenith));
		glUniform1f(uniNight, night);
		glUniform1f(uniStarDensity, config.stars() ? config.starDensity() / 1000f : 0f);
		glUniform1f(uniHalfW, viewportWidth / (2f * scale));
		glUniform1f(uniHalfH, viewportHeight / (2f * scale));

		float pitch = (float) (client.getCameraPitch() * Math.PI * 2f / 2048f);
		float yaw = (float) (client.getCameraYaw() * Math.PI * 2f / 2048f);
		glUniform1f(uniCosPitch, (float) Math.cos(pitch));
		glUniform1f(uniSinPitch, (float) Math.sin(pitch));
		glUniform1f(uniCosYaw, (float) Math.cos(yaw));
		glUniform1f(uniSinYaw, (float) Math.sin(yaw));

		float seconds = time.toSecondOfDay();
		glUniform1f(uniStarTime, (System.nanoTime() % 1_000_000_000_000L) / 1e9f);
		glUniform1f(uniCloudTime, seconds);
		glUniform3f(uniSunDir, sunDir[0], sunDir[1], sunDir[2]);
		glUniform3f(uniMoonDir, moonDir[0], moonDir[1], moonDir[2]);
		glUniform1f(uniShowMoon, config.showMoon() ? 1f : 0f);
		glUniform1f(uniShowSun, config.showSun() ? 1f : 0f);
		glUniform1f(uniSunGlow, config.sunGlow() / 100f);
		glUniform1f(uniSunGlare, config.showSun() ? config.sunGlare() / 100f : 0f);
		glUniform1f(uniMoonGlow, config.moonGlow() / 100f);
		glUniform1f(uniMoonPhase, 0.5f);
		glUniform1f(uniCloudAmount, config.cloudAmount() / 100f);
		glUniform1f(uniCloudOpacity, config.cloudOpacity() / 100f);
		glUniform1f(uniCloudSeal, 0f);
		glUniform1f(uniSkyDim, 0f);
		glUniform1f(uniSunOcclusion, 0f);
		glUniform1f(uniToneMap, 0f);
		glUniform1i(uniMeteorSamples, 8);
		glUniform1i(uniCloudOctaves, 5);
		glUniform1f(uniMeteorActive, 0f);
		glUniform1f(uniMeteorTravel, 2f);
		glUniform4f(uniMeteorPath, 0f, 0f, 0f, 0f);
		glUniform1f(uniAuroraStrength, config.aurora() ? config.auroraStrength() / 100f : 0f);
		glUniform1f(uniAuroraTime, seconds);
		glUniform1f(uniBoltStrength, 0f);
		glUniform1f(uniBoltSeed, 0f);
		glUniform2f(uniBoltDirXZ, 0f, 1f);

		glDrawArrays(GL_TRIANGLES, 0, 3);

		// Handed back the way it was found. The renderer draws the scene straight after
		// this and inherits whatever state is left behind.
		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_BLEND);
		glBindVertexArray(0);
		glUseProgram(0);

		return true;
	}

	@Override
	public void onPostDrawToplevel()
	{
		// Nothing yet. Post-processing would go here, but it needs the scene colour
		// texture, which the API does not hand over - see PORTING.md.
	}

	private int uniform(String name)
	{
		return glGetUniformLocation(program, name);
	}

	private static float red(int rgb)
	{
		return (rgb >> 16 & 0xFF) / 255f;
	}

	private static float green(int rgb)
	{
		return (rgb >> 8 & 0xFF) / 255f;
	}

	private static float blue(int rgb)
	{
		return (rgb & 0xFF) / 255f;
	}

	/**
	 * Direction toward a body at the given fraction of a day, east at dawn through west at
	 * dusk. World Y is negative-up, which is why the vertical term is negated.
	 */
	private static void computeBodyDirection(float dayFraction, float[] out)
	{
		double angle = (dayFraction - 0.25) * 2.0 * Math.PI;
		out[0] = (float) Math.cos(angle);
		out[1] = (float) -Math.sin(angle);
		out[2] = 0f;

		float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
		if (len > 0f)
		{
			out[0] /= len;
			out[1] /= len;
			out[2] /= len;
		}
	}
}
