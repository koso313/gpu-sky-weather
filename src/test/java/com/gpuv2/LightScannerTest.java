package com.gpuv2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LightScannerTest
{
	@Test
	public void matchesCommonLightSources()
	{
		String[] lights = {
			"Fire", "Campfire", "Bonfire", "Fireplace", "Torch", "Lit torch",
			"Lantern", "Candles", "Brazier", "Lamp", "Furnace", "Forge",
			"Wall torch", "Burning brazier",
		};

		for (String name : lights)
		{
			assertTrue(name + " should light", LightScanner.nameSuggestsLight(name));
		}
	}

	/**
	 * Substring matching is broad on purpose, so the traps it creates have to stay
	 * excluded - these all contain a light word without being lights.
	 */
	@Test
	public void doesNotMatchThingsThatMerelySoundLikeLights()
	{
		String[] notLights = {
			"Fire rune", "Firemaking stall", "Fire altar", "Unlit torch",
			"Burnt tree", "Lamp post",
		};

		for (String name : notLights)
		{
			assertFalse(name + " should not light", LightScanner.nameSuggestsLight(name));
		}
	}

	/**
	 * Objects with no name come back as null or the literal string "null" from the cache,
	 * and neither should be treated as a name.
	 */
	@Test
	public void handlesMissingNames()
	{
		assertFalse(LightScanner.nameSuggestsLight(null));
		assertFalse(LightScanner.nameSuggestsLight(""));
		assertFalse(LightScanner.nameSuggestsLight("null"));
	}

	@Test
	public void ignoresCase()
	{
		assertTrue(LightScanner.nameSuggestsLight("TORCH"));
		assertTrue(LightScanner.nameSuggestsLight("bRaZiEr"));
	}

	@Test
	public void ordinarySceneryDoesNotLight()
	{
		String[] scenery = {"Tree", "Rock", "Door", "Table", "Bank booth", "Fence"};

		for (String name : scenery)
		{
			assertFalse(name + " should not light", LightScanner.nameSuggestsLight(name));
		}
	}
}
