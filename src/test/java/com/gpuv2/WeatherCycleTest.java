package com.gpuv2;

import com.gpuv2.config.WeatherMode;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

		/*
		 * A clear majority, not merely the largest share. Weather has to be the exception
		 * for a storm arriving to be worth noticing, and an earlier table that left clear at
		 * 42% meant most slots had something going on - which reads as a permanently unsettled
		 * sky rather than as weather.
		 */
		assertTrue("clear should be a majority of slots, got " + counts, clear > 550);

		for (Map.Entry<WeatherMode, Integer> e : counts.entrySet())
		{
			if (e.getKey() != WeatherMode.OFF)
			{
				assertTrue(e.getKey() + " outnumbers clear", e.getValue() < clear);
			}
		}
	}

	/**
	 * Storms and blizzards have to stay rare, or the thing they are for - being an event -
	 * stops working. Pinned so a later reshuffle of the table cannot quietly promote them.
	 */
	@Test
	public void severeWeatherStaysRare()
	{
		int severe = 0;
		for (int i = 0; i < 1000; ++i)
		{
			WeatherMode mode = WeatherCycle.modeAt(i * (double) PERIOD, PERIOD);
			if (mode == WeatherMode.STORM || mode == WeatherMode.BLIZZARD)
			{
				++severe;
			}
		}

		assertTrue("severe weather in " + severe + "/1000 slots", severe < 150);
	}

	/**
	 * AUTO is the user's selection, not a condition. If the cycle ever returned it the
	 * renderer would try to draw it as real weather.
	 */
	@Test
	public void cycleNeverReturnsAuto()
	{
		for (int i = 0; i < 2000; ++i)
		{
			assertTrue("cycle returned AUTO at slot " + i,
				WeatherCycle.modeAt(i * (double) PERIOD, PERIOD) != WeatherMode.AUTO);
		}
	}

	/**
	 * Overcast is cloud and nothing else. If it ever reported precipitation the particle
	 * pass would run with nothing sensible to draw.
	 */
	@Test
	public void overcastHasNoPrecipitation()
	{
		assertFalse(WeatherMode.OVERCAST.hasPrecipitation());
		assertFalse(WeatherMode.OVERCAST.hasLightning());
	}

	/**
	 * Only overcast closes the sky over. Everything else is capped short of it on purpose,
	 * so picking blizzard does not blot out the sun and moon as a side effect of the snow.
	 */
	@Test
	public void onlyOvercastSealsTheSky()
	{
		assertTrue(WeatherMode.OVERCAST.sealsSky());

		for (WeatherMode m : WeatherMode.values())
		{
			if (m != WeatherMode.OVERCAST)
			{
				assertFalse(m + " should not seal the sky", m.sealsSky());
			}
		}
	}

	/** Every real condition must declare precipitation one way or the other, not by accident. */
	@Test
	public void onlyFallingWeatherPrecipitates()
	{
		assertFalse(WeatherMode.OFF.hasPrecipitation());
		assertFalse(WeatherMode.AUTO.hasPrecipitation());
		assertTrue(WeatherMode.RAIN.hasPrecipitation());
		assertTrue(WeatherMode.STORM.hasPrecipitation());
		assertTrue(WeatherMode.SNOW.hasPrecipitation());
		assertTrue(WeatherMode.BLIZZARD.hasPrecipitation());
	}

	/**
	 * A storm with a visible sun does not read as a storm. Rain leaves it as a smear behind
	 * the cloud rather than removing it, which is the difference between the two.
	 */
	@Test
	public void weatherHidesTheSunBySeverity()
	{
		assertEquals(0f, WeatherMode.OFF.sunHiding(), 1e-6);
		assertEquals(1f, WeatherMode.STORM.sunHiding(), 1e-6);
		assertEquals(1f, WeatherMode.OVERCAST.sunHiding(), 1e-6);

		float rain = WeatherMode.RAIN.sunHiding();
		assertTrue("rain should dim the sun", rain > 0f);
		assertTrue("rain should not remove it", rain < 1f);
	}

	@Test
	public void guardsAgainstZeroPeriod()
	{
		// Should not divide by zero or loop forever.
		WeatherCycle.modeAt(5.0, 0);
		WeatherCycle.intensityAt(5.0, 0);
	}
}
