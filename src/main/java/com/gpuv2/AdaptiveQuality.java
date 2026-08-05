package com.gpuv2;

import javax.inject.Singleton;

/**
 * Sheds expensive passes when the frame rate falls short, and restores them when it
 * recovers.
 *
 * <p>Steps rather than a continuous dial, so the effects that go are always the same ones
 * in the same order and the result is predictable. Post-processing goes first because it is
 * the most expensive and the least missed; the sky drops to half resolution next, which is
 * nearly invisible; and only then does the light budget get cut, which is noticeable.
 *
 * <p>Deliberately asymmetric. Dropping quality is quick, because the frame rate is already
 * bad and waiting makes it worse for longer. Restoring is slow and requires comfortable
 * headroom, because restoring the moment the target is met puts back exactly the load that
 * broke it - which drops it again, restores again, and leaves the picture visibly pulsing.
 * That oscillation is the failure mode this design exists to avoid.
 */
@Singleton
class AdaptiveQuality
{
	/** No effects shed. */
	static final int LEVEL_FULL = 0;

	/** Post-processing off: bloom, god rays and the image pass. */
	static final int LEVEL_NO_POST = 1;

	/** Sky at half resolution as well. */
	static final int LEVEL_LOW_SKY = 2;

	/** Light budget cut too. The last resort, and the first thing anyone would notice. */
	static final int LEVEL_FEW_LIGHTS = 3;

	static final int MAX_LEVEL = LEVEL_FEW_LIGHTS;

	/**
	 * How far above target the frame rate must sit before anything is given back. Restoring
	 * at exactly the target guarantees the load that broke it comes straight back.
	 */
	private static final double RESTORE_MARGIN = 1.25;

	/** Seconds below target before shedding, and above it before restoring. */
	private static final double DROP_AFTER = 1.0;
	private static final double RESTORE_AFTER = 6.0;

	private int level = LEVEL_FULL;
	private double belowSeconds;
	private double aboveSeconds;

	int level()
	{
		return level;
	}

	void reset()
	{
		level = LEVEL_FULL;
		belowSeconds = 0;
		aboveSeconds = 0;
	}

	/**
	 * Steps the controller.
	 *
	 * @param fps         frame rate over a recent window, not a single frame - one slow
	 *                    frame is not a reason to change anything
	 * @param targetFps   the rate to hold
	 * @param deltaSeconds wall time since the last call
	 */
	void update(double fps, int targetFps, double deltaSeconds)
	{
		if (targetFps <= 0 || fps <= 0)
		{
			return;
		}

		if (fps < targetFps)
		{
			aboveSeconds = 0;
			belowSeconds += deltaSeconds;

			if (belowSeconds >= DROP_AFTER && level < MAX_LEVEL)
			{
				++level;
				belowSeconds = 0;
			}
		}
		else if (fps > targetFps * RESTORE_MARGIN)
		{
			belowSeconds = 0;
			aboveSeconds += deltaSeconds;

			if (aboveSeconds >= RESTORE_AFTER && level > LEVEL_FULL)
			{
				--level;
				aboveSeconds = 0;
			}
		}
		else
		{
			// Between the target and the restore margin: the settings are doing their job,
			// so neither timer should be running.
			belowSeconds = 0;
			aboveSeconds = 0;
		}
	}

	boolean postProcessingAllowed()
	{
		return level < LEVEL_NO_POST;
	}

	boolean fullResolutionSkyAllowed()
	{
		return level < LEVEL_LOW_SKY;
	}

	/**
	 * Light budget after any cut. Quartered rather than switched off - going dark entirely
	 * would be far more jarring than a thinner set of lights.
	 */
	int lightBudget(int configured)
	{
		return level < LEVEL_FEW_LIGHTS ? configured : Math.max(4, configured / 4);
	}
}
