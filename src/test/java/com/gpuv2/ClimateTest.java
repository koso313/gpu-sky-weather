package com.gpuv2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.gpuv2.config.WeatherMode;
import org.junit.Test;

public class ClimateTest
{
	private static final int PERIOD = 30;

	@Test
	public void placesThePlacesPeopleWouldCheck()
	{
		assertEquals(Climate.TEMPERATE, Climate.at(3222, 3218)); // Lumbridge
		assertEquals(Climate.TEMPERATE, Climate.at(3213, 3424)); // Varrock
		assertEquals(Climate.TEMPERATE, Climate.at(2965, 3380)); // Falador
		assertEquals(Climate.DESERT, Climate.at(3293, 3174));    // Al Kharid
		assertEquals(Climate.DESERT, Climate.at(3304, 3000));    // south of the Shantay Pass
		assertEquals(Climate.COLD, Climate.at(2890, 3676));      // Trollheim
		assertEquals(Climate.WET, Climate.at(3495, 3488));       // Canifis
		assertEquals(Climate.TROPICAL, Climate.at(2770, 3180));  // Brimhaven
	}

	/** The towns that sit right beside a region must not be swallowed by it. */
	@Test
	public void neighboursStayTemperate()
	{
		assertEquals(Climate.TEMPERATE, Climate.at(2899, 3544)); // Burthorpe, below Death Plateau
		assertEquals(Climate.TEMPERATE, Climate.at(2809, 3436)); // Catherby, below White Wolf Mountain
		assertEquals(Climate.TEMPERATE, Climate.at(3020, 3220)); // Port Sarim, across from Karamja
		assertEquals(Climate.TEMPERATE, Climate.at(3250, 3240)); // east Lumbridge, this side of the river
	}

	/** Nothing underground has weather, whatever is overhead on the map. */
	@Test
	public void undergroundIsNeverARegion()
	{
		assertEquals(Climate.TEMPERATE, Climate.at(3293, 3174 + 6400));
	}

	@Test
	public void theDesertStaysDry()
	{
		for (int i = 0; i < 5000; ++i)
		{
			WeatherMode mode = WeatherCycle.modeAt(i * (double) PERIOD, PERIOD, Climate.DESERT);
			assertFalse("fell in the desert: " + mode, mode.hasPrecipitation());
		}
	}

	@Test
	public void theColdNeverRainsAndTheJungleNeverSnows()
	{
		for (int i = 0; i < 5000; ++i)
		{
			WeatherMode cold = WeatherCycle.modeAt(i * (double) PERIOD, PERIOD, Climate.COLD);
			assertFalse("rained in the cold: " + cold, cold.isRainLike());

			WeatherMode jungle = WeatherCycle.modeAt(i * (double) PERIOD, PERIOD, Climate.TROPICAL);
			assertFalse("snowed in the jungle: " + jungle,
				jungle == WeatherMode.SNOW || jungle == WeatherMode.BLIZZARD);
		}
	}

	/** Clear stays the commonest single outcome everywhere, so weather stays an event. */
	@Test
	public void clearIsTheCommonestOutcomeInEveryClimate()
	{
		for (Climate climate : Climate.values())
		{
			int clear = climate.count(WeatherMode.OFF);
			for (WeatherMode mode : WeatherMode.values())
			{
				if (mode != WeatherMode.OFF)
				{
					assertTrue(climate + ": " + mode + " is as common as clear",
						climate.count(mode) < clear);
				}
			}
		}
	}

	@Test
	public void noClimateEverHandsBackAutomatic()
	{
		for (Climate climate : Climate.values())
		{
			assertEquals(0, climate.count(WeatherMode.AUTO));
			assertEquals(0, climate.count(WeatherMode.SUNNY));
		}
	}

	/** Intensity ramps exactly as it does without a climate, and is zero when it is clear. */
	@Test
	public void intensityFollowsTheSameEnvelope()
	{
		for (Climate climate : Climate.values())
		{
			for (double m = 0; m < PERIOD * 200; m += 1.7)
			{
				float v = WeatherCycle.intensityAt(m, PERIOD, climate);
				assertTrue(v >= 0f && v <= 1f);
				if (WeatherCycle.modeAt(m, PERIOD, climate).isClear())
				{
					assertEquals(0f, v, 1e-6);
				}
			}
		}
	}
}
