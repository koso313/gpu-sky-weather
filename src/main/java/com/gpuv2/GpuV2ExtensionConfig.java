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
		description = "Tone mapping and the finishing passes over the image. Draw distance and "
			+ "anti-aliasing are set in the GPU plugin, which draws the world.",
		position = 10
	)
	String displaySection = "displaySection";

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
		keyName = "fxaa",
		name = "FXAA",
		description = "Post-process anti-aliasing. Smooths shader-drawn edges such as the "
			+ "aurora, clouds and lightning, which the GPU plugin's anti-aliasing cannot touch since they are not "
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
		description = "Crispens edges and picks detail back out of textures. 0 disables."
			+ "<br><br>"
			+ "Pairs well with FXAA, which softens the image - this puts the bite back. "
			+ "Push it too far and edges grow bright "
			+ "outlines.",
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
		description = "Darkens the corners of the screen, drawing the eye toward the middle "
			+ "and giving the picture a lens-like framing. 0 disables."
			+ "<br><br>"
			+ "A matter of taste rather than accuracy, and it works against you in dark "
			+ "places where the corners are already hard to read.",
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
		description = "The performance overlay and the quality/cost trade-offs of this plugin's own "
			+ "effects. Frame rate and threads are set in the GPU plugin. Also where the "
			+ "performance overlay lives.",
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
		description = "Mean frame rate over the last thousand frames. Steadier than the live "
			+ "reading, and the fair number to compare against after changing a setting.",
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
		description = "Master switch for distance fog, ground mist and aerial perspective."
			+ "<br><br>"
			+ "Fog fades the far edge of the scene into the sky colour, which hides the hard "
			+ "line where drawing stops and makes a short draw distance far less obvious.",
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
		description = "Stars, shooting stars and the aurora. All of it is night-only and "
			+ "hidden by cloud cover.",
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
		description = "Where the sky - and the fog that fades into it - takes its colour from."
			+ "<br><br>"
			+ "'Game default' uses whatever the area itself specifies, as vanilla does. "
			+ "'Custom colour' holds the single colour you pick below. 'Time of day' runs a "
			+ "full day cycle from your system clock, shifting continuously through dawn, "
			+ "midday, dusk and night."
			+ "<br><br>"
			+ "Several other settings - god rays, lights following time, auto colour "
			+ "temperature - need 'Time of day', since the others have no clock to follow.",
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
		description = "How far in from the edge of the drawn world the fog begins, in tiles."
			+ "<br><br>"
			+ "Higher brings the haze closer and hides more of the distance; 0 disables it. "
			+ "Because it fades to the sky colour, it changes through the day along with the "
			+ "sky rather than staying a fixed grey.",
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
		description = "Draw a sun that rises in the east, crosses the sky through the day and "
			+ "sets in the west, matching the game's own compass."
			+ "<br><br>"
			+ "Its height drives the sky colour, the direction of the lighting and the god "
			+ "rays, so this is the anchor the rest of the time-of-day effects hang off.",
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
		description = "How fiercely the sun's disc burns. 0 leaves it a plain pale circle; "
			+ "high makes it too bright to look at, with the glow bleeding into the sky "
			+ "around it."
			+ "<br><br>"
			+ "Weather that hides the sun overrides this.",
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
		description = "Halo and streaks radiating from the sun, as a camera lens or a squint "
			+ "would give you. 0 disables."
			+ "<br><br>"
			+ "Strongest when the sun is low and pointed at, and hidden by weather that takes "
			+ "the sun away.",
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
		description = "Draw a moon. It rides opposite the sun, so it rises as the sun sets "
			+ "and is up through the night."
			+ "<br><br>"
			+ "Its position tracks the phase as a real moon does - a crescent sits near the "
			+ "sun and follows it down, a full moon is high at midnight.",
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
		description = "Let the moon wax and wane from night to night rather than staying "
			+ "permanently full."
			+ "<br><br>"
			+ "The phase also decides where the moon sits and how much light it throws, so a "
			+ "new moon leaves genuinely darker nights than a full one.",
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
		description = "How brightly the moon's disc and its halo shine. 0 leaves the moon "
			+ "drawn but unlit."
			+ "<br><br>"
			+ "Only the lit crescent is affected, so a thin moon stays dim however high this "
			+ "goes.",
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
		description = "Draw a starfield after dark. Fades in as the sky darkens and out again "
			+ "at dawn, and cloud cover and weather hide it."
			+ "<br><br>"
			+ "The field is fixed to the sky rather than the camera, so stars hold their "
			+ "places as you turn.",
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
		description = "How many stars fill the sky. Low gives a handful of bright ones, high "
			+ "gives a dense field with faint stars between them.",
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
		description = "How much of the sky is covered by cloud. 0 disables clouds entirely, "
			+ "low gives scattered fair-weather puffs, high gives a broken deck with only "
			+ "gaps of blue."
			+ "<br><br>"
			+ "Weather raises this on its own while it runs, so an overcast day closes over "
			+ "whatever you set here.",
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
		description = "How solid the clouds look against the sky. Low gives thin haze you can "
			+ "see through, high gives opaque cloud with definite edges."
			+ "<br><br>"
			+ "Separate from cloud cover, which is how much sky they take up rather than how "
			+ "dense they are.",
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
		description = "Play a sound when a shooting star appears. Off by default, since a "
			+ "chime with no in-game cause behind it can be misleading.",
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
		description = "Which in-game sound effect to play. 0 is silence, so the sound can be "
			+ "switched on without anything arbitrary playing until you pick one."
			+ "<br><br>"
			+ "Any sound effect id works - type whichever you prefer. 3924 is a coin tinkle, "
			+ "200 a teleport whoosh, 3925 a bell ding.",
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
		description = "How bright the aurora burns. Low is a faint green suggestion near the "
			+ "horizon, high is unmistakable curtains reaching up the sky.",
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
		description = "What the weather is doing. Precipitation is drawn in front of the "
			+ "world and behind the interface, and each condition also settles the sky to "
			+ "match - there is no raining out of a clear blue sky."
			+ "<br><br>"
			+ "'Off' and 'Sunny' both leave the sky exactly as you configured it. 'Overcast' "
			+ "seals the deck over without anything falling. 'Fog' does the same and fills the "
			+ "air with mist, so the distance closes in. Rain, storm, snow and blizzard "
			+ "each take the sun away and darken the light beneath them."
			+ "<br><br>"
			+ "'Automatic' changes it on its own over time, keeping clear skies roughly two "
			+ "thirds of the time so that weather stays worth noticing.",
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

	@ConfigItem(
		keyName = "weatherFollowsRegion",
		name = "Automatic: follow the region",
		description = "Let 'Automatic' weather suit where you are standing."
			+ "<br><br>"
			+ "The desert stays dry, the mountains and the far north get snow where "
			+ "elsewhere would get rain, Morytania is wet and often foggy, and Karamja "
			+ "rains hard but never snows. Everywhere else is as before."
			+ "<br><br>"
			+ "Crossing between them fades one kind of weather out and the next in. Off "
			+ "draws from one table everywhere, so it can snow in Al Kharid.",
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
		description = "How much rain or snow falls. Low is a light scattering, high is a "
			+ "downpour thick enough to see through."
			+ "<br><br>"
			+ "Storm and blizzard already fall harder than rain and snow, so this scales on "
			+ "top of whichever condition is running rather than replacing it.",
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
		description = "How hard the wind blows precipitation sideways, and how much it "
			+ "gusts. 0 makes it fall straight down.",
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
		description = "Settles snow on upward-facing surfaces while it is snowing - ground, "
			+ "rooftops and the tops of scenery, but not walls. 0 disables."
			+ "<br><br>"
			+ "Builds up as the snow falls and melts away again once it stops.",
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
		description = "Darkens the ground and pools reflective puddles while it is "
			+ "raining. 0 disables.",
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
		description = "Flashes and bolts of lightning during a storm."
			+ "<br><br>"
			+ "Each strike lights the world as well as the sky, so the ground and everything "
			+ "on it flares for the instant the bolt is out. Storms only.",
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
		description = "How often lightning strikes during a storm. 1 is occasional, "
			+ "2 is frequent, 3 is near-constant.",
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
		description = "Play thunder a moment after each lightning strike. Off by default, "
			+ "since a sound with nothing in the game behind it can be misleading."
			+ "<br><br>"
			+ "Uses the game's sound effects, so it follows your sound effect volume and "
			+ "is silent if that is muted. With lightning set to near-constant this is a "
			+ "lot of thunder.",
		position = 50,
		section = weatherSection
	)
	default boolean thunderSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "thunderSoundId",
		name = "Thunder sound id",
		description = "0 picks from the game's own seven rolls of thunder, a different one "
			+ "each strike. Any other in-game sound effect id plays that one every time - "
			+ "3762 is a single heavy crack.",
		position = 51,
		section = weatherSection
	)
	default int thunderSoundId()
	{
		return 0;
	}

	// ---------------------------------------------------------------- Lighting

	@ConfigSection(
		name = "Lighting",
		description = "Ambient and directional light layered over the game's own shading, "
			+ "plus the dynamic lights cast by fires, torches and lanterns.",
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
		description = "Master strength for the ambient and sun lighting below. 0 disables "
			+ "them entirely and leaves the game's own shading untouched."
			+ "<br><br>"
			+ "This lighting is layered over vanilla shading rather than replacing it, so a "
			+ "little goes a long way - high values wash out the original art.",
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
		description = "Colour of the light filling faces the sun does not reach."
			+ "<br><br>"
			+ "Stands in for skylight, so a cool blue reads as a clear day and a warm tone as "
			+ "firelit or overcast. This is what sets the colour of your shadows.",
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
		description = "How much ambient light there is. Raise it to lift shadows and flatten "
			+ "the scene, lower it for deeper contrast between lit and unlit faces."
			+ "<br><br>"
			+ "Balance this against Sun strength: ambient sets the floor, the sun sets how "
			+ "far above it the lit side rises.",
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
		description = "Colour of the light thrown by the sun onto faces angled toward it."
			+ "<br><br>"
			+ "Warm tones read as low sun and late afternoon, near-white as midday. Works "
			+ "against the ambient colour - the wider apart the two, the more the shading "
			+ "reads as sunlight rather than brightness.",
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
		description = "How hard the sun lights faces turned toward it."
			+ "<br><br>"
			+ "This is what gives objects a lit side and a shaded side, so it does most of "
			+ "the work of making things look solid. Turn it up when judging whether Smooth "
			+ "lighting is doing anything.",
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
		description = "How much light the moon throws at night, from wherever it is in the "
			+ "sky. 0 disables."
			+ "<br><br>"
			+ "Follows the moon itself: brightest under a full moon high in the sky, nothing "
			+ "at new moon, before it rises, or when the weather has hidden it. A full moon "
			+ "also lifts the darkest the night gets a little, and a new moon lowers it."
			+ "<br><br>"
			+ "Scaled by Lighting strength like the sun, so at a low Lighting strength it is "
			+ "subtle.",
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
		description = "Keep dynamic lights burning around the clock."
			+ "<br><br>"
			+ "Off follows the sky clock instead, fading them in through dusk and out again "
			+ "at dawn - firelight pooling on the ground under a midday sun is what gives the "
			+ "effect away."
			+ "<br><br>"
			+ "Has no effect unless the sky is set to time of day, since nothing else has a "
			+ "clock to follow. Underground is always treated as night either way.",
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
		description = "Colour cast by dynamic lights onto the ground and nearby scenery. "
			+ "Warm orange reads as firelight; cooler tones suit lanterns and magical "
			+ "sources.",
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
		description = "Let a light that is plainly not fire cast its own colour - a blue "
			+ "flame lights the ground blue, a green lantern green."
			+ "<br><br>"
			+ "Ordinary fire, in every shade from red to yellow, still takes the Light colour "
			+ "above. Off gives every light that one colour.",
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
		description = "How far a single light reaches, in tiles. Raising this also widens "
			+ "the area searched for lights, so distant ones keep working.",
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
		description = "How many lights can be drawn together. Somewhere dense this is what "
			+ "limits how far lighting reaches, since only the nearest ones fit - raise it "
			+ "to light more of the street. Each costs a little performance everywhere on "
			+ "screen, so raise it only as far as you need.",
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
		description = "How far out lights are looked for, in tiles. 0 follows your draw "
			+ "distance, so anything on screen can light. Raise it only alongside 'Max "
			+ "lights at once' - finding more lights does nothing if there is no room to "
			+ "draw them.",
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
		description = "How much firelight wavers and breathes. 0 is a steady glow, high is a "
			+ "guttering flame."
			+ "<br><br>"
			+ "Each light flickers on its own rhythm, so a row of torches does not pulse in "
			+ "unison.",
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
		description = "Darken, cool and desaturate underground areas, where there is no "
			+ "daylight. 0 disables.",
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
		description = "Dim and cool the world at night in step with the sky, so dusk falls on "
			+ "the ground as well as overhead."
			+ "<br><br>"
			+ "Only applies when sky colour is set to 'Time of day' - the other modes have no "
			+ "clock to follow. Off keeps the world lit the same however late it is.",
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
		description = "Strength of the glow added back over the scene. Low is a subtle "
			+ "softening around bright edges; high is a dreamy haze over everything that "
			+ "passed the threshold.",
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
		description = "How far the shafts stretch out from the sun. Short keeps them a halo "
			+ "close around it; long reaches them across the sky.",
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
		description = "Separation between lights and darks. 100 is neutral; higher deepens "
			+ "shadows and brightens highlights, lower flattens the image toward grey.",
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
		description = "Colour intensity. 100 is neutral, 0 is greyscale, and above 100 pushes "
			+ "colours harder - useful for bringing the original art's palette back after "
			+ "heavy lighting has washed it out.",
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
}
