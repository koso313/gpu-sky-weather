package com.gpuv2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AdaptiveQualityTest
{
	private static final int TARGET = 60;

	/** Runs the controller at a steady frame rate for a number of seconds. */
	private static void run(AdaptiveQuality q, double fps, double seconds)
	{
		for (double t = 0; t < seconds; t += 0.1)
		{
			q.update(fps, TARGET, 0.1);
		}
	}

	@Test
	public void doesNothingWhileTheTargetIsMet()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		run(q, 65, 30);
		assertEquals(AdaptiveQuality.LEVEL_FULL, q.level());
	}

	@Test
	public void shedsOneLevelAtATimeUnderLoad()
	{
		AdaptiveQuality q = new AdaptiveQuality();

		run(q, 30, 1.5);
		assertEquals("post should go first", AdaptiveQuality.LEVEL_NO_POST, q.level());
		assertFalse(q.postProcessingAllowed());
		assertTrue("the sky should still be full", q.fullResolutionSkyAllowed());

		run(q, 30, 1.5);
		assertEquals(AdaptiveQuality.LEVEL_LOW_SKY, q.level());
		assertFalse(q.fullResolutionSkyAllowed());

		run(q, 30, 1.5);
		assertEquals("lights are the last resort", AdaptiveQuality.LEVEL_FEW_LIGHTS, q.level());
		assertTrue(q.lightBudget(64) < 64);
	}

	@Test
	public void neverShedsPastTheLastLevel()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		run(q, 5, 120);
		assertEquals(AdaptiveQuality.MAX_LEVEL, q.level());
	}

	/**
	 * The failure this design exists to avoid. Restoring the moment the target is met puts
	 * back exactly the load that broke it, which drops it again - and the picture pulses.
	 * Sitting right at the target must therefore change nothing in either direction.
	 */
	@Test
	public void doesNotOscillateAtTheTarget()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		run(q, 30, 5);

		int shed = q.level();
		assertTrue("should have shed something", shed > AdaptiveQuality.LEVEL_FULL);

		// Exactly on target for a long time: no further shedding, and nothing given back.
		run(q, TARGET, 60);
		assertEquals("should hold steady at the target", shed, q.level());
	}

	@Test
	public void restoresOnlyWithComfortableHeadroom()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		run(q, 20, 5);
		int shed = q.level();

		// Just over target is not enough - that is the rate the shedding bought.
		run(q, TARGET + 1, 30);
		assertEquals("marginally over target should not restore", shed, q.level());

		// Well clear of it, sustained, does restore.
		run(q, TARGET * 3, 30);
		assertTrue("should have given something back", q.level() < shed);
	}

	@Test
	public void aBriefDipIsNotEnoughToShed()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		run(q, 100, 5);
		q.update(10, TARGET, 0.1);
		assertEquals(AdaptiveQuality.LEVEL_FULL, q.level());
	}

	/** Turning it off has to give everything back, not freeze at whatever it had shed. */
	@Test
	public void resetRestoresEverything()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		run(q, 10, 30);
		assertTrue(q.level() > AdaptiveQuality.LEVEL_FULL);

		q.reset();
		assertEquals(AdaptiveQuality.LEVEL_FULL, q.level());
		assertTrue(q.postProcessingAllowed());
		assertTrue(q.fullResolutionSkyAllowed());
		assertEquals(64, q.lightBudget(64));
	}

	@Test
	public void aDisabledTargetChangesNothing()
	{
		AdaptiveQuality q = new AdaptiveQuality();
		for (int i = 0; i < 1000; ++i)
		{
			q.update(5, 0, 0.1);
		}
		assertEquals(AdaptiveQuality.LEVEL_FULL, q.level());
	}
}
