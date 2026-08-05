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
 * heavily saturated orange that almost nothing else in the game uses at that intensity -
 * and critically, the test reads {@code getUnlitFaceColors()} rather than the lit ones.
 * The lit colours have already had scene lighting folded into them, which flattens hue and
 * saturation towards a plain brightness value; judging hue from those would be reading a
 * number that is no longer there.
 *
 * <p>Colour alone is not enough - a yellow banner is bright and orange-ish too - so
 * {@link LightScanner} only asks this of objects that are also animated. Fire flickers;
 * banners do not.
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
	 * And it is bright. Together with the saturation floor this is the whole discriminator:
	 * dark orange is timber, pale orange is sand, but bright saturated orange is burning.
	 */
	static final int MIN_LUMINANCE = 70;

	/**
	 * How much of the model has to be alight. A torch is mostly bracket, so this is
	 * deliberately low - but not zero, or any object with a single warm highlight qualifies.
	 */
	static final float MIN_FRACTION = 0.12f;

	/** Below this, a "fraction" of a handful of faces is noise rather than evidence. */
	static final int MIN_FACES = 3;

	private FlameDetector()
	{
	}

	/**
	 * @param unlitFaceColors from {@code Model.getUnlitFaceColors()}, packed HSL per face
	 */
	static boolean looksLikeFlame(short[] unlitFaceColors)
	{
		if (unlitFaceColors == null)
		{
			return false;
		}

		int hot = burningFaces(unlitFaceColors);
		return hot >= MIN_FACES && hot >= unlitFaceColors.length * MIN_FRACTION;
	}

	/** How many faces are painted like fire. Reported by ::lightids so the call is visible. */
	static int burningFaces(short[] unlitFaceColors)
	{
		if (unlitFaceColors == null)
		{
			return 0;
		}

		int hot = 0;
		for (short c : unlitFaceColors)
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
	static String describe(short[] unlitFaceColors)
	{
		if (unlitFaceColors == null || unlitFaceColors.length == 0)
		{
			return "no faces";
		}

		StringBuilder warm = new StringBuilder();
		int shown = 0;
		int seenWarm = 0;
		for (short c : unlitFaceColors)
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

		return "fire=" + burningFaces(unlitFaceColors) + "/" + unlitFaceColors.length
			+ " warm=" + seenWarm + (seenWarm > 0 ? " [" + warm.toString().trim() + "]" : "");
	}

	static boolean isFlameColor(short packed)
	{
		int hsl = packed & 0xFFFF;
		return ((hsl >> HUE_SHIFT) & HUE_MASK) <= MAX_HUE
			&& ((hsl >> SAT_SHIFT) & SAT_MASK) >= MIN_SATURATION
			&& (hsl & LUM_MASK) >= MIN_LUMINANCE;
	}
}
