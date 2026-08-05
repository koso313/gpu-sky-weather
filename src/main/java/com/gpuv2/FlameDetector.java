package com.gpuv2;

/**
 * Decides whether a model is on fire, from the colours its faces are painted.
 *
 * <p>Needed because names run out. Most scenery in OSRS has no name at all - the cache
 * stores the literal string "null" for anything you cannot click, and wall torches are
 * pure decoration, so that is exactly what they are. No name means
 * {@link LightScanner#nameSuggestsLight} can never reach them, however many words are
 * added to it.
 *
 * <p>What a flame does have is its colour. Fire is painted in a narrow band of bright,
 * heavily saturated orange that almost nothing else in the game uses at that intensity.
 *
 * <p>Reads {@code getFaceColors1()}, the lit colours, because {@code getUnlitFaceColors()}
 * comes back null for scene models - ::lightids reported "no faces" for every animated
 * object nearby. Lit is fine here, unlike for terrain: model lighting is applied by moving
 * the luminance component of the packed HSL, so hue and saturation survive it. That is not
 * true of {@code SceneTilePaint}, whose corner colours are bare light levels with no hue in
 * them at all - which is why saturation carries more of the decision here than brightness.
 *
 * <p>The floors do all the discriminating, so they matter more than the hue arc does. Wood
 * and brick sit in the same arc as flame and are only separated by being duller and darker;
 * a yellow banner is separated by brightness alone. An earlier version also required the
 * object to be animated, on the grounds that fire flickers - true, but it excluded every
 * static wall torch, which is the thing this was written to find.
 */
final class FlameDetector
{
	/*
	 * Jagex packs colour as 6 bits of hue, 3 of saturation, 7 of luminance. Hue is a
	 * standard wheel starting at red, so hue/64 is the fraction around it.
	 */
	private static final int HUE_SHIFT = 10;
	private static final int HUE_MASK = 0x3F;
	private static final int SAT_SHIFT = 7;
	private static final int SAT_MASK = 0x7;
	private static final int LUM_MASK = 0x7F;

	/**
	 * The fire arc, in Jagex hue units: red through orange to yellow. 12/64 is a fraction
	 * of 0.19, just past yellow and short of green.
	 */
	static final int MAX_HUE = 12;

	/**
	 * Flame is near-pure colour. This is what separates it from wood and brick, which sit
	 * in the same hue arc but muted - the bracket a torch is mounted on is brown, and brown
	 * is only dark unsaturated orange.
	 */
	static final int MIN_SATURATION = 5;

	/**
	 * And it is bright. Kept lower than a flame's painted brightness because these are lit
	 * colours: scene lighting moves luminance in both directions, and a torch in a dark
	 * corner at night is still a torch.
	 */
	static final int MIN_LUMINANCE = 70;

	/**
	 * How many faces have to be alight. Just two, and there is no proportional test at all.
	 *
	 * <p>Measured rather than guessed: a Falador wall torch reports 2 burning faces out of
	 * 150. The flame is a tiny cap on a model that is nearly all mounting bracket, and the
	 * bracket is textured, so its faces carry light values with no hue in them. An earlier
	 * version wanted 12% of the model alight, which asked that torch for 18 faces and missed
	 * it by a factor of nine.
	 *
	 * <p>Dropping the proportion is safe because it was never what did the discriminating.
	 * The luminance floor is: measured against the same scene, banners come in at 5/7/9,
	 * signposts at 5/7/16 and javelins at 6/7/8 - all perfect fire hue and saturation,
	 * every one of them failing on brightness alone.
	 */
	static final int MIN_FACES = 2;

	private FlameDetector()
	{
	}

	/**
	 * @param faceColors from {@code Model.getFaceColors1()}, packed HSL per face. Hidden
	 *                   faces come through as -1, which masks to a hue well outside the
	 *                   fire arc and so cannot pass.
	 */
	static boolean looksLikeFlame(int[] faceColors)
	{
		if (faceColors == null)
		{
			return false;
		}

		return burningFaces(faceColors) >= MIN_FACES;
	}

	/** How many faces are painted like fire. Reported by ::lightids so the call is visible. */
	static int burningFaces(int[] faceColors)
	{
		if (faceColors == null)
		{
			return 0;
		}

		int hot = 0;
		for (int c : faceColors)
		{
			if (isFlameColor(c))
			{
				++hot;
			}
		}
		return hot;
	}

	/**
	 * What the test saw, for ::lightids: how many faces passed, out of how many, and the
	 * warm-hued colours it found as hue/sat/lum.
	 *
	 * <p>Reports the warm faces separately from the passing ones because those are two
	 * different failures with two different fixes. No warm faces at all means the flame is
	 * not in this model. Warm faces that did not pass means the thresholds are wrong.
	 */
	static String describe(int[] faceColors)
	{
		if (faceColors == null || faceColors.length == 0)
		{
			return "no faces";
		}

		StringBuilder warm = new StringBuilder();
		int shown = 0;
		int seenWarm = 0;
		for (int c : faceColors)
		{
			int hsl = c & 0xFFFF;
			if (((hsl >> HUE_SHIFT) & HUE_MASK) > MAX_HUE)
			{
				continue;
			}

			++seenWarm;
			if (shown < 4)
			{
				++shown;
				warm.append(' ')
					.append((hsl >> HUE_SHIFT) & HUE_MASK).append('/')
					.append((hsl >> SAT_SHIFT) & SAT_MASK).append('/')
					.append(hsl & LUM_MASK);
			}
		}

		return "fire=" + burningFaces(faceColors) + "/" + faceColors.length
			+ " warm=" + seenWarm + (seenWarm > 0 ? " [" + warm.toString().trim() + "]" : "");
	}

	static boolean isFlameColor(int packed)
	{
		int hsl = packed & 0xFFFF;
		return ((hsl >> HUE_SHIFT) & HUE_MASK) <= MAX_HUE
			&& ((hsl >> SAT_SHIFT) & SAT_MASK) >= MIN_SATURATION
			&& (hsl & LUM_MASK) >= MIN_LUMINANCE;
	}
}
