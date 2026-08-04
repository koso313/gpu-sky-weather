package com.gpuv2;

import java.time.LocalTime;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyGradientTest
{
	/**
	 * Every minute of the day must resolve without falling off the end of the keyframe
	 * arrays - the wrapping segment past the last keyframe is the easy one to get wrong.
	 */
	@Test
	public void everyMinuteOfDayResolves()
	{
		for (int m = 0; m < 24 * 60; ++m)
		{
			LocalTime t = LocalTime.of(m / 60, m % 60);
			int color = SkyGradient.colorAt(t);
			assertTrue("colour out of range at " + t, color >= 0 && color <= 0xFFFFFF);

			float night = SkyGradient.nightFactorAt(t);
			assertTrue("night factor out of range at " + t, night >= 0f && night <= 1f);
		}
	}

	@Test
	public void wrapsContinuouslyAcrossMidnight()
	{
		// 23:59 -> 00:00 should be a small step, not a jump back through the whole gradient.
		int before = SkyGradient.colorAt(LocalTime.of(23, 59));
		int after = SkyGradient.colorAt(LocalTime.of(0, 0));
		assertTrue("discontinuity across midnight", channelDistance(before, after) <= 4);
	}

	@Test
	public void middayIsDaylightAndMidnightIsDark()
	{
		assertEquals(0f, SkyGradient.nightFactorAt(LocalTime.of(13, 0)), 0.001f);
		assertEquals(1f, SkyGradient.nightFactorAt(LocalTime.of(0, 0)), 0.001f);
	}

	@Test
	public void middayIsBrighterThanMidnight()
	{
		assertTrue(luminance(SkyGradient.colorAt(LocalTime.of(13, 0)))
			> luminance(SkyGradient.colorAt(LocalTime.of(0, 0))));
	}

	@Test
	public void keyframeTimesLandExactlyOnTheirColour()
	{
		// 13:00 is a keyframe, so it should return that keyframe's colour untouched.
		assertEquals(0x7FC4EC, SkyGradient.colorAt(LocalTime.of(13, 0)));
	}

	private static int channelDistance(int a, int b)
	{
		return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF))
			+ Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF))
			+ Math.abs((a & 0xFF) - (b & 0xFF));
	}

	private static int luminance(int c)
	{
		return (c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF);
	}
}
