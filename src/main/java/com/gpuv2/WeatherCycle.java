package com.gpuv2;

import com.gpuv2.config.WeatherMode;

/**
 * Picks the weather automatically as time passes.
 *
 * <p>Kept free of client and OpenGL state so it can be unit tested directly.
 *
 * <p>Each spell occupies a fixed slot of time. Within a slot the weather builds, holds and
 * eases off, so conditions arrive and fade rather than snapping on at full strength - the
 * intensity envelope is what makes an automatic cycle feel like weather instead of a
 * setting changing itself.
 */
final class WeatherCycle
{
	/**
	 * Draw table. Clear weather appears most often; storms and blizzards are rare enough
	 * to stay notable rather than becoming the normal state of the world.
	 */
	private static final WeatherMode[] TABLE = {
		WeatherMode.OFF,
		WeatherMode.OFF,
		WeatherMode.OFF,
		WeatherMode.OFF,
		WeatherMode.OFF,
		WeatherMode.RAIN,
		WeatherMode.RAIN,
		WeatherMode.FOG,
		WeatherMode.SNOW,
		WeatherMode.STORM,
		WeatherMode.BLIZZARD,
	};

	/**
	 * Fraction of a slot spent ramping in, and again ramping out.
	 */
	private static final double RAMP = 0.18;

	private WeatherCycle()
	{
	}

	/**
	 * Weather for the slot containing {@code minutes}.
	 *
	 * @param minutes       elapsed minutes, from any stable origin
	 * @param periodMinutes length of one slot; values below 1 are treated as 1
	 */
	static WeatherMode modeAt(double minutes, int periodMinutes)
	{
		long slot = slotOf(minutes, periodMinutes);
		// Golden-ratio stride decorrelates consecutive slots, so the sequence doesn't
		// visibly march through the table in order.
		double r = frac(slot * 0.6180339887498949);
		int i = (int) (r * TABLE.length);
		return TABLE[Math.min(i, TABLE.length - 1)];
	}

	/**
	 * How far along the current spell is, 0..1: ramping up, holding, then easing off.
	 * Always 0 for clear slots.
	 */
	static float intensityAt(double minutes, int periodMinutes)
	{
		if (modeAt(minutes, periodMinutes) == WeatherMode.OFF)
		{
			return 0f;
		}

		double phase = phaseOf(minutes, periodMinutes);
		if (phase < RAMP)
		{
			return (float) smooth(phase / RAMP);
		}
		if (phase > 1 - RAMP)
		{
			return (float) smooth((1 - phase) / RAMP);
		}
		return 1f;
	}

	private static long slotOf(double minutes, int periodMinutes)
	{
		return (long) Math.floor(minutes / Math.max(1, periodMinutes));
	}

	private static double phaseOf(double minutes, int periodMinutes)
	{
		double p = Math.max(1, periodMinutes);
		double x = minutes / p;
		return x - Math.floor(x);
	}

	/**
	 * Smoothstep, so spells ease in and out instead of ramping linearly.
	 */
	private static double smooth(double t)
	{
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	private static double frac(double v)
	{
		return v - Math.floor(v);
	}
}
