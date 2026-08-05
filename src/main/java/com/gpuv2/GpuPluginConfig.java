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
		description = "Default renders the game with the GPU and nothing else - no sky, "
			+ "weather, lighting, fog or post-processing. Custom uses everything you have "
			+ "set below. Switching between them changes nothing you have configured, so "
			+ "it is safe to flip back and forth to compare.",
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
		return 70;
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

	@Range(
		min = 50,
		max = 200
	)
	@ConfigItem(
		keyName = "renderScale",
		name = "Render scale",
		description = "What percentage of the window the world is drawn at before being "
			+ "scaled to fit. Below 100 costs sharpness and buys frame rate; above 100 "
			+ "renders larger and shrinks it down, which is the best-looking anti-aliasing "
			+ "there is and the most expensive. The interface is unaffected either way.",
		position = 135,
		section = displaySection
	)
	default int renderScale()
	{
		return 100;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "toneMapping",
		name = "Tone mapping",
		description = "Rolls bright areas off instead of letting them clip. Sunlit ground, "
			+ "the sun itself and heavy snow skies all go past what the screen can show and "
			+ "are currently cut flat white; this compresses them back into range so they "
			+ "keep their shape. 0 disables. It does darken midtones slightly on the way, "
			+ "which is why it is a slider rather than a switch.",
		position = 136,
		section = displaySection
	)
	default int toneMapping()
	{
		return 0;
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
		return AntiAliasingMode.MSAA_4;
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
		return 0;
	}

	@ConfigItem(
		keyName = "fxaa",
		name = "FXAA",
		description = "Post-process anti-aliasing. Smooths shader-drawn edges such as the "
			+ "aurora, clouds and lightning, which MSAA cannot touch since they are not "
			+ "geometry.",
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
		description = "Crispens edges. 0 disables. Pairs well with FXAA, which softens.",
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
		description = "Darkens the corners of the screen. 0 disables.",
		position = 153,
		section = displaySection
	)
	default int vignette()
	{
		return 0;
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
		return UIScalingMode.CATMULL_ROM;
	}

	@ConfigItem(
		keyName = "smoothLighting",
		name = "Smooth lighting",
		description = "Light curved surfaces smoothly instead of one flat panel per "
			+ "triangle."
			+ "<br><br>"
			+ "Most visible on trees, characters and rounded roofs, and easiest to see with "
			+ "Sun strength turned up. Flat walls and floors are unchanged."
			+ "<br><br>"
			+ "Terrain keeps its flat shading either way - the game provides no normals for "
			+ "it, and inventing them would round off corners that are meant to be sharp.",
		position = 137,
		section = displaySection
	)
	default boolean smoothLighting()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hideTrees",
		name = "Hide trees",
		description = "Leave trees out of the scene entirely - they are not drawn, not just "
			+ "hidden, so it is a saving as well as a view."
			+ "<br><br>"
			+ "Stumps, logs and saplings are kept, since those are usually what you are "
			+ "actually looking for.",
		position = 139,
		section = displaySection
	)
	default boolean hideTrees()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hideClutter",
		name = "Hide ground clutter",
		description = "Leave out the small scenery scattered over the ground - flowers, "
			+ "ferns, mushrooms, loose pebbles and the like."
			+ "<br><br>"
			+ "Kept narrow on purpose: anything you might want to click is not treated as "
			+ "clutter, since the cost of being wrong is a missing interaction.",
		position = 140,
		section = displaySection
	)
	default boolean hideClutter()
	{
		return false;
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
		keyName = "perfOverlay",
		name = "Performance overlay",
		description = "Show the current frame rate in the corner of the viewport. The "
			+ "options below add to it and do nothing on their own.",
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
		description = "Average milliseconds per frame. Often more use than the frame rate: "
			+ "the difference between 250 and 200 fps is under a millisecond, while the "
			+ "difference between 60 and 30 is sixteen.",
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
		description = "Mean frame rate over the last thousand frames.",
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
		description = "Frame rate of the slowest one percent of the last thousand frames - "
			+ "what a stutter actually feels like, and the number an average hides.",
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
		description = "GPU temperature and utilisation. NVIDIA cards only: there is no way "
			+ "to read either from Java, so this runs the nvidia-smi tool as a separate "
			+ "process every two seconds while it is on. Hides itself on any machine where "
			+ "that tool is not present. CPU temperature is not offered because Windows "
			+ "does not report it on most desktops without extra drivers.",
		position = 34,
		section = performanceSection
	)
	default boolean perfShowGpu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lowResSky",
		name = "Low resolution sky",
		description = "Draw the sky at half size and stretch it back. It is the most "
			+ "expensive pass here - fullscreen procedural cloud, every frame - and being a "
			+ "smooth gradient it survives the treatment better than anything else on "
			+ "screen. Little to no visible cost; the sun's edge is where to look.",
		position = 24,
		section = performanceSection
	)
	default boolean lowResSky()
	{
		return false;
	}

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
		return 300;
	}

	@Range(
		max = 60
	)
	@ConfigItem(
		keyName = "unfocusedFpsTarget",
		name = "FPS when unfocused",
		description = "Frame rate cap while the client is in the background. 0 keeps the "
			+ "normal target. Needs Unlock FPS.",
		position = 25,
		section = performanceSection
	)
	default int unfocusedFpsTarget()
	{
		return 0;
	}

	@Range(
		min = 1,
		max = 3
	)
	@ConfigItem(
		keyName = "effectQuality",
		name = "Effect quality",
		description = "Sample counts for the expensive effects - god rays, cloud detail "
			+ "and shooting star trails. 1 is cheapest, 3 is best.",
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
		description = "Master switch for bloom, god rays, FXAA, sharpening and vignette. "
			+ "Turn off to see what they cost.",
		position = 27,
		section = performanceSection
	)
	default boolean postProcessing()
	{
		return true;
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
		description = "Sky colour, time of day, fog and ground mist.",
		position = 30
	)
	String skySection = "skySection";

	@ConfigSection(
		name = "Fog",
		description = "Distance haze and mist lying on the ground.",
		position = 32
	)
	String fogSection = "fogSection";

	@ConfigItem(
		keyName = "fogEnabled",
		name = "Enable fog",
		description = "Master switch for both distance fog and ground mist.",
		position = 320,
		section = fogSection
	)
	default boolean fogEnabled()
	{
		return true;
	}

	@ConfigSection(
		name = "Sun and moon",
		description = "The sun and moon discs and the light they throw.",
		position = 34
	)
	String sunMoonSection = "sunMoonSection";

	@ConfigSection(
		name = "Stars and aurora",
		description = "Night sky detail.",
		position = 38
	)
	String starSection = "starSection";

	@ConfigSection(
		name = "Clouds",
		description = "Cloud cover, drift and the shadows they cast.",
		position = 42
	)
	String cloudSection = "cloudSection";

	@ConfigItem(
		keyName = "skyMode",
		name = "Sky colour",
		description = "Where the sky - and the fog that fades into it - takes its colour from.",
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
		max = 1439
	)
	@ConfigItem(
		keyName = "previewMinute",
		name = "Preview time",
		description = "For 'Time of day': hold the sky at a chosen time instead of following "
			+ "your clock. -1 follows the real time."
			+ "<br><br>"
			+ "Measured in minutes past midnight, so it sweeps the whole day rather than "
			+ "jumping an hour at a time: 360 is 06:00, 720 is midday, 1140 is 19:00."
			+ "<br><br>"
			+ "Everything the sky derives from time moves with it - colour, the sun and moon, "
			+ "cloud drift and the light on the ground - so dragging through a sunrise shows "
			+ "the whole transition rather than its endpoints.",
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
		description = "Distant scenery picks up the sky's colour, the way looking through "
			+ "air does. Builds gradually with distance rather than only at the scene "
			+ "edge like fog depth. 0 disables.",
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
		description = "How far from the scene edge fog starts. Fades into the sky colour.",
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
		description = "Draw a sun that arcs east to west across the day.",
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
		description = "How fiercely the sun's disc burns.",
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
		description = "Halo and streaks radiating from the sun. 0 disables.",
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
		description = "Draw a moon, opposite the sun so it is up at night.",
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
		description = "Let the moon wax and wane between nights. Off keeps it permanently "
			+ "full.",
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
		description = "Days for a full new-to-full-to-new cycle. 29 matches the real "
			+ "lunar calendar but changes only slightly per night; lower makes the "
			+ "phases visibly move.",
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
		description = "-1 uses the cycle above. 0-100 pins a phase for a look: 0 new, "
			+ "50 full, 100 new again.",
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
		description = "How brightly the moon's disc and halo shine.",
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
		description = "Draw a starfield after dark.",
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
		description = "How many stars fill the sky.",
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
		description = "How much of the sky is covered by cloud. 0 disables clouds entirely.",
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
		description = "How solid the clouds look against the sky.",
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
		description = "How fast clouds drift and reshape. 1 is a realistic crawl, "
			+ "3 is a brisk sky.",
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
		description = "Meteors streaking across the night sky. 0 off, 1 rare (roughly one "
			+ "every few minutes), 2 shows them constantly so the speed can be judged. "
			+ "Needs stars enabled and a clear sky.",
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
		description = "How fast they cross the sky. 1 is a slow drift, 3 is a quick flash.",
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
		description = "Play a chime when a shooting star appears.",
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
		description = "Any in-game sound effect id - type whichever you prefer. 3924 is "
			+ "a coin tinkle, 200 a teleport whoosh, 3925 a bell ding.",
		position = 388,
		section = starSection
	)
	default int shootingStarSoundId()
	{
		return 3924;
	}

	@ConfigItem(
		keyName = "aurora",
		name = "Aurora",
		description = "Shimmering curtains low in the northern sky on clear nights. "
			+ "Cloud cover hides it.",
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
		description = "How bright the aurora is.",
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
		description = "How fast the aurora churns. 1 is a slow lava-lamp drift, "
			+ "3 is restless.",
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
		description = "Mist pooling in low ground and valleys. 0 disables. Fog weather "
			+ "brings its own on top of this.",
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
		description = "How deep the mist lies, in tiles. Lower keeps it hugging the "
			+ "ground; higher fills valleys.",
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
		description = "Drift cloud shadows across the world, matching the deck overhead. "
			+ "0 disables.",
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
		return 30;
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
		return 93;
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
		return 58;
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
		return 100;
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
		return 100;
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
		return 3;
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
		return 5;
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
		return 70;
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
		return 70;
	}

	@Range(
		max = 10
	)
	@ConfigItem(
		keyName = "dynamicLights",
		name = "Dynamic lights",
		description = "Brightness of light cast by fires, torches, lanterns and braziers. "
			+ "0 to 10, where 0 is off and 10 is brightest. Sources are found "
			+ "automatically by name - no setup needed.",
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
		description = "Keep drawing the sky underground. Off blacks it out in caves, "
			+ "dungeons and raids, and stops weather falling indoors - there is no sky "
			+ "above you down there, so a sunset through the ceiling gives the game away.",
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
		description = "Keep dynamic lights on around the clock - torches, fires and glowing "
			+ "attacks alike. Off follows the sky clock instead, fading them in through dusk "
			+ "and out again at dawn, since firelight on the ground under a midday sun is "
			+ "what gives the effect away. Has no effect unless the sky is set to time of "
			+ "day, because nothing else has a clock to follow.",
		position = 557,
		section = lightSection
	)
	default boolean daytimeLights()
	{
		return false;
	}

	@ConfigItem(
		keyName = "lightColour",
		name = "Light colour",
		description = "Colour cast by dynamic lights.",
		position = 559,
		section = lightSection
	)
	default Color lightColour()
	{
		return new Color(0xFF, 0xA5, 0x4A);
	}

	@Range(
		min = 1,
		max = 40
	)
	@ConfigItem(
		keyName = "lightRadius",
		name = "Light radius",
		description = "How far a single light reaches, in tiles. Raising this also widens "
			+ "the area searched for lights, so distant ones keep working.",
		position = 560,
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
		description = "How many lights can be drawn together. Somewhere dense this is what "
			+ "limits how far lighting reaches, since only the nearest ones fit - raise it "
			+ "to light more of the street. Each costs a little performance everywhere on "
			+ "screen, so raise it only as far as you need.",
		position = 562,
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
		description = "How far out lights are looked for, in tiles. 0 follows your draw "
			+ "distance, so anything on screen can light. Raise it only alongside 'Max "
			+ "lights at once' - finding more lights does nothing if there is no room to "
			+ "draw them.",
		position = 563,
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
		description = "How much firelight wavers. 0 is a steady glow.",
		position = 561,
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
		description = "Darken, cool and desaturate underground areas, where there is no "
			+ "daylight. 0 disables.",
		position = 562,
		section = lightSection
	)
	default int undergroundDarkening()
	{
		return 1;
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
		return 80;
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

	@ConfigItem(
		keyName = "autoTemperature",
		name = "Auto colour temperature",
		description = "Warm the image at sunrise and sunset and cool it at night, "
			+ "following the sky clock. The slider below becomes an offset. Needs sky "
			+ "colour set to 'Time of day'.",
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
