package com.gpuv2;

import com.gpuv2.config.WeatherMode;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WeatherCycleTest
{
	private static final int PERIOD = 12;

	@Test
	public void modeIsStableWithinASlot()
	{
		WeatherMode start = WeatherCycle.modeAt(0.0, PERIOD);
		for (double m = 0; m < PERIOD; m += 0.25)
		{
			assertEquals("weather changed mid-slot at " + m, start, WeatherCycle.modeAt(m, PERIOD));
		}
	}

	@Test
	public void intensityIsZeroAtBothEndsOfASpell()
	{
		// Find a slot that actually has weather.
		int slot = -1;
		for (int i = 0; i < 200; ++i)
		{
			if (WeatherCycle.modeAt(i * (double) PERIOD, PERIOD) != WeatherMode.OFF)
			{
				slot = i;
				break;
			}
		}
		assertTrue("no non-clear slot found in 200 tries", slot >= 0);

		double base = slot * (double) PERIOD;
		assertEquals(0f, WeatherCycle.intensityAt(base, PERIOD), 0.001f);
		assertEquals(1f, WeatherCycle.intensityAt(base + PERIOD * 0.5, PERIOD), 0.001f);
		// Just before the slot ends it should have eased back to nearly nothing.
		assertTrue(WeatherCycle.intensityAt(base + PERIOD * 0.999, PERIOD) < 0.05f);
	}

	@Test
	public void clearSlotsHaveNoIntensity()
	{
		for (int i = 0; i < 200; ++i)
		{
			double m = i * (double) PERIOD + PERIOD * 0.5;
			if (WeatherCycle.modeAt(m, PERIOD) == WeatherMode.OFF)
			{
				assertEquals(0f, WeatherCycle.intensityAt(m, PERIOD), 0.001f);
			}
		}
	}

	@Test
	public void intensityAlwaysInRange()
	{
		for (double m = 0; m < PERIOD * 100; m += 0.37)
		{
			float v = WeatherCycle.intensityAt(m, PERIOD);
			assertTrue("intensity out of range at " + m, v >= 0f && v <= 1f);
		}
	}

	/**
	 * Clear should be the most common condition - a world that is raining most of the time
	 * reads as broken rather than atmospheric.
	 */
	@Test
	public void clearWeatherIsTheMostCommonOutcome()
	{
		Map<WeatherMode, Integer> counts = new HashMap<>();
		for (int i = 0; i < 1000; ++i)
		{
			WeatherMode mode = WeatherCycle.modeAt(i * (double) PERIOD, PERIOD);
			counts.merge(mode, 1, Integer::sum);
		}

		int clear = counts.getOrDefault(WeatherMode.OFF, 0);
		assertTrue("clear should be most common, got " + counts, clear > 1000 / 3);

		for (Map.Entry<WeatherMode, Integer> e : counts.entrySet())
		{
			if (e.getKey() != WeatherMode.OFF)
			{
				assertTrue(e.getKey() + " outnumbers clear", e.getValue() < clear);
			}
		}
	}

	@Test
	public void guardsAgainstZeroPeriod()
	{
		// Should not divide by zero or loop forever.
		WeatherCycle.modeAt(5.0, 0);
		WeatherCycle.intensityAt(5.0, 0);
	}
}
