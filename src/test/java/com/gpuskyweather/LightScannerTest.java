package com.gpuskyweather;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
		assertEquals("close up", 1f, LightScanner.fadeAt(0f, 25f), 1e-6);
		assertEquals("inside the band", 1f, LightScanner.fadeAt(19f, 25f), 1e-6);
		assertEquals("band starts", 1f, LightScanner.fadeAt(20f, 25f), 1e-6);
		assertEquals("half way through", 0.5f, LightScanner.fadeAt(22.5f, 25f), 1e-6);
		assertEquals("at the edge", 0f, LightScanner.fadeAt(25f, 25f), 1e-6);
		assertEquals("past the edge", 0f, LightScanner.fadeAt(40f, 25f), 1e-6);
	}

	/**
	 * Somewhere dense enough to spend the light budget within a few tiles, the budget is the
	 * real edge rather than the scan boundary - and a light dropped for being 33rd nearest
	 * has to be dark by then, or it snaps off as you take a step.
	 */
	@Test
	public void theFadeFollowsTheBudgetWhenThatIsWhatBinds()
	{
		float edge = 6f;
		assertEquals("well inside", 1f, LightScanner.fadeAt(0.5f, edge), 1e-6);
		assertEquals("at the budget edge", 0f, LightScanner.fadeAt(6f, edge), 1e-6);
		assertEquals("beyond it", 0f, LightScanner.fadeAt(9f, edge), 1e-6);

		// The same distance is full brightness when the budget is not the constraint.
		assertEquals(1f, LightScanner.fadeAt(6f, 25f), 1e-6);
	}

	/**
	 * The regression this cost: a fixed-width band is wider than the whole kept region once
	 * the budget edge comes in close, so it reaches past the player and dims every light in
	 * the scene rather than just the outermost ones. A torch you are standing next to must
	 * be at full brightness no matter how tight the edge is.
	 */
	@Test
	public void aCloseEdgeDoesNotDimTheLightsBesideYou()
	{
		for (float edge : new float[]{1f, 2f, 3f, 5f, 8f})
		{
			assertEquals("edge " + edge, 1f, LightScanner.fadeAt(0.5f, edge), 1e-6);
			assertEquals("edge " + edge + " at the edge", 0f, LightScanner.fadeAt(edge, edge), 1e-6);
		}
	}

	/**
	 * The regression that cost the torches twice over: the budget edge alone made the
	 * radius setting work backwards. Turning the radius up pulls in more candidates, the
	 * budget binds sooner, the edge collapses inward, and the fade dims the torches that
	 * were working - asking for more light gave less. Flooring the edge at the light radius
	 * means a light inside its own reach is never faded.
	 */
	@Test
	public void aLightInsideItsOwnReachIsNeverFaded()
	{
		int lightRadius = 12;

		// Budget bit hard at 3 tiles, but the floor keeps the edge out at the light radius.
		float edge = Math.min(40f, Math.max(3f, lightRadius));
		assertEquals(12f, edge, 1e-6);

		for (float d : new float[]{0.5f, 1f, 2f, 3f})
		{
			assertEquals("at " + d + " tiles", 1f, LightScanner.fadeAt(d, edge), 1e-6);
		}
	}

	@Test
	public void aZeroEdgeIsNotADivideByZero()
	{
		assertEquals(0f, LightScanner.fadeAt(0f, 0f), 1e-6);
	}

	/**
	 * The ceiling has to match the shader's array exactly. Too high and lights past the end
	 * are silently dropped; too low and uniform slots go to waste. Read out of the shader
	 * rather than repeated here, so the two cannot drift apart.
	 */
	@Test
	public void lightCeilingMatchesTheShaderArray() throws Exception
	{
		String frag;
		try (InputStream in = getClass().getResourceAsStream("/com/gpuskyweather/ext_frag_defs.glsl"))
		{
			assertNotNull("ext_frag_defs.glsl not on the test classpath", in);
			frag = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
				.lines().collect(Collectors.joining("\n"));
		}

		Matcher m = Pattern.compile("#define\\s+GSW_MAX_LIGHTS\\s+(\\d+)").matcher(frag);
		assertTrue("ext_frag_defs.glsl declares no GSW_MAX_LIGHTS", m.find());
		assertEquals(Integer.parseInt(m.group(1)), LightScanner.MAX_LIGHTS);
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
