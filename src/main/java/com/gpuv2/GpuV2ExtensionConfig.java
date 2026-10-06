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
import static com.gpuv2.GpuPlugin.MAX_FOG_DEPTH;
import com.gpuv2.config.GraphicsPreset;
import com.gpuv2.config.SkyMode;
import com.gpuv2.config.WeatherMode;

/*
 * The settings of the full plugin that the extension build can honour, under the same
 * group and key names - so everything already tuned there carries straight over, and the
 * two builds stay in step while both exist.
 *
 * Left out: whatever belongs to the renderer itself (draw distance, anti-aliasing, frame
 * rate, threads, scaling), which the core GPU plugin now owns, and what has no hook yet.
 *
 * Key names must not change - they are what settings persist under.
 */
@ConfigGroup(GpuV2ExtensionConfig.GROUP)
public interface GpuV2ExtensionConfig extends Config
{
	String GROUP = "gpuv2";

	@ConfigItem(
		keyName = "preset",
		name = "Preset",
		description = "Default leaves the GPU plugin's picture untouched. Custom applies "
			+ "everything below. Switching never changes your settings.",
		position = 0
	)
	default GraphicsPreset preset()
	{
		return GraphicsPreset.CUSTOM;
	}

	// ------------------------------------------------------------------ Display

