package com.gpuskyweather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NormalPackingTest
{
	/**
	 * Mirrors the decode in vert.glsl. Kept here rather than only in the shader so the round
	 * trip can actually be measured - a packing whose error nobody has checked is a packing
	 * that lights everything slightly wrong.
	 */
	private static float[] unpack(int packed)
	{
		float x = (byte) ((packed >> 8) & 0xFF) / 127f;
		float y = (byte) (packed & 0xFF) / 127f;
		float z = 1f - Math.abs(x) - Math.abs(y);

		if (z < 0)
		{
			float ox = x;
			x = (1f - Math.abs(y)) * Math.signum(ox == 0 ? 1 : ox);
			y = (1f - Math.abs(ox)) * Math.signum(y == 0 ? 1 : y);
		}

		float len = (float) Math.sqrt(x * x + y * y + z * z);
		return new float[]{x / len, y / len, z / len};
	}

	private static double angleError(float x, float y, float z)
	{
		float[] out = unpack(NormalPacking.pack(x, y, z));

		float len = (float) Math.sqrt(x * x + y * y + z * z);
		double dot = (x / len) * out[0] + (y / len) * out[1] + (z / len) * out[2];
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
	}

	@Test
	public void axesSurviveTheRoundTrip()
	{
		assertTrue(angleError(0, 1, 0) < 1.0);
		assertTrue(angleError(0, -1, 0) < 1.0);
		assertTrue(angleError(1, 0, 0) < 1.0);
		assertTrue(angleError(-1, 0, 0) < 1.0);
		assertTrue(angleError(0, 0, 1) < 1.0);
		assertTrue(angleError(0, 0, -1) < 1.0);
	}

	/**
	 * The error has to be small everywhere, not just on the axes. Octahedral encoding is
	 * chosen precisely because it has no pole where accuracy collapses, so a sweep is the
	 * test that means something.
	 */
	@Test
	public void errorStaysSmallOverTheWholeSphere()
	{
		double worst = 0;

		for (int i = 0; i < 64; ++i)
		{
			for (int j = 0; j < 64; ++j)
			{
				double theta = Math.PI * 2 * i / 64;
				double phi = Math.PI * (j + 0.5) / 64;

				float x = (float) (Math.sin(phi) * Math.cos(theta));
				float y = (float) Math.cos(phi);
				float z = (float) (Math.sin(phi) * Math.sin(theta));

				worst = Math.max(worst, angleError(x, y, z));
			}
		}

		assertTrue("worst case " + worst + " degrees", worst < 1.5);
	}

	/** Zero length means "no normal", which the shader falls back on rather than lighting. */
	@Test
	public void aZeroVectorPacksToNone()
	{
		assertEquals(NormalPacking.NONE, NormalPacking.pack(0, 0, 0));
	}

	/**
	 * No real direction may collide with the sentinel, or that vertex silently loses its
	 * normal and lights differently to the ones beside it.
	 */
	@Test
	public void noRealDirectionPacksToNone()
	{
		for (int i = 0; i < 200; ++i)
		{
			for (int j = 0; j < 200; ++j)
			{
				double theta = Math.PI * 2 * i / 200;
				double phi = Math.PI * (j + 0.5) / 200;

				float x = (float) (Math.sin(phi) * Math.cos(theta));
				float y = (float) Math.cos(phi);
				float z = (float) (Math.sin(phi) * Math.sin(theta));

				assertNotEquals("collided with the sentinel", NormalPacking.NONE,
					NormalPacking.pack(x, y, z));
			}
		}
	}

	/** Magnitude carries no information, so scaling the input must not change the result. */
	@Test
	public void lengthDoesNotMatter()
	{
		assertEquals(NormalPacking.pack(0.3f, 0.5f, -0.8f),
			NormalPacking.pack(30f, 50f, -80f));
	}

	/** It has to fit the 16-bit slot it was written for. */
	@Test
	public void packsIntoSixteenBits()
	{
		assertTrue(NormalPacking.pack(0.577f, 0.577f, 0.577f) <= 0xFFFF);
		assertTrue(NormalPacking.pack(-0.577f, -0.577f, -0.577f) <= 0xFFFF);
		assertTrue(NormalPacking.pack(0.1f, -0.9f, 0.4f) >= 0);
	}
}
