package com.gpuv2;

import com.gpuv2.config.WeatherMode;

/**
 * What kind of weather a part of the world gets.
 *
 * <p>The automatic cycle draws from one table everywhere unless told otherwise, which is
 * how it comes to snow in the Kharidian Desert. Each climate is a table of its own, drawn
 * from in exactly the same way, so the desert stays dry and the mountains stay white
 * without the cycle itself knowing anything about geography.
 *
 * <p>The regions are rectangles in world coordinates and deliberately few. They mark the
 * places where the default weather is plainly wrong - sand, ice, swamp and jungle - and
 * leave everywhere else temperate. A boundary drawn a few tiles off is invisible in play;
 * a list long enough to be exact would not stay correct for long.
 */
enum Climate
{
	/** Most of the mainland. The original table, with the odd foggy spell. */
	TEMPERATE(
		clear(12), of(WeatherMode.OVERCAST, 2), of(WeatherMode.RAIN, 2), of(WeatherMode.FOG, 1),
		of(WeatherMode.SNOW, 1), of(WeatherMode.STORM, 1), of(WeatherMode.BLIZZARD, 1)),

	/** Sand and sun. Cloud is as bad as it gets; nothing ever falls. */
	DESERT(
		clear(18), of(WeatherMode.OVERCAST, 2)),

	/** Mountains and the far north. What would be rain elsewhere arrives as snow. */
	COLD(
		clear(8), of(WeatherMode.OVERCAST, 3), of(WeatherMode.SNOW, 5), of(WeatherMode.BLIZZARD, 2),
		of(WeatherMode.FOG, 2)),

	/** Swamp and marsh. Rarely clear for long, and fog comes with the territory. */
	WET(
		clear(6), of(WeatherMode.OVERCAST, 4), of(WeatherMode.RAIN, 4), of(WeatherMode.FOG, 4),
		of(WeatherMode.STORM, 2)),

	/** Jungle. Hot, so it never snows, but it rains hard and often. */
	TROPICAL(
		clear(10), of(WeatherMode.OVERCAST, 2), of(WeatherMode.RAIN, 5), of(WeatherMode.STORM, 2),
		of(WeatherMode.FOG, 1));

	// Underground starts here; nothing below it has weather, and no region reaches it.
	private static final int SURFACE_MAX_Y = 6400;

	private static final Region[] REGIONS = {
		// Kharidian Desert, south of the Shantay Pass.
		new Region(DESERT, 3130, 2600, 3530, 3116),
		// Al Kharid, its mine and the old duel arena, east of the River Lum.
		new Region(DESERT, 3268, 3117, 3400, 3330),

		// Death Plateau, Trollheim, the Troll Stronghold and the God Wars approach.
		new Region(COLD, 2750, 3560, 2943, 3800),
		// White Wolf Mountain.
		new Region(COLD, 2810, 3455, 2880, 3530),
		// The Fremennik north: the snow hunter area, the Keldagrim approach, Trollweiss.
		new Region(COLD, 2600, 3700, 2749, 3890),
		// Neitiznot and Jatizso.
		new Region(COLD, 2300, 3780, 2440, 3900),
		// Waterbirth Island.
		new Region(COLD, 2490, 3710, 2570, 3780),
		// The Iceberg.
		new Region(COLD, 2610, 3970, 2710, 4090),
		// Weiss.
		new Region(COLD, 2840, 3900, 2910, 3980),
		// The Wilderness ice plateau.
		new Region(COLD, 2940, 3890, 3015, 3970),

		// Morytania: Mort Myre, Canifis, Port Phasmatys, the Barrows, Meiyerditch.
		new Region(WET, 3410, 3160, 3750, 3560),

		// Karamja, from Brimhaven and Musa Point down through the Kharazi Jungle.
		new Region(TROPICAL, 2740, 2880, 2990, 3200),
		// Ape Atoll.
		new Region(TROPICAL, 2690, 2690, 2815, 2815),
	};

	private final WeatherMode[] table;

	Climate(WeatherMode[]... runs)
	{
		int length = 0;
		for (WeatherMode[] run : runs)
		{
			length += run.length;
		}

		table = new WeatherMode[length];
		int at = 0;
		for (WeatherMode[] run : runs)
		{
			System.arraycopy(run, 0, table, at, run.length);
			at += run.length;
		}
	}

	/**
	 * The climate at a point on the world map. Anywhere not listed is temperate, including
	 * instances, which sit at coordinates that say nothing about what they are a copy of.
	 */
	static Climate at(int worldX, int worldY)
	{
		if (worldY >= SURFACE_MAX_Y)
		{
			return TEMPERATE;
		}

		for (Region region : REGIONS)
		{
			if (region.contains(worldX, worldY))
			{
				return region.climate;
			}
		}
		return TEMPERATE;
	}

	/**
	 * The weather for a draw in 0..1. One entry per share of the odds, so the proportions
	 * can be read straight off the declaration.
	 */
	WeatherMode pick(double r)
	{
		int i = (int) (r * table.length);
		return table[Math.max(0, Math.min(i, table.length - 1))];
	}

	/** How many entries of the table are the given weather. For tests. */
	int count(WeatherMode mode)
	{
		int n = 0;
		for (WeatherMode m : table)
		{
			if (m == mode)
			{
				++n;
			}
		}
		return n;
	}

	int tableSize()
	{
		return table.length;
	}

	private static WeatherMode[] clear(int shares)
	{
		return of(WeatherMode.OFF, shares);
	}

	private static WeatherMode[] of(WeatherMode mode, int shares)
	{
		WeatherMode[] run = new WeatherMode[shares];
		java.util.Arrays.fill(run, mode);
		return run;
	}

	private static final class Region
	{
		private final Climate climate;
		private final int minX;
		private final int minY;
		private final int maxX;
		private final int maxY;

		Region(Climate climate, int minX, int minY, int maxX, int maxY)
		{
			this.climate = climate;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
		}

		boolean contains(int x, int y)
		{
			return x >= minX && x <= maxX && y >= minY && y <= maxY;
		}
	}
}
