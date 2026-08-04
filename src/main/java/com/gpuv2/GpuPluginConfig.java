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
import com.gpuv2.config.UIScalingMode;
import com.gpuv2.config.WeatherMode;

/*
 * Section positions are spaced in tens so items can be inserted without renumbering
 * everything after them. Ordering runs from what most people change often (display,
 * performance) to the atmospheric effects, with post-processing last.
 *
 * Key names must not change - they are what settings persist under, and renaming one
 * silently resets it to default.
 */
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

	// ------------------------------------------------------------------ Display

	@ConfigSection(
		name = "Display",
		description = "Draw distance, anti-aliasing and image quality.",
		position = 10
	)
	String displaySection = "displaySection";

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "How far into the distance the world is drawn, in tiles.",
		position = 11,
		section = displaySection
	)
	default int drawDistance()
	{
		return 50;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 12,
		section = displaySection
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 13,
		section = displaySection
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Smooths jagged edges. Higher costs more.",
		position = 14,
		section = displaySection
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@Range(
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Sharpens textures viewed at a steep angle.",
		position = 15,
		section = displaySection
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 16,
		section = displaySection
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove colour banding",
		description = "Smooths out the colour banding present in the CPU renderer.",
		position = 17,
		section = displaySection
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use a brighter texture lighting model.",
		position = 18,
		section = displaySection
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Turn off vanilla's integer vertex snapping. Leaving this unticked "
			+ "keeps the old-school wobble.",
		position = 19,
		section = displaySection
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	// -------------------------------------------------------------- Performance

	@ConfigSection(
		name = "Performance",
		description = "Frame rate and threading.",
		position = 20,
		closedByDefault = true
	)
	String performanceSection = "performanceSection";

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Draw more frames than the game's own 50 fps cap.",
		position = 21,
		section = performanceSection
	)
	default boolean unlockFps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Synchronise frames to the monitor's refresh rate.",
		position = 22,
		section = performanceSection
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@Range(
		min = 1,
		max = 999
	)
	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Frame rate cap when vsync is off and FPS is unlocked.",
		position = 23,
		section = performanceSection
	)
	default int fpsTarget()
	{
		return 60;
	}

	@Range(
		min = 0,
		max = 15
	)
	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
		position = 24,
		section = performanceSection
	)
	default int numThreads()
	{
		return 3;
	}

	// --------------------------------------------------------------------- Sky

	@ConfigSection(
		name = "Sky",
		description = "Sky colour, sun, moon, stars, clouds and fog.",
		position = 30
	)
	String skySection = "skySection";

	@ConfigItem(
		keyName = "skyMode",
		name = "Sky colour",
		description = "Where the sky - and the fog that fades into it - takes its colour from.",
		position = 31,
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
		position = 32,
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
		position = 33,
		section = skySection
	)
	default int previewHour()
	{
		return -1;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "How far from the scene edge fog starts. Fades into the sky colour.",
		position = 34,
		section = skySection
	)
	default int fogDepth()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showSun",
		name = "Sun",
		description = "Draw a sun that arcs east to west across the day.",
		position = 35,
		section = skySection
	)
	default boolean showSun()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMoon",
		name = "Moon",
		description = "Draw a moon, opposite the sun so it is up at night.",
		position = 36,
		section = skySection
	)
	default boolean showMoon()
	{
		return true;
	}

	@ConfigItem(
		keyName = "nightSky",
		name = "Stars at night",
		description = "Draw a starfield after dark.",
		position = 37,
		section = skySection
	)
	default boolean nightSky()
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
		position = 38,
		section = skySection
	)
	default int starDensity()
	{
		return 15;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudAmount",
		name = "Cloud cover",
		description = "How much of the sky is covered by cloud. 0 disables clouds entirely.",
		position = 39,
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
		position = 40,
		section = skySection
	)
	default int cloudOpacity()
	{
		return 70;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "cloudSpeed",
		name = "Cloud speed",
		description = "How fast clouds drift and reshape. 1 is a realistic crawl, "
			+ "3 is a brisk sky.",
		position = 41,
		section = skySection
	)
	default int cloudSpeed()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "aurora",
		name = "Aurora",
		description = "Shimmering curtains low in the northern sky on clear nights. "
			+ "Cloud cover hides it.",
		position = 45,
		section = skySection
	)
	default boolean aurora()
	{
		return false;
	}

	@Range(
		min = 1,
		max = 100
	)
	@ConfigItem(
		keyName = "auroraStrength",
		name = "Aurora strength",
		description = "How bright the aurora is.",
		position = 46,
		section = skySection
	)
	default int auroraStrength()
	{
		return 50;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "heightFog",
		name = "Ground mist",
		description = "Mist pooling in low ground and valleys. 0 disables. Fog weather "
			+ "brings its own on top of this.",
		position = 43,
		section = skySection
	)
	default int heightFog()
	{
		return 0;
	}

	@Range(
		min = 1,
		max = 40
	)
	@ConfigItem(
		keyName = "heightFogDepth",
		name = "Ground mist depth",
		description = "How deep the mist lies, in tiles. Lower keeps it hugging the "
			+ "ground; higher fills valleys.",
		position = 44,
		section = skySection
	)
	default int heightFogDepth()
	{
		return 8;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudShadows",
		name = "Cloud shadows",
		description = "Drift cloud shadows across the world, matching the deck overhead. "
			+ "0 disables.",
		position = 42,
		section = skySection
	)
	default int cloudShadows()
	{
		return 45;
	}

	// ----------------------------------------------------------------- Weather

	@ConfigSection(
		name = "Weather",
		description = "Rain, snow, storms and what they leave on the ground.",
		position = 40
	)
	String weatherSection = "weatherSection";

	@ConfigItem(
		keyName = "weather",
		name = "Weather",
		description = "Precipitation drawn in front of the world and behind the interface. "
			+ "'Automatic' lets it change on its own over time.",
		position = 41,
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
		position = 42,
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
		position = 43,
		section = weatherSection
	)
	default int weatherAmount()
	{
		return 55;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "weatherWind",
		name = "Wind strength",
		description = "How hard the wind blows precipitation sideways, and how much it "
			+ "gusts. 0 makes it fall straight down.",
		position = 44,
		section = weatherSection
	)
	default int weatherWind()
	{
		return 60;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "groundSnow",
		name = "Snow on the ground",
		description = "Settles snow on upward-facing surfaces while it is snowing. "
			+ "0 disables.",
		position = 45,
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
		position = 46,
		section = weatherSection
	)
	default int groundWet()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "lightning",
		name = "Lightning",
		description = "Flashes and bolts of lightning during a storm.",
		position = 47,
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
		position = 48,
		section = weatherSection
	)
	default int lightningFrequency()
	{
		return 1;
	}

	// ---------------------------------------------------------------- Lighting

	@ConfigSection(
		name = "Lighting",
		description = "Ambient and directional light over the game's own shading.",
		position = 50,
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
		position = 51,
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
		position = 52,
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
		position = 53,
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
		position = 54,
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
		position = 55,
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
		position = 56,
		section = lightSection
	)
	default boolean lightFollowsTime()
	{
		return true;
	}

	// ------------------------------------------------------------------- Water

	@ConfigSection(
		name = "Water",
		description = "Animated water surfaces.",
		position = 60,
		closedByDefault = true
	)
	String waterSection = "waterSection";

	@ConfigItem(
		keyName = "waterEnabled",
		name = "Enable water",
		description = "Animate surfaces whose texture id is listed below.",
		position = 61,
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
		position = 62,
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
		position = 63,
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
		position = 64,
		section = waterSection
	)
	default Color waterTint()
	{
		return new Color(0x2E, 0x6B, 0x8A);
	}

	@ConfigItem(
		keyName = "waterTextureIds",
		name = "Water texture ids",
		description = "Comma-separated texture ids treated as water. Stand near water and "
			+ "type ::watertex in chat to find the ids in that area.",
		position = 65,
		section = waterSection
	)
	default String waterTextureIds()
	{
		return "25";
	}

	// --------------------------------------------------------- Post-processing

	@ConfigSection(
		name = "Post-processing",
		description = "Bloom, god rays, colour grading and colourblindness correction.",
		position = 70,
		closedByDefault = true
	)
	String postSection = "postSection";

	@ConfigItem(
		keyName = "bloomEnabled",
		name = "Bloom",
		description = "Bleed a glow out of bright parts of the scene. Costs a few extra "
			+ "render passes.",
		position = 71,
		section = postSection
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
		name = "Bloom threshold",
		description = "How bright a pixel must be before it glows. Lower makes more of "
			+ "the scene glow.",
		position = 72,
		section = postSection
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
		name = "Bloom intensity",
		description = "Strength of the glow added back over the scene.",
		position = 73,
		section = postSection
	)
	default int bloomIntensity()
	{
		return 60;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "godRays",
		name = "God rays",
		description = "Strength of light shafts from the sun. 0 disables. Needs sky "
			+ "colour set to 'Time of day', and only shows when the sun is in view.",
		position = 74,
		section = postSection
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
		name = "God ray threshold",
		description = "How bright a pixel must be to cast a shaft. Lower catches more of "
			+ "the sky.",
		position = 75,
		section = postSection
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
		name = "God ray length",
		description = "How far the shafts stretch from the sun.",
		position = 76,
		section = postSection
	)
	default int godRayLength()
	{
		return 60;
	}

	@Range(
		min = 25,
		max = 250
	)
	@ConfigItem(
		keyName = "gradeGamma",
		name = "Gamma",
		description = "Midtone brightness. 100 is neutral; lower is darker, higher is brighter.",
		position = 77,
		section = postSection
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
		position = 78,
		section = postSection
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
		position = 79,
		section = postSection
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
		position = 80,
		section = postSection
	)
	default int gradeTemperature()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colourblindness correction",
		description = "Adjusts colours to account for colourblindness.",
		position = 81,
		section = postSection
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colourblindness intensity",
		description = "How strongly the colourblindness correction is applied.",
		position = 82,
		section = postSection
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	public enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}
}
