package com.gpuv2;

import java.time.LocalTime;

/**
 * Maps a time of day onto a sky colour, interpolating between keyframes.
 *
 * <p>Kept free of any client or OpenGL state so it can be unit tested directly.
 */
public final class SkyGradient
{
	private static final int MINUTES_PER_DAY = 24 * 60;

	/**
	 * Keyframe times, in minutes past midnight. Must be sorted ascending.
	 */
	private static final int[] TIMES = {
		0,    // 00:00 night
		300,  // 05:00 last of the night
		375,  // 06:15 sunrise
		450,  // 07:30 morning
		780,  // 13:00 midday
		1020, // 17:00 afternoon
		1140, // 19:00 sunset
		1245, // 20:45 dusk
		1380, // 23:00 night again
	};

	/**
	 * Sky colour at each keyframe, packed 0xRRGGBB. One longer than {@link #TIMES}: the
	 * trailing entry repeats the first so the segment that wraps past midnight can
	 * interpolate into it.
	 */
	private static final int[] COLORS = {
		0x070B1E,
		0x1B2A55,
		0xE0764B,
		0x9FD0F0,
		0x7FC4EC,
		0x8FC0E4,
		0xE0764B,
		0x1B2A55,
		0x070B1E,
		0x070B1E, // wraps onto COLORS[0]
	};

	/**
	 * How "night" each keyframe is, 0 = full daylight, 1 = full dark. Drives the starfield
	 * and, once it exists, the scene lighting - so the world dims in step with the sky.
	 */
	private static final float[] NIGHT = {
		1f,
		1f,
		0.55f,
		0f,
		0f,
		0f,
		0.55f,
		1f,
		1f,
		1f, // wraps onto NIGHT[0]
	};

	private SkyGradient()
	{
	}

	/**
	 * Sky colour for the given time, packed 0xRRGGBB.
	 */
	public static int colorAt(LocalTime time)
	{
		int minute = minuteOfDay(time);
		int i = segmentStart(minute);
		float t = segmentProgress(minute, i);
		return lerpColor(COLORS[i], COLORS[i + 1], t);
	}

	/**
	 * 0 at full daylight, 1 at full dark.
	 */
	public static float nightFactorAt(LocalTime time)
	{
		int minute = minuteOfDay(time);
		int i = segmentStart(minute);
		float t = segmentProgress(minute, i);
		return NIGHT[i] + (NIGHT[i + 1] - NIGHT[i]) * t;
	}

	private static int minuteOfDay(LocalTime time)
	{
		return time.getHour() * 60 + time.getMinute();
	}

	/**
	 * Index of the keyframe at or before {@code minute}. The final segment wraps past
	 * midnight back onto the first keyframe, which is why COLORS/NIGHT repeat their
	 * first entry at the end.
	 */
	private static int segmentStart(int minute)
	{
		for (int i = TIMES.length - 1; i >= 0; --i)
		{
			if (minute >= TIMES[i])
			{
				return i;
			}
		}
		// Before the first keyframe (i.e. exactly 00:00 is covered above, so this is
		// unreachable for valid input) - fall back to the wrapping segment.
		return TIMES.length - 1;
	}

	private static float segmentProgress(int minute, int i)
	{
		int start = TIMES[i];
		int end = i + 1 < TIMES.length ? TIMES[i + 1] : TIMES[0] + MINUTES_PER_DAY;
		int span = end - start;
		if (span <= 0)
		{
			return 0f;
		}
		return (minute - start) / (float) span;
	}

	private static int lerpColor(int a, int b, float t)
	{
		int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
		int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return (r & 0xFF) << 16 | (g & 0xFF) << 8 | (bl & 0xFF);
	}
}
