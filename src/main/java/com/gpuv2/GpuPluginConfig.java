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

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import static com.gpuv2.GpuPlugin.MAX_DISTANCE;
import static com.gpuv2.GpuPlugin.MAX_FOG_DEPTH;
import com.gpuv2.config.AntiAliasingMode;
import com.gpuv2.config.ColorBlindMode;
import com.gpuv2.config.GraphicsPreset;
import com.gpuv2.config.SkyMode;
import com.gpuv2.config.WeatherMode;
import com.gpuv2.config.UIScalingMode;

@ConfigGroup(GpuPluginConfig.GROUP)
public interface GpuPluginConfig extends Config
{
	String GROUP = "gpuv2";

	@ConfigItem(
		keyName = "preset",
		name = "Preset",
		description = "Quality presets. Only Custom is implemented - the others are "
			+ "placeholders and currently change nothing.",
		position = 0
	)
	default GraphicsPreset preset()
	{
		return GraphicsPreset.CUSTOM;
	}

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance.",
		position = 1
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 2
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 1
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Smooths out the color banding that is present in the CPU renderer.",
		position = 2
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Configures the anti-aliasing mode.",
		position = 3
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 4
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "Distance from the scene edge the fog starts.",
		position = 5
	)
	default int fogDepth()
	{
		return 0;
	}

	@ConfigSection(
		name = "Weather",
		description = "Rain and snow drawn over the scene.",
		position = 205,
		closedByDefault = true
	)
	String weatherSection = "weatherSection";

	@ConfigItem(
		keyName = "weather",
		name = "Weather",
		description = "Precipitation drawn in front of the world and behind the interface. "
			+ "'Automatic' lets it change on its own over time.",
		position = 205,
		section = weatherSection
	)
	default WeatherMode weather()
	{
		return WeatherMode.OFF;
	}

	@Range(
		min = 1,
		max = 60
	)
	@ConfigItem(
		keyName = "autoWeatherPeriod",
		name = "Automatic: changes every",
		description = "Minutes each spell of weather lasts on 'Automatic', including the "
			+ "time it spends building and easing off.",
		position = 206,
		section = weatherSection
	)
	default int autoWeatherPeriod()
	{
		return 12;
	}

	@Range(
		min = 1,
		max = 100
	)
	@ConfigItem(
		keyName = "weatherAmount",
		name = "Amount",
		description = "How heavy the rain or snow is.",
		position = 207,
		section = weatherSection
	)
	default int weatherAmount()
	{
		return 55;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "groundSnow",
		name = "Snow on the ground",
		description = "Settles snow on upward-facing surfaces while it is snowing. "
			+ "0 disables.",
		position = 209,
		section = weatherSection
	)
	default int groundSnow()
	{
		return 70;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "groundWet",
		name = "Wet ground and puddles",
		description = "Darkens the ground and pools reflective puddles while it is "
			+ "raining. 0 disables.",
		position = 210,
		section = weatherSection
	)
	default int groundWet()
	{
		return 70;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "weatherWind",
		name = "Wind strength",
		description = "How hard the wind blows precipitation sideways, and how much it "
			+ "gusts. 0 makes it fall straight down.",
		position = 208,
		section = weatherSection
	)
	default int weatherWind()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "lightning",
		name = "Lightning",
		description = "Flashes of lightning during a storm.",
		position = 208,
		section = weatherSection
	)
	default boolean lightning()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "lightningFrequency",
		name = "Lightning frequency",
		description = "How often lightning strikes during a storm. 1 is occasional, "
			+ "2 is frequent, 3 is near-constant.",
		position = 211,
		section = weatherSection
	)
	default int lightningFrequency()
	{
		return 1;
	}

	@ConfigSection(
		name = "Bloom",
		description = "Glow around bright areas.",
		position = 210,
		closedByDefault = true
	)
	String bloomSection = "bloomSection";

	@ConfigItem(
		keyName = "bloomEnabled",
		name = "Enable bloom",
		description = "Bleed a glow out of bright parts of the scene. Costs a few extra "
			+ "render passes.",
		position = 211,
		section = bloomSection
	)
	default boolean bloomEnabled()
	{
		return false;
	}

	@Range(
		max = 99
	)
	@ConfigItem(
		keyName = "bloomThreshold",
		name = "Threshold",
		description = "How bright a pixel must be before it glows. Lower makes more of "
			+ "the scene glow.",
		position = 212,
		section = bloomSection
	)
	default int bloomThreshold()
	{
		return 65;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "bloomIntensity",
		name = "Intensity",
		description = "Strength of the glow added back over the scene.",
		position = 213,
		section = bloomSection
	)
	default int bloomIntensity()
	{
		return 60;
	}

	@ConfigSection(
		name = "God rays",
		description = "Light shafts radiating from the sun.",
		position = 215,
		closedByDefault = true
	)
	String godRaySection = "godRaySection";

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "godRays",
		name = "God rays",
		description = "Strength of light shafts from the sun. 0 disables. Needs sky "
			+ "colour set to 'Time of day', and only shows when the sun is in view.",
		position = 216,
		section = godRaySection
	)
	default int godRays()
	{
		return 0;
	}

	@Range(
		max = 99
	)
	@ConfigItem(
		keyName = "godRayThreshold",
		name = "Threshold",
		description = "How bright a pixel must be to cast a shaft. Lower catches more of "
			+ "the sky.",
		position = 217,
		section = godRaySection
	)
	default int godRayThreshold()
	{
		return 55;
	}

	@Range(
		min = 5,
		max = 150
	)
	@ConfigItem(
		keyName = "godRayLength",
		name = "Length",
		description = "How far the shafts stretch from the sun.",
		position = 218,
		section = godRaySection
	)
	default int godRayLength()
	{
		return 60;
	}

	@ConfigSection(
		name = "Water",
		description = "Animated water surfaces.",
		position = 225,
		closedByDefault = true
	)
	String waterSection = "waterSection";

	@ConfigItem(
		keyName = "waterEnabled",
		name = "Enable water",
		description = "Animate surfaces whose texture id is listed below.",
		position = 226,
		section = waterSection
	)
	default boolean waterEnabled()
	{
		return false;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "waterStrength",
		name = "Strength",
		description = "How much the water effect replaces the original surface.",
		position = 227,
		section = waterSection
	)
	default int waterStrength()
	{
		return 70;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "waterChoppiness",
		name = "Choppiness",
		description = "Size of the ripples. Low is glassy, high is rough.",
		position = 228,
		section = waterSection
	)
	default int waterChoppiness()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "waterTint",
		name = "Water tint",
		description = "Colour of the water body, under the sky reflection.",
		position = 229,
		section = waterSection
	)
	default Color waterTint()
	{
		return new Color(0x2E, 0x6B, 0x8A);
	}

	@ConfigItem(
		keyName = "waterTextureIds",
		name = "Water texture ids",
		description = "Comma-separated texture ids treated as water. Stand on water and "
			+ "type ::watertex in chat to find the id for a tile.",
		position = 230,
		section = waterSection
	)
	default String waterTextureIds()
	{
		return "1";
	}

	@ConfigSection(
		name = "Lighting",
		description = "Ambient and directional light over the game's own shading.",
		position = 250,
		closedByDefault = true
	)
	String lightSection = "lightSection";

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "lightStrength",
		name = "Lighting strength",
		description = "How strongly the lighting below is applied. 0 disables it entirely.",
		position = 251,
		section = lightSection
	)
	default int lightStrength()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "lightAmbientColor",
		name = "Ambient colour",
		description = "Colour of the light filling shadowed faces.",
		position = 252,
		section = lightSection
	)
	default Color lightAmbientColor()
	{
		return new Color(0xB0, 0xC0, 0xD8);
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "lightAmbientStrength",
		name = "Ambient strength",
		description = "Brightness of the ambient fill.",
		position = 253,
		section = lightSection
	)
	default int lightAmbientStrength()
	{
		return 75;
	}

	@ConfigItem(
		keyName = "lightSunColor",
		name = "Sun colour",
		description = "Colour of the directional light.",
		position = 254,
		section = lightSection
	)
	default Color lightSunColor()
	{
		return new Color(0xFF, 0xF0, 0xD0);
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "lightSunStrength",
		name = "Sun strength",
		description = "Brightness of the directional light on faces angled toward the sun.",
		position = 255,
		section = lightSection
	)
	default int lightSunStrength()
	{
		return 45;
	}

	@ConfigItem(
		keyName = "lightFollowsTime",
		name = "Follow time of day",
		description = "Dim the world at night in step with the sky. Only applies when sky "
			+ "colour is set to 'Time of day'.",
		position = 256,
		section = lightSection
	)
	default boolean lightFollowsTime()
	{
		return true;
	}

	@ConfigSection(
		name = "Retro / performance",
		description = "Old-school stylisation. Approximates the look - it does not load "
			+ "pre-2007 model data, which isn't in the modern cache.",
		position = 300,
		closedByDefault = true
	)
	String retroSection = "retroSection";

	@ConfigItem(
		keyName = "retroNoTextures",
		name = "Disable textures",
		description = "Render flat vertex colours instead of textures. Also a real "
			+ "performance win on weak hardware.",
		position = 301,
		section = retroSection
	)
	default boolean retroNoTextures()
	{
		return false;
	}

	@Range(
		max = 32
	)
	@ConfigItem(
		keyName = "retroPosterize",
		name = "Posterise",
		description = "Colour levels per channel, for a low-colour-depth look. "
			+ "0 or 1 disables; lower values are chunkier.",
		position = 302,
		section = retroSection
	)
	default int retroPosterize()
	{
		return 0;
	}

	@ConfigSection(
		name = "Colour grading",
		description = "Final image adjustments.",
		position = 200,
		closedByDefault = true
	)
	String gradeSection = "gradeSection";

	@Range(
		min = 25,
		max = 250
	)
	@ConfigItem(
		keyName = "gradeGamma",
		name = "Gamma",
		description = "Midtone brightness. 100 is neutral; lower is darker, higher is brighter.",
		position = 201,
		section = gradeSection
	)
	default int gradeGamma()
	{
		return 100;
	}

	@Range(
		min = 25,
		max = 250
	)
	@ConfigItem(
		keyName = "gradeContrast",
		name = "Contrast",
		description = "Separation between lights and darks. 100 is neutral.",
		position = 202,
		section = gradeSection
	)
	default int gradeContrast()
	{
		return 100;
	}

	@Range(
		max = 250
	)
	@ConfigItem(
		keyName = "gradeSaturation",
		name = "Saturation",
		description = "Colour intensity. 100 is neutral, 0 is greyscale.",
		position = 203,
		section = gradeSection
	)
	default int gradeSaturation()
	{
		return 100;
	}

	@Range(
		min = -100,
		max = 100
	)
	@ConfigItem(
		keyName = "gradeTemperature",
		name = "Temperature",
		description = "Colour warmth. 0 is neutral, positive is warmer, negative is cooler.",
		position = 204,
		section = gradeSection
	)
	default int gradeTemperature()
	{
		return 0;
	}

	@ConfigSection(
		name = "Sky",
		description = "Sky and fog colour.",
		position = 100
	)
	String skySection = "skySection";

	@ConfigItem(
		keyName = "skyMode",
		name = "Sky colour",
		description = "Where the sky - and the fog that fades into it - takes its colour from.",
		position = 101,
		section = skySection
	)
	default SkyMode skyMode()
	{
		return SkyMode.GAME;
	}

	@ConfigItem(
		keyName = "skyColor",
		name = "Custom colour",
		description = "Sky and fog colour, used when sky colour is set to 'Custom colour'.",
		position = 102,
		section = skySection
	)
	default Color skyColor()
	{
		return new Color(0x87, 0xCE, 0xEB);
	}

	@Range(
		min = -1,
		max = 23
	)
	@ConfigItem(
		keyName = "previewHour",
		name = "Preview hour",
		description = "For 'Time of day': force a specific hour (0-23) to preview it. "
			+ "-1 uses your real local time.",
		position = 103,
		section = skySection
	)
	default int previewHour()
	{
		return -1;
	}

	@ConfigItem(
		keyName = "nightSky",
		name = "Stars at night",
		description = "Draw a starfield after dark. Only applies when sky colour is 'Time of day'.",
		position = 104,
		section = skySection
	)
	default boolean nightSky()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMoon",
		name = "Moon",
		description = "Draw a moon that tracks the time of day.",
		position = 105,
		section = skySection
	)
	default boolean showMoon()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 100
	)
	@ConfigItem(
		keyName = "starDensity",
		name = "Star density",
		description = "How many stars fill the sky.",
		position = 106,
		section = skySection
	)
	default int starDensity()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "showSun",
		name = "Sun",
		description = "Draw a sun that arcs east to west across the day.",
		position = 107,
		section = skySection
	)
	default boolean showSun()
	{
		return true;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudAmount",
		name = "Cloud cover",
		description = "How much of the sky is covered by cloud. 0 disables clouds entirely.",
		position = 108,
		section = skySection
	)
	default int cloudAmount()
	{
		return 45;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudOpacity",
		name = "Cloud strength",
		description = "How solid the clouds look against the sky.",
		position = 109,
		section = skySection
	)
	default int cloudOpacity()
	{
		return 70;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudShadows",
		name = "Cloud shadows",
		description = "Drift cloud shadows across the world, matching the deck overhead. "
			+ "0 disables.",
		position = 110,
		section = skySection
	)
	default int cloudShadows()
	{
		return 45;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Configures the anisotropic filtering level.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Adjusts colors to account for colorblindness.",
		position = 8
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Strength of the colorblindness correction effect.",
		position = 9
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use old texture lighting method which results in brighter game textures.",
		position = 10
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 11
	)
	default boolean unlockFps()
	{
		return true;
	}

	public enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 12
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 13
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Removes vertex snapping from most animations.",
		position = 14
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
		position = 20
	)
	@Range(min = 0, max = 15)
	default int numThreads()
	{
		return 3;
	}
}