	@ConfigSection(
		name = "Display",
		description = "Tone mapping and the finishing passes over the picture. Draw distance "
			+ "and anti-aliasing are set in the GPU plugin.",
		position = 10
	)
	String displaySection = "displaySection";

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "toneMapping",
		name = "Tone mapping",
		description = "Rolls off bright areas so they keep detail instead of clipping to white. "
			+ "Darkens midtones slightly. 0 off, max 100.",
		position = 136,
		section = displaySection
	)
	default int toneMapping()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "fxaa",
		name = "FXAA",
		description = "Smooths the edges of clouds, aurora and lightning, which the GPU "
			+ "plugin's anti-aliasing cannot reach.",
		position = 151,
		section = displaySection
	)
	default boolean fxaa()
	{
		return false;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "sharpen",
		name = "Sharpening",
		description = "Crispens edges and texture detail. Too much puts outlines on edges. 0 "
			+ "off, max 100.",
		position = 152,
		section = displaySection
	)
	default int sharpen()
	{
		return 10;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "vignette",
		name = "Vignette",
		description = "Darkens the corners of the 3D view. 0 off, max 100.",
		position = 153,
		section = displaySection
	)
	default int vignette()
	{
		return 0;
	}

	// -------------------------------------------------------------- Performance

	@ConfigSection(
		name = "Performance",
		description = "The performance overlay, and switches for the cost of this plugin's own "
			+ "effects.",
		position = 20,
		closedByDefault = true
	)
	String performanceSection = "performanceSection";

	@ConfigItem(
		keyName = "perfOverlay",
		name = "Performance overlay",
		description = "Shows the current frame rate in the corner of the view. The options "
			+ "below add lines to it.",
		position = 30,
		section = performanceSection
	)
	default boolean perfOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "perfShowFrameTime",
		name = "  Frame time",
		description = "Adds average milliseconds per frame.",
		position = 31,
		section = performanceSection
	)
	default boolean perfShowFrameTime()
	{
		return true;
	}

	@ConfigItem(
		keyName = "perfShowAverage",
		name = "  Average FPS",
		description = "Adds the mean frame rate over the last 1000 frames.",
		position = 32,
		section = performanceSection
	)
	default boolean perfShowAverage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "perfShowLows",
		name = "  1% lows",
		description = "Adds the frame rate of the slowest 1% of the last 1000 frames, which is "
			+ "what stutter feels like.",
		position = 33,
		section = performanceSection
	)
	default boolean perfShowLows()
	{
		return true;
	}

	@ConfigItem(
		keyName = "perfShowGpu",
		name = "  GPU temperature",
		description = "Adds GPU temperature and load. NVIDIA cards only: it runs nvidia-smi "
			+ "every two seconds while on.",
		position = 34,
		section = performanceSection
	)
	default boolean perfShowGpu()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "effectQuality",
		name = "Effect quality",
		description = "Detail of god rays, clouds and shooting star trails. 1 cheapest, 3 best.",
		position = 26,
		section = performanceSection
	)
	default int effectQuality()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "postProcessing",
		name = "Post-processing",
		description = "Master switch for bloom, god rays, FXAA, sharpening, vignette and auto "
			+ "exposure.",
		position = 27,
		section = performanceSection
	)
	default boolean postProcessing()
	{
		return true;
	}

	// --------------------------------------------------------------------- Sky

	@ConfigSection(
		name = "Sky",
		description = "Sky colour and time of day.",
		position = 30
	)
	String skySection = "skySection";

	@ConfigSection(
		name = "Fog",
		description = "Distance fog, ground mist and haze.",
		position = 32
	)
	String fogSection = "fogSection";

	@ConfigItem(
		keyName = "fogEnabled",
		name = "Enable fog",
		description = "Master switch for fog depth and ground mist. Fog weather still brings "
			+ "its own.",
		position = 320,
		section = fogSection
	)
	default boolean fogEnabled()
	{
		return true;
	}

	@ConfigSection(
		name = "Sun and moon",
		description = "The sun and moon in the sky.",
		position = 34
	)
	String sunMoonSection = "sunMoonSection";

	@ConfigSection(
		name = "Stars and aurora",
		description = "Stars, shooting stars and the aurora. Night only, and hidden by cloud.",
		position = 38
	)
	String starSection = "starSection";

	@ConfigSection(
		name = "Clouds",
		description = "Cloud cover, drift and shadows.",
		position = 42
	)
	String cloudSection = "cloudSection";

	@ConfigItem(
		keyName = "skyMode",
		name = "Sky colour",
		description = "Where the sky and fog colour come from. Game default uses the area's "
			+ "own, Custom colour uses the one below, Time of day follows your clock "
			+ "through dawn, day, dusk and night.",
		position = 31,
		section = skySection
	)
	default SkyMode skyMode()
	{
		return SkyMode.TIME_OF_DAY;
	}

	@ConfigItem(
		keyName = "skyColor",
		name = "Custom colour",
		description = "Sky and fog colour when Sky colour is set to Custom colour.",
		position = 32,
		section = skySection
	)
	default Color skyColor()
	{
		return new Color(0x87, 0xCE, 0xEB);
	}

	@Range(
		min = -1,
		max = 1439
	)
	@ConfigItem(
		keyName = "previewMinute",
		name = "Preview time",
		description = "Holds the sky at one time of day, in minutes past midnight: 360 is "
			+ "06:00, 720 midday, 1140 19:00. -1 follows your clock, max 1439.",
		position = 33,
		section = skySection
	)
	default int previewMinute()
	{
		return -1;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "aerialPerspective",
		name = "Aerial perspective",
		description = "Tints distant scenery with the sky colour, building gradually with "
			+ "distance. 0 off, max 100.",
		position = 324,
		section = fogSection
	)
	default int aerialPerspective()
	{
		return 0;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "How far in from the edge of the drawn world the fog reaches, in tiles. "
			+ "Fades to the sky colour. 0 off, max 100.",
		position = 321,
		section = fogSection
	)
	default int fogDepth()
	{
		return 24;
	}

	@ConfigItem(
		keyName = "showSun",
		name = "Sun",
		description = "Draws a sun that rises in the east and sets in the west. Weather hides "
			+ "it.",
		position = 340,
		section = sunMoonSection
	)
	default boolean showSun()
	{
		return true;
	}

	@Range(
		max = 250
	)
	@ConfigItem(
		keyName = "sunGlow",
		name = "Sun brightness",
		description = "Brightness of the sun's disc. 0 to 250.",
		position = 341,
		section = sunMoonSection
	)
	default int sunGlow()
	{
		return 100;
	}

	@Range(
		max = 250
	)
	@ConfigItem(
		keyName = "sunGlare",
		name = "Sun glare",
		description = "Halo and streaks around the sun. 0 off, max 250.",
		position = 342,
		section = sunMoonSection
	)
	default int sunGlare()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "showMoon",
		name = "Moon",
		description = "Draws a moon, up through the night.",
		position = 343,
		section = sunMoonSection
	)
	default boolean showMoon()
	{
		return true;
	}

	@ConfigItem(
		keyName = "moonPhases",
		name = "Moon phases",
		description = "Lets the moon wax and wane. Off keeps it full.",
		position = 345,
		section = sunMoonSection
	)
	default boolean moonPhases()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 30
	)
	@ConfigItem(
		keyName = "moonCycleDays",
		name = "Moon cycle length",
		description = "Days for one full cycle of phases. 29 matches the real moon, lower "
			+ "changes faster. 1 to 30.",
		position = 346,
		section = sunMoonSection
	)
	default int moonCycleDays()
	{
		return 29;
	}

	@Range(
		min = -1,
		max = 100
	)
	@ConfigItem(
		keyName = "moonPhasePreview",
		name = "Force moon phase",
		description = "Pins the moon's phase: 0 new, 50 full, 100 new again. -1 follows the "
			+ "cycle.",
		position = 347,
		section = sunMoonSection
	)
	default int moonPhasePreview()
	{
		return -1;
	}

	@Range(
		max = 250
	)
	@ConfigItem(
		keyName = "moonGlow",
		name = "Moon brightness",
		description = "Brightness of the moon's disc and halo. 0 to 250.",
		position = 344,
		section = sunMoonSection
	)
	default int moonGlow()
	{
		return 110;
	}

	@ConfigItem(
		keyName = "nightSky",
		name = "Stars at night",
		description = "Draws stars after dark. Cloud and weather hide them.",
		position = 380,
		section = starSection
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
		description = "How many stars fill the sky. 1 to 100.",
		position = 381,
		section = starSection
	)
	default int starDensity()
	{
		return 100;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudAmount",
		name = "Cloud cover",
		description = "How much of the sky is cloud. Weather adds more. 0 off, max 100.",
		position = 420,
		section = cloudSection
	)
	default int cloudAmount()
	{
		return 50;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudOpacity",
		name = "Cloud strength",
		description = "How solid the clouds look, from thin haze to opaque. 0 to 100.",
		position = 421,
		section = cloudSection
	)
	default int cloudOpacity()
	{
		return 100;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "cloudSpeed",
		name = "Cloud speed",
		description = "How fast clouds drift and reshape. 1 slow, 3 brisk.",
		position = 422,
		section = cloudSection
	)
	default int cloudSpeed()
	{
		return 3;
	}

	@Range(
		max = 2
	)
	@ConfigItem(
		keyName = "shootingStars",
		name = "Shooting stars",
		description = "Meteors crossing the night sky. 0 off, 1 rare, 2 constant.",
		position = 385,
		section = starSection
	)
	default int shootingStars()
	{
		return 2;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "shootingStarSpeed",
		name = "Shooting star speed",
		description = "How fast shooting stars cross the sky. 1 slow, 3 fast.",
		position = 386,
		section = starSection
	)
	default int shootingStarSpeed()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "shootingStarSound",
		name = "Shooting star sound",
		description = "Plays a sound when a shooting star appears.",
		position = 387,
		section = starSection
	)
	default boolean shootingStarSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shootingStarSoundId",
		name = "Sound effect id",
		description = "In-game sound effect id to play. 0 is silent. 3924 is a coin tinkle, "
			+ "3925 a bell.",
		position = 388,
		section = starSection
	)
	default int shootingStarSoundId()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "aurora",
		name = "Aurora",
		description = "Shimmering curtains low in the northern sky on clear nights.",
		position = 382,
		section = starSection
	)
	default boolean aurora()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 100
	)
	@ConfigItem(
		keyName = "auroraStrength",
		name = "Aurora strength",
		description = "Brightness of the aurora. 1 to 100.",
		position = 383,
		section = starSection
	)
	default int auroraStrength()
	{
		return 100;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "auroraSpeed",
		name = "Aurora speed",
		description = "How fast the aurora moves. 1 slow, 3 restless.",
		position = 384,
		section = starSection
	)
	default int auroraSpeed()
	{
		return 3;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "heightFog",
		name = "Ground mist",
		description = "Mist that pools in low ground. 0 off, max 100.",
		position = 322,
		section = fogSection
	)
	default int heightFog()
	{
		return 50;
	}

	@Range(
		min = 1,
		max = 40
	)
	@ConfigItem(
		keyName = "heightFogDepth",
		name = "Ground mist depth",
		description = "How deep the mist lies, in tiles. Low hugs the ground, high fills "
			+ "valleys. 1 to 40.",
		position = 323,
		section = fogSection
	)
	default int heightFogDepth()
	{
		return 40;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "cloudShadows",
		name = "Cloud shadows",
		description = "Cloud shadows drifting over the ground by day. 0 off, max 100.",
		position = 423,
		section = cloudSection
	)
	default int cloudShadows()
	{
		return 60;
	}

	// ----------------------------------------------------------------- Weather

	@ConfigSection(
		name = "Weather",
		description = "Rain, snow, storms and fog, and what they leave on the ground.",
		position = 40
	)
	String weatherSection = "weatherSection";

	@ConfigItem(
		keyName = "weather",
		name = "Weather",
		description = "The weather. Each condition changes the sky and light to match. Off and "
			+ "Sunny are both clear, and Automatic changes it over time.",
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
		description = "Minutes each spell of Automatic weather lasts, including building up and "
			+ "easing off. 1 to 60.",
		position = 42,
		section = weatherSection
	)
	default int autoWeatherPeriod()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "weatherFollowsRegion",
		name = "Automatic: follow the region",
		description = "Makes Automatic weather suit the area: dry desert, snow in the mountains "
			+ "and far north, wet and foggy Morytania, rain but no snow on Karamja.",
		position = 43,
		section = weatherSection
	)
	default boolean weatherFollowsRegion()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 100
	)
	@ConfigItem(
		keyName = "weatherAmount",
		name = "Amount",
		description = "How heavily rain or snow falls. 1 to 100.",
		position = 44,
		section = weatherSection
	)
	default int weatherAmount()
	{
		return 93;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "weatherWind",
		name = "Wind strength",
		description = "How far the wind blows rain and snow sideways. 0 falls straight down, "
			+ "max 200.",
		position = 45,
		section = weatherSection
	)
	default int weatherWind()
	{
		return 58;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "groundSnow",
		name = "Snow on the ground",
		description = "Snow settling on the ground and scenery while it snows. 0 off, max 100.",
		position = 46,
		section = weatherSection
	)
	default int groundSnow()
	{
		return 100;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "groundWet",
		name = "Wet ground and puddles",
		description = "Darker wet ground and puddles while it rains. 0 off, max 100.",
		position = 47,
		section = weatherSection
	)
	default int groundWet()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "lightning",
		name = "Lightning",
		description = "Lightning bolts and flashes during a storm, lighting the world as well "
			+ "as the sky.",
		position = 48,
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
		description = "How often lightning strikes. 1 occasional, 3 near-constant.",
		position = 49,
		section = weatherSection
	)
	default int lightningFrequency()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "thunderSound",
		name = "Thunder",
		description = "Plays thunder after a lightning strike, no more often than the gap "
			+ "below. Uses your in-game sound effect volume.",
		position = 50,
		section = weatherSection
	)
	default boolean thunderSound()
	{
		return false;
	}

	@Range(
		min = 5,
		max = 120
	)
	@ConfigItem(
		keyName = "thunderGap",
		name = "Thunder gap",
		description = "Shortest time between thunderclaps, in seconds. Lightning strikes inside "
			+ "the gap stay silent. 5 to 120.",
		position = 51,
		section = weatherSection
	)
	default int thunderGap()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "thunderSoundId",
		name = "Thunder sound id",
		description = "In-game sound effect id for thunder. 9474 is a low rumble. 0 picks from "
			+ "the game's seven thunder rolls.",
		position = 52,
		section = weatherSection
	)
	default int thunderSoundId()
	{
		return 9474;
	}

	// ---------------------------------------------------------------- Lighting

	@ConfigSection(
		name = "Lighting",
		description = "Ambient, sun and moon light over the game's own shading, and light from "
			+ "fires and torches.",
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
		description = "Overall strength of the ambient, sun and moon light below. A little goes "
			+ "a long way. 0 off, max 100.",
		position = 51,
		section = lightSection
	)
	default int lightStrength()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "lightAmbientColor",
		name = "Ambient colour",
		description = "Colour of the light in shadow, which sets the colour of your shadows.",
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
		description = "How much ambient light. Higher lifts shadows and flattens the scene. 0 "
			+ "to 200.",
		position = 53,
		section = lightSection
	)
	default int lightAmbientStrength()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "lightSunColor",
		name = "Sun colour",
		description = "Colour of sunlight on faces turned toward the sun.",
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
		description = "How strongly the sun lights faces turned toward it. 0 to 200.",
		position = 55,
		section = lightSection
	)
	default int lightSunStrength()
	{
		return 70;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "lightMoonStrength",
		name = "Moon strength",
		description = "How much light the moon throws at night, following its phase and height. "
			+ "0 off, max 200.",
		position = 56,
		section = lightSection
	)
	default int lightMoonStrength()
	{
		return 100;
	}

	@Range(
		max = 10
	)
	@ConfigItem(
		keyName = "dynamicLights",
		name = "Dynamic lights",
		description = "Brightness of light from fires, torches, lanterns and braziers, which "
			+ "are found automatically. 0 off, max 10.",
		position = 557,
		section = lightSection
	)
	default int dynamicLights()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "undergroundSky",
		name = "Sky in caves and dungeons",
		description = "Keeps the sky and weather in caves, dungeons and raids. Off blacks the "
			+ "sky out and stops weather underground.",
		position = 305,
		section = skySection
	)
	default boolean undergroundSky()
	{
		return false;
	}

	@ConfigItem(
		keyName = "daytimeLights",
		name = "Lights during the day",
		description = "Keeps dynamic lights on by day. Off fades them in at dusk and out at "
			+ "dawn.",
		position = 558,
		section = lightSection
	)
	default boolean daytimeLights()
	{
		return false;
	}

	@ConfigItem(
		keyName = "lightColour",
		name = "Light colour",
		description = "Colour of the light from fires and torches.",
		position = 559,
		section = lightSection
	)
	default Color lightColour()
	{
		return new Color(0xFF, 0xA5, 0x4A);
	}

	@ConfigItem(
		keyName = "lightColourFromSource",
		name = "Colour from the source",
		description = "Lets a light that is not fire-coloured cast its own colour, such as a "
			+ "blue flame or a green lantern.",
		position = 560,
		section = lightSection
	)
	default boolean lightColourFromSource()
	{
		return true;
	}

	@Range(
		min = 1,
		max = 40
	)
	@ConfigItem(
		keyName = "lightRadius",
		name = "Light radius",
		description = "How far each light reaches, in tiles. 1 to 40.",
		position = 561,
		section = lightSection
	)
	default int lightRadius()
	{
		return 6;
	}

	@Range(
		min = 4,
		max = 64
	)
	@ConfigItem(
		keyName = "maxLights",
		name = "Max lights at once",
		description = "How many lights are drawn at once, nearest first. More costs frame rate. "
			+ "4 to 64.",
		position = 563,
		section = lightSection
	)
	default int maxLights()
	{
		return 64;
	}

	@Range(
		max = 60
	)
	@ConfigItem(
		keyName = "lightSearchDistance",
		name = "Light search distance",
		description = "How far out lights are looked for, in tiles. 0 follows the GPU plugin's "
			+ "draw distance, max 60.",
		position = 564,
		section = lightSection
	)
	default int lightSearchDistance()
	{
		return 60;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "lightFlicker",
		name = "Light flicker",
		description = "How much firelight wavers. 0 steady, max 200.",
		position = 562,
		section = lightSection
	)
	default int lightFlicker()
	{
		return 100;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "undergroundDarkening",
		name = "Underground darkening",
		description = "Darkens and cools caves and dungeons. 0 off, max 100.",
		position = 565,
		section = lightSection
	)
	default int undergroundDarkening()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "lightFollowsTime",
		name = "Follow time of day",
		description = "Dims and cools the world at night in step with the sky. Needs Sky colour "
			+ "set to Time of day.",
		position = 57,
		section = lightSection
	)
	default boolean lightFollowsTime()
	{
		return true;
	}

	// --------------------------------------------------------- Post-processing

	@ConfigSection(
		name = "Post-processing",
		description = "Bloom, god rays, auto exposure and colour grading.",
		position = 70,
		closedByDefault = true
	)
	String postSection = "postSection";

	@ConfigItem(
		keyName = "bloomEnabled",
		name = "Bloom",
		description = "Adds a soft glow around bright parts of the scene.",
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
		description = "How bright something must be before it glows. Lower makes more glow. 0 "
			+ "to 99.",
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
		description = "Strength of the glow. 0 to 200.",
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
		description = "Light shafts from the sun while it is in view. Needs Sky colour set to "
			+ "Time of day. 0 off, max 100.",
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
		description = "How bright the sky must be to cast a shaft. Lower catches more. 0 to 99.",
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
		description = "How far the shafts reach from the sun. 5 to 150.",
		position = 76,
		section = postSection
	)
	default int godRayLength()
	{
		return 80;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "autoExposure",
		name = "Auto exposure",
		description = "Adjusts brightness as you move between dark and bright places. "
			+ "Experimental, and costs a little frame rate while on. 0 off, max 100.",
		position = 765,
		section = postSection
	)
	default int autoExposure()
	{
		return 0;
	}

	@Range(
		min = 25,
		max = 250
	)
	@ConfigItem(
		keyName = "gradeGamma",
		name = "Gamma",
		description = "Midtone brightness. 100 is neutral. 25 to 250.",
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
		description = "Contrast between light and dark. 100 is neutral. 25 to 250.",
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
		description = "Colour intensity. 0 is greyscale, 100 neutral, max 250.",
		position = 79,
		section = postSection
	)
	default int gradeSaturation()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "autoTemperature",
		name = "Auto colour temperature",
		description = "Warms the picture at sunrise and sunset and cools it at night. "
			+ "Temperature below becomes an offset. Needs Sky colour set to Time of "
			+ "day.",
		position = 805,
		section = postSection
	)
	default boolean autoTemperature()
	{
		return false;
	}

	@Range(
		min = -100,
		max = 100
	)
	@ConfigItem(
		keyName = "gradeTemperature",
		name = "Temperature",
		description = "Colour warmth. Negative is cooler, positive warmer, 0 neutral. -100 to "
			+ "100.",
		position = 80,
		section = postSection
	)
	default int gradeTemperature()
	{
		return 0;
	}
}
