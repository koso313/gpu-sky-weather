package com.gpuv2;

/**
 * Decides whether a terrain tile is water, from the tile's own geometry and colour.
 *
 * <p>Nothing in the game data marks a tile as water. The stock client has no water
 * rendering - water is just terrain that happens to be painted blue - so no flag was ever
 * needed. 117HD solves this with a hand-built table mapping thousands of overlay and
 * underlay ids to water types; that works, but it only covers ids somebody thought to
 * enter, and it is a lot of data to carry and keep current.
 *
 * <p>This takes the same route {@link LightScanner} takes for lights: judge the thing
 * itself rather than look it up. Water has two properties that almost nothing else in the
 * game combines:
 *
 * <ul>
 *   <li><b>It is perfectly level.</b> Water finds its own surface, so the client never
 *       gives a water tile sloped corners. Grass, paths and floors slope constantly.
 *   <li><b>It is blue.</b> Not merely "not green" - it sits in a narrow band of the hue
 *       wheel that terrain otherwise barely uses.
 * </ul>
 *
 * <p>Either test alone is useless: plenty of ground is flat, and a few floors are blue.
 * Together they are specific enough to run unattended, which is the whole point - no ids
 * to find, no list to maintain, and it works in areas nobody has visited yet.
 *
 * <p>What it deliberately misses is water that is not blue: swamp, and the various murky
 * greens. Those sit right on top of grass in hue, and widening the band far enough to
 * catch them turns every lawn in the game into a rippling lake. Those are what the manual
 * texture-id override stays for.
 */
final class WaterDetector
{
	/*
	 * Jagex packs colour as 6 bits of hue, 3 of saturation, 7 of luminance. Hue is a
	 * standard colour wheel starting at red, so hue/64 is the fraction around it: 0 is
	 * red, 1/3 green, 2/3 blue.
	 */
	private static final int HUE_SHIFT = 10;
	private static final int HUE_MASK = 0x3F;
	private static final int SAT_SHIFT = 7;
	private static final int SAT_MASK = 0x7;
	private static final int LUM_MASK = 0x7F;

	/**
	 * The blue arc, in Jagex hue units.
	 *
	 * <p>33/64 is a fraction of 0.52 - just past cyan - and 46/64 is 0.72, just before the
	 * band turns violet. Sea, lake, river and pool water all land inside it. Stopping short
	 * of violet matters: several dungeon and Zeah floors are purple-blue, and they are flat.
	 */
	private static final int MIN_HUE = 33;
	private static final int MAX_HUE = 46;

	/**
	 * Water is a saturated colour. Below this the "blue" is really grey with a cast to it,
	 * which describes an awful lot of stone flooring.
	 */
	private static final int MIN_SATURATION = 2;

	/**
	 * Luminance bounds, of 127. Excludes tiles rendered essentially black (unlit indoor
	 * geometry) or blown out to white, where hue means little and the ripple would look
	 * wrong regardless.
	 */
	private static final int MIN_LUMINANCE = 8;
	private static final int MAX_LUMINANCE = 110;

	/** The client's marker for "do not draw this corner". */
	static final int HIDDEN = 12345678;

	private WaterDetector()
	{
	}

	/**
	 * Classifies a four-cornered flat tile.
	 *
	 * @param hslSw packed HSL at each corner, as the client has already lit them
	 * @param heights the four corner heights, in world units
	 */
	static boolean isWaterTile(int hslSw, int hslSe, int hslNe, int hslNw,
		int hSw, int hSe, int hNe, int hNw)
	{
		if (hSw != hSe || hSw != hNe || hSw != hNw)
		{
			return false;
		}

		// All four corners, not a majority. Shorelines blend land colour into the water
		// tile next to them, and accepting three-of-four would walk the effect up the beach.
		return isWaterColor(hslSw) && isWaterColor(hslSe)
			&& isWaterColor(hslNe) && isWaterColor(hslNw);
	}

	/**
	 * Classifies one triangle of a shaped tile. Shaped tiles are how the client draws the
	 * diagonal edge where water meets land, so they carry the shoreline.
	 *
	 * @param yA vertex heights; a water triangle is still level
	 */
	static boolean isWaterFace(int hslA, int hslB, int hslC, int yA, int yB, int yC)
	{
		if (yA != yB || yA != yC)
		{
			return false;
		}

		return isWaterColor(hslA) && isWaterColor(hslB) && isWaterColor(hslC);
	}

	static boolean isWaterColor(int hsl)
	{
		if (hsl == HIDDEN || hsl < 0)
		{
			return false;
		}

		int hue = (hsl >> HUE_SHIFT) & HUE_MASK;
		if (hue < MIN_HUE || hue > MAX_HUE)
		{
			return false;
		}

		if (((hsl >> SAT_SHIFT) & SAT_MASK) < MIN_SATURATION)
		{
			return false;
		}

		int lum = hsl & LUM_MASK;
		return lum >= MIN_LUMINANCE && lum <= MAX_LUMINANCE;
	}
}
