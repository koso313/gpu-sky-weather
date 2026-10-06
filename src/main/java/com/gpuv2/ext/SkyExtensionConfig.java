package com.gpuv2.ext;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * Settings for the extension build of the sky.
 *
 * <p>Deliberately a small subset of the full plugin's config. This is a port to find out
 * what the API supports, not a second copy of the plugin, so it carries only what the sky
 * pass itself reads.
 */
@ConfigGroup(SkyExtensionConfig.GROUP)
public interface SkyExtensionConfig extends Config
{
	String GROUP = "gpuv2sky";

	@ConfigItem(
		keyName = "showSun",
		name = "Sun",
		description = "Draw a sun that rises in the east and sets in the west.",
		position = 1
	)
	default boolean showSun()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMoon",
		name = "Moon",
		description = "Draw a moon, opposite the sun so it is up at night.",
		position = 2
	)
	default boolean showMoon()
	{
		return true;
	}

	@Range(max = 250)
	@ConfigItem(
		keyName = "sunGlow",
		name = "Sun brightness",
		description = "How fiercely the sun's disc burns.",
		position = 3
	)
	default int sunGlow()
	{
		return 100;
	}

	@Range(max = 250)
	@ConfigItem(
		keyName = "sunGlare",
		name = "Sun glare",
		description = "Halo and streaks radiating from the sun. 0 disables.",
		position = 4
	)
	default int sunGlare()
	{
		return 50;
	}

	@Range(max = 250)
	@ConfigItem(
		keyName = "moonGlow",
		name = "Moon brightness",
		description = "How brightly the moon's disc and halo shine.",
		position = 5
	)
	default int moonGlow()
	{
		return 110;
	}

	@ConfigItem(
		keyName = "stars",
		name = "Stars at night",
		description = "Draw a starfield after dark.",
		position = 6
	)
	default boolean stars()
	{
		return true;
	}

	@Range(min = 1, max = 100)
	@ConfigItem(
		keyName = "starDensity",
		name = "Star density",
		description = "How many stars fill the sky.",
		position = 7
	)
	default int starDensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "aurora",
		name = "Aurora",
		description = "Shimmering curtains low in the northern sky on clear nights.",
		position = 8
	)
	default boolean aurora()
	{
		return true;
	}

	@Range(min = 1, max = 100)
	@ConfigItem(
		keyName = "auroraStrength",
		name = "Aurora strength",
		description = "How bright the aurora burns.",
		position = 9
	)
	default int auroraStrength()
	{
		return 100;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "cloudAmount",
		name = "Cloud cover",
		description = "How much of the sky is covered by cloud. 0 disables clouds.",
		position = 10
	)
	default int cloudAmount()
	{
		return 50;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "cloudOpacity",
		name = "Cloud strength",
		description = "How solid the clouds look against the sky.",
		position = 11
	)
	default int cloudOpacity()
	{
		return 100;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "nightDimming",
		name = "Night dimming",
		description = "How far the world is dimmed and cooled at night, in step with the sky. "
			+ "0 leaves the world lit the same at every hour.",
		position = 12
	)
	default int nightDimming()
	{
		return 60;
	}

	@Range(min = -1, max = 1439)
	@ConfigItem(
		keyName = "previewMinute",
		name = "Preview time",
		description = "Hold the sky at a chosen time instead of following your clock, in "
			+ "minutes past midnight: 360 is 06:00, 720 is midday, 1140 is 19:00. "
			+ "-1 follows the real time.",
		position = 13
	)
	default int previewMinute()
	{
		return -1;
	}
}
