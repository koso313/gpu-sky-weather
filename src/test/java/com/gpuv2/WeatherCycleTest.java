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
	 * The deck closes up with severity. Clear weather must never seal, or a sunny day would
	 * arrive with a lid on it.
	 */
	@Test
	public void theDeckClosesUpWithSeverity()
	{
		assertEquals(0f, WeatherMode.OFF.cloudSealing(), 1e-6);
		assertFalse(WeatherMode.OFF.sealsSky());

		assertTrue("overcast should seal", WeatherMode.OVERCAST.sealsSky());
		assertTrue("a blizzard should be more closed in than snow",
			WeatherMode.BLIZZARD.cloudSealing() > WeatherMode.SNOW.cloudSealing());
		assertTrue("a storm should be more closed in than rain",
			WeatherMode.STORM.cloudSealing() > WeatherMode.RAIN.cloudSealing());

		for (WeatherMode m : WeatherMode.values())
		{
			assertTrue(m + " seals past solid", m.cloudSealing() <= 1f);
		}
	}

	/**
	 * Snow and blizzard were first shipped with the sun untouched, on the reasoning that
	 * their skies are bright rather than dark. In game that came out blown white with the
	 * sun blazing through it. Pale is not the same as bright: they are heavily overcast days
	 * that happen to be light in colour.
	 */
	@Test
	public void snowSkiesArePaleNotSunlit()
	{
		assertTrue("snow should hide the sun", WeatherMode.SNOW.sunHiding() > 0.8f);
		assertTrue("a blizzard should hide it further",
			WeatherMode.BLIZZARD.sunHiding() > WeatherMode.SNOW.sunHiding());
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

		/*
		 * Rain has to hide nearly all of it. The disc is drawn incandescent, so a merely
		 * halved one is still a bright white spot in the sky - 0.7 was tried and the result
		 * read as a sunny day with rain falling through it.
		 */
		float rain = WeatherMode.RAIN.sunHiding();
		assertTrue("rain should nearly hide the sun, was " + rain, rain > 0.85f);
		assertTrue("rain should not remove it outright", rain < 1f);
	}

	/**
	 * Hiding the sun is only half of it. Ground still lit as though the sun were out is what
	 * made rain read as a sunny day with water falling through it.
	 */
	@Test
	public void wetWeatherDarkensTheWorldUnderIt()
	{
		assertEquals(0f, WeatherMode.OFF.gloom(), 1e-6);
		assertTrue("rain should darken", WeatherMode.RAIN.gloom() > 0f);
		assertTrue("a storm should be darker than rain",
			WeatherMode.STORM.gloom() > WeatherMode.RAIN.gloom());

		// Readable, not unplayable.
		for (WeatherMode m : WeatherMode.values())
		{
			assertTrue(m + " darkens too far", m.gloom() < 0.5f);
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
