package com.gpuv2;

import static org.junit.Assert.assertEquals;
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

	/**
	 * The fade is what stops a torch snapping off the moment it leaves the scan patch, so
	 * pin its shape: full brightness well inside, nothing at the boundary, and no values
	 * outside 0..1 for anything beyond it.
	 */
	@Test
	public void lightsFadeOutTowardsTheEdgeOfTheScan()
	{
		assertEquals("close up", 1f, LightScanner.fadeAt(0f), 1e-6);
		assertEquals("inside the band", 1f, LightScanner.fadeAt(19f), 1e-6);
		assertEquals("band starts", 1f, LightScanner.fadeAt(20f), 1e-6);
		assertEquals("half way through", 0.5f, LightScanner.fadeAt(22.5f), 1e-6);
		assertEquals("at the edge", 0f, LightScanner.fadeAt(25f), 1e-6);
		assertEquals("past the edge", 0f, LightScanner.fadeAt(40f), 1e-6);
	}

	/** The budget has to match the shader's array, or lights past it are silently dropped. */
	@Test
	public void lightBudgetIsWhatTheShaderDeclares()
	{
		assertEquals(32, LightScanner.MAX_LIGHTS);
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
