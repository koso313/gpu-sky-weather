package com.gpuskyweather;

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

	// How saturated and bright a face has to be to count as glowing, for any hue. Looser
	// than the fire thresholds: those have to tell a flame from a yellow banner, while
	// these only run on objects already known to be lights.
	private static final int GLOW_MIN_SATURATION = 4;
	private static final int GLOW_MIN_LUMINANCE = 60;

	// Red through yellow, and the top of the wheel where it comes back round to red.
	private static final int FIRE_HUE_MAX = 14;
	private static final int FIRE_HUE_WRAP = 61;

	/**
	 * The colour a light source glows, packed 0xRRGGBB, or -1 if it is ordinary fire or
	 * shows no glow of its own.
	 *
	 * <p>Fire is deliberately not given a colour here. Every shade of orange and yellow a
	 * torch is painted in is still firelight, and that has one colour chosen in settings;
	 * reporting each model's own orange would only make a row of torches slightly different
	 * from one another for no reason anyone could see. What this is for is the light that is
	 * plainly something else - a blue spirit flame, a green lantern - where lighting the
	 * ground orange is simply wrong.
	 *
	 * <p>Textured faces carry a light level in place of a colour and read as hue and
	 * saturation zero, so the saturation floor leaves them out without a separate check.
	 */
	static int glowRgb(int[] faceColors)
	{
		if (faceColors == null)
		{
			return -1;
		}

		int[] byHue = new int[HUE_MASK + 1];
		int fire = 0;
		int other = 0;
		for (int c : faceColors)
		{
			int hsl = c & 0xFFFF;
			int hue = (hsl >> HUE_SHIFT) & HUE_MASK;
			if (((hsl >> SAT_SHIFT) & SAT_MASK) < GLOW_MIN_SATURATION || (hsl & LUM_MASK) < GLOW_MIN_LUMINANCE)
			{
				continue;
			}

			if (hue <= FIRE_HUE_MAX || hue >= FIRE_HUE_WRAP)
			{
				++fire;
			}
			else
			{
				++other;
				++byHue[hue];
			}
		}

		// Something that is mostly flame with a coloured fitting is still a fire.
		if (other < MIN_FACES || other < fire)
		{
			return -1;
		}

		int peak = 0;
		for (int h = 0; h < byHue.length; ++h)
		{
			if (byHue[h] > byHue[peak])
			{
				peak = h;
			}
		}

		// Averaged over the faces near the commonest hue, so shading on the model does not
		// pull the answer about.
		float hueSum = 0f;
		float satSum = 0f;
		int n = 0;
		for (int c : faceColors)
		{
			int hsl = c & 0xFFFF;
			int hue = (hsl >> HUE_SHIFT) & HUE_MASK;
			int sat = (hsl >> SAT_SHIFT) & SAT_MASK;
			if (sat < GLOW_MIN_SATURATION || (hsl & LUM_MASK) < GLOW_MIN_LUMINANCE || Math.abs(hue - peak) > 3)
			{
				continue;
			}
			hueSum += hue;
			satSum += sat;
			++n;
		}
		if (n == 0)
		{
			return -1;
		}

		// Full brightness: how bright the light is belongs to the strength setting, and only
		// the hue and how vivid it is are taken from the model.
		float h = (hueSum / n + 0.5f) / (HUE_MASK + 1);
		float s = Math.min(1f, (satSum / n + 0.5f) / (SAT_MASK + 1));
		return java.awt.Color.HSBtoRGB(h, s * 0.85f, 1f) & 0xFFFFFF;
	}

	static boolean isFlameColor(int packed)
	{
		int hsl = packed & 0xFFFF;
		return ((hsl >> HUE_SHIFT) & HUE_MASK) <= MAX_HUE
			&& ((hsl >> SAT_SHIFT) & SAT_MASK) >= MIN_SATURATION
			&& (hsl & LUM_MASK) >= MIN_LUMINANCE;
	}
}
