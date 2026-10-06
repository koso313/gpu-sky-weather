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
package com.gpuskyweather;

import static org.lwjgl.opengl.GL33C.*;

import com.gpuskyweather.template.Template;

/**
 * Bloom, god rays, and the anti-aliasing / sharpening / vignette pass, for the extension
 * build.
 *
 * <p>The full plugin ran these while moving the finished scene from its own framebuffer to
 * the window. An extension never sees that step, so they run here instead, in place: the
 * scene is copied out of the renderer's framebuffer into a texture, and each effect is
 * drawn back over the same framebuffer it came from, before the renderer goes on to put it
 * on screen.
 *
 * <p>Everything is confined to the 3D viewport rectangle the renderer has set, which is
 * smaller than its framebuffer whenever the interface takes up part of the window.
 */
final class PostEffects
{
	// Reuse the sky pass's fullscreen-triangle vertex shader.
	private static final Shader BLOOM_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "bloom_frag.glsl");

	private static final Shader GODRAY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "godray_frag.glsl");

	private static final Shader POST_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "post_frag.glsl");

	private int glBloomProgram;
	private int glGodrayProgram;
	private int glPostProgram;

	private int uniBloomSrc;
	private int uniBloomPass;
	private int uniBloomBlurDir;
	private int uniBloomThreshold;
	private int uniBloomIntensity;

	private int uniPostSrc;
	private int uniPostTexel;
	private int uniPostFxaa;
	private int uniPostSharpen;
	private int uniPostVignette;
	private int uniPostExposure;

	private int uniRaySrc;
	private int uniRayPass;
	private int uniRaySunUv;
	private int uniRayThreshold;
	private int uniRayDecay;
	private int uniRayDensity;
	private int uniRayIntensity;
	private int uniRayCount;

	// A single-sampled copy of the scene at viewport size, and two half-size buffers the
	// blurs ping-pong through.
	private int fboResolve;
	private int texResolve;
	private final int[] fboBloom = new int[2];
	private final int[] texBloom = new int[2];
	private int targetW;
	private int targetH;
	private int bloomW;
	private int bloomH;

	void create(Template template) throws ShaderException
	{
		glBloomProgram = BLOOM_PROGRAM.compile(template);
		glGodrayProgram = GODRAY_PROGRAM.compile(template);
		glPostProgram = POST_PROGRAM.compile(template);

		uniBloomSrc = glGetUniformLocation(glBloomProgram, "src");
		uniBloomPass = glGetUniformLocation(glBloomProgram, "bloomPass");
		uniBloomBlurDir = glGetUniformLocation(glBloomProgram, "blurDir");
		uniBloomThreshold = glGetUniformLocation(glBloomProgram, "threshold");
		uniBloomIntensity = glGetUniformLocation(glBloomProgram, "intensity");

		uniPostSrc = glGetUniformLocation(glPostProgram, "src");
		uniPostTexel = glGetUniformLocation(glPostProgram, "texel");
		uniPostFxaa = glGetUniformLocation(glPostProgram, "useFxaa");
		uniPostSharpen = glGetUniformLocation(glPostProgram, "sharpen");
		uniPostVignette = glGetUniformLocation(glPostProgram, "vignette");
		uniPostExposure = glGetUniformLocation(glPostProgram, "exposure");

		uniRaySrc = glGetUniformLocation(glGodrayProgram, "src");
		uniRayPass = glGetUniformLocation(glGodrayProgram, "rayPass");
		uniRaySunUv = glGetUniformLocation(glGodrayProgram, "sunUv");
		uniRayThreshold = glGetUniformLocation(glGodrayProgram, "threshold");
		uniRayDecay = glGetUniformLocation(glGodrayProgram, "decay");
		uniRayDensity = glGetUniformLocation(glGodrayProgram, "density");
		uniRayIntensity = glGetUniformLocation(glGodrayProgram, "intensity");
		uniRayCount = glGetUniformLocation(glGodrayProgram, "rayCount");
	}

	void destroy()
	{
		destroyTargets();

		if (glBloomProgram != 0)
		{
			glDeleteProgram(glBloomProgram);
			glBloomProgram = 0;
		}
		if (glGodrayProgram != 0)
		{
			glDeleteProgram(glGodrayProgram);
			glGodrayProgram = 0;
		}
		if (glPostProgram != 0)
		{
			glDeleteProgram(glPostProgram);
			glPostProgram = 0;
		}
	}

	boolean ready()
	{
		return glBloomProgram != 0 && glGodrayProgram != 0 && glPostProgram != 0;
	}

	/**
	 * Runs whichever effects are switched on over the scene in the bound draw framebuffer.
	 *
	 * <p>Changes the bound program, vertex array, viewport, blend, depth and cull state and
	 * leaves them for the caller to restore; framebuffer bindings and texture unit 0 are put
	 * back here, since nothing else in the extension touches those.
	 *
	 * @param viewport   the renderer's 3D viewport, x, y, width, height
	 * @param sunScreen  the sun's position in 0..1 viewport space, or null when it is not
	 *                   somewhere shafts could come from
	 * @param sunRayFade how much daylight is left for shafts, 0..1
	 * @param exposure   brightness multiplier for the whole picture, 1 for none
	 */
	void run(GpuSkyWeatherConfig config, int vao, int[] viewport, float[] sunScreen, float sunRayFade,
		float exposure)
	{
		final boolean bloom = config.bloomEnabled();
		final boolean rays = config.godRays() > 0 && sunScreen != null;
		final boolean exposed = Math.abs(exposure - 1f) > 0.004f;
		final boolean imagePass = config.fxaa() || config.sharpen() > 0 || config.vignette() > 0 || exposed;
		if (!bloom && !rays && !imagePass)
		{
			return;
		}

		final int vx = viewport[0];
		final int vy = viewport[1];
		final int w = viewport[2];
		final int h = viewport[3];
		if (w <= 0 || h <= 0)
		{
			return;
		}

		final int sceneFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		final int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		glActiveTexture(GL_TEXTURE0);
		final int unit0 = glGetInteger(GL_TEXTURE_BINDING_2D);

		ensureTargets(w, h);

		/*
		 * The scene framebuffer is multisampled whenever anti-aliasing is on, and a
		 * multisampled buffer cannot be sampled as a texture. Blitting resolves it - and a
		 * multisampled blit cannot rescale, which is why the copy is exactly viewport sized.
		 */
		glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboResolve);
		glBlitFramebuffer(vx, vy, vx + w, vy + h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glBindVertexArray(vao);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);

		/*
		 * God rays go first because both effects ping-pong through the same two half-size
		 * buffers. Rays blur and composite; only then does bloom reuse those buffers. Both
		 * read the resolve, which neither of them writes.
		 */
		if (rays)
		{
			renderGodRays(config, sunScreen);
		}

		if (imagePass)
		{
			// Redraws the scene through FXAA, sharpening, vignette and exposure, over itself.
			glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
			glViewport(vx, vy, w, h);
			glUseProgram(glPostProgram);
			glBindTexture(GL_TEXTURE_2D, texResolve);
			glUniform1i(uniPostSrc, 0);
			glUniform2f(uniPostTexel, 1f / w, 1f / h);
			glUniform1f(uniPostFxaa, config.fxaa() ? 1f : 0f);
			glUniform1f(uniPostSharpen, config.sharpen() / 100f * 0.5f);
			glUniform1f(uniPostVignette, config.vignette() / 100f * 0.8f);
			glUniform1f(uniPostExposure, exposed ? exposure : 1f);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}

		if (rays)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
			glViewport(vx, vy, w, h);
			glUseProgram(glGodrayProgram);
			additiveBlend();
			glBindTexture(GL_TEXTURE_2D, texBloom[0]);
			glUniform1i(uniRaySrc, 0);
			// Pass 1 with no decay and no density degenerates to a plain read, which is
			// what compositing needs.
			glUniform1i(uniRayPass, 1);
			glUniform2f(uniRaySunUv, sunScreen[0], sunScreen[1]);
			glUniform1f(uniRayDecay, 1f);
			glUniform1f(uniRayDensity, 0f);
			glUniform1f(uniRayIntensity, config.godRays() / 100f * sunRayFade);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDisable(GL_BLEND);
		}

		if (bloom)
		{
			renderBloom(config);

			glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
			glViewport(vx, vy, w, h);
			additiveBlend();
			glBindTexture(GL_TEXTURE_2D, texBloom[0]);
			glUniform1i(uniBloomSrc, 0);
			glUniform1i(uniBloomPass, 2);
			glUniform1f(uniBloomIntensity, config.bloomIntensity() / 100f);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDisable(GL_BLEND);
		}

		glBindTexture(GL_TEXTURE_2D, unit0);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFbo);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
		glViewport(vx, vy, w, h);
	}

	/** Additive, leaving the target's alpha alone: it is the renderer's own framebuffer. */
	private static void additiveBlend()
	{
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE);
	}

	/**
	 * Extracts light near the sun and smears it radially outward, leaving the result in
	 * texBloom[0].
	 */
	private void renderGodRays(GpuSkyWeatherConfig config, float[] sunScreen)
	{
		glUseProgram(glGodrayProgram);
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
		int quality = config.effectQuality();
		glUniform1i(uniRayCount, quality >= 3 ? 24 : quality == 2 ? 16 : 10);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	/**
	 * Extracts the bright parts of the scene and blurs them into texBloom[0]. Leaves the
	 * bloom program bound for the composite that follows.
	 */
	private void renderBloom(GpuSkyWeatherConfig config)
	{
		glUseProgram(glBloomProgram);
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
	}

	/** Reallocates the targets when the viewport changes size. Binds texture unit 0. */
	private void ensureTargets(int width, int height)
	{
		if (fboResolve != 0 && width == targetW && height == targetH)
		{
			return;
		}

		destroyTargets();

		targetW = width;
		targetH = height;
		bloomW = Math.max(1, width / 2);
		bloomH = Math.max(1, height / 2);

		texResolve = newTexture(width, height);
		fboResolve = newFramebuffer(texResolve);

		for (int i = 0; i < 2; ++i)
		{
			texBloom[i] = newTexture(bloomW, bloomH);
			fboBloom[i] = newFramebuffer(texBloom[i]);
		}
	}

	private static int newTexture(int width, int height)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// Clamped so the blur doesn't wrap bright pixels around to the opposite edge.
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		return tex;
	}

	private static int newFramebuffer(int tex)
	{
		int fbo = glGenFramebuffers();
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
		return fbo;
	}

	private void destroyTargets()
	{
		if (fboResolve != 0)
		{
			glDeleteFramebuffers(fboResolve);
			fboResolve = 0;
		}
		if (texResolve != 0)
		{
			glDeleteTextures(texResolve);
			texResolve = 0;
		}
		for (int i = 0; i < 2; ++i)
		{
			if (fboBloom[i] != 0)
			{
				glDeleteFramebuffers(fboBloom[i]);
				fboBloom[i] = 0;
			}
			if (texBloom[i] != 0)
			{
				glDeleteTextures(texBloom[i]);
				texBloom[i] = 0;
			}
		}
		targetW = 0;
		targetH = 0;
	}
}
