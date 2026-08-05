package com.gpuv2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WaterDetectorTest
{
	/** Packs a colour the way the client does: 6 bits hue, 3 saturation, 7 luminance. */
	private static int hsl(int hue, int sat, int lum)
	{
		return ((hue & 0x3F) << 10) | ((sat & 0x7) << 7) | (lum & 0x7F);
	}

	private static final int SEA = hsl(40, 4, 50);
	private static final int GRASS = hsl(12, 4, 50);

	@Test
	public void flatBlueTileIsWater()
	{
		assertTrue(WaterDetector.isWaterTile(SEA, SEA, SEA, SEA, 0, 0, 0, 0));
	}

	/**
	 * The flatness test is what stops the detector from claiming every blue-ish surface in
	 * the game. Water is never sloped, so a slope is disqualifying on its own.
	 */
	@Test
	public void slopedBlueTileIsNotWater()
	{
		assertFalse(WaterDetector.isWaterTile(SEA, SEA, SEA, SEA, 0, 0, 40, 0));
		assertFalse(WaterDetector.isWaterTile(SEA, SEA, SEA, SEA, 0, -8, 0, 0));
	}

	@Test
	public void flatGroundOfOtherColoursIsNotWater()
	{
		int[] notWater = {
			GRASS,
			hsl(6, 3, 60),    // sand
			hsl(0, 4, 45),    // red brick
			hsl(20, 5, 55),   // foliage
			hsl(55, 4, 40),   // violet-blue floor, just past the band
			hsl(30, 4, 45),   // teal-green, just before it
		};

		for (int c : notWater)
		{
			assertFalse("hue " + ((c >> 10) & 0x3F) + " should not be water",
				WaterDetector.isWaterTile(c, c, c, c, 0, 0, 0, 0));
		}
	}

	/** Grey stone with a blue cast is in the hue band but has no saturation to speak of. */
	@Test
	public void desaturatedBlueIsNotWater()
	{
		int stone = hsl(40, 1, 50);
		assertFalse(WaterDetector.isWaterTile(stone, stone, stone, stone, 0, 0, 0, 0));
	}

	@Test
	public void blackAndBlownOutTilesAreNotWater()
	{
		int black = hsl(40, 4, 2);
		int white = hsl(40, 4, 125);
		assertFalse(WaterDetector.isWaterTile(black, black, black, black, 0, 0, 0, 0));
		assertFalse(WaterDetector.isWaterTile(white, white, white, white, 0, 0, 0, 0));
	}

	/**
	 * Shorelines blend land colour into the corner of the water tile beside them. Accepting
	 * a majority of corners would let the ripple creep up the beach a tile at a time.
	 */
	@Test
	public void oneLandCornerDisqualifiesTheTile()
	{
		assertFalse(WaterDetector.isWaterTile(SEA, SEA, SEA, GRASS, 0, 0, 0, 0));
	}

	@Test
	public void hiddenCornersAreNotWater()
	{
		int h = WaterDetector.HIDDEN;
		assertFalse(WaterDetector.isWaterTile(SEA, SEA, SEA, h, 0, 0, 0, 0));
		assertFalse(WaterDetector.isWaterColor(h));
	}

	@Test
	public void shapedTileFacesFollowTheSameRules()
	{
		assertTrue(WaterDetector.isWaterFace(SEA, SEA, SEA, 0, 0, 0));
		assertFalse("sloped", WaterDetector.isWaterFace(SEA, SEA, SEA, 0, 0, 16));
		assertFalse("land corner", WaterDetector.isWaterFace(SEA, GRASS, SEA, 0, 0, 0));
	}

	/**
	 * The band edges are the whole calibration, so pin them: 33 and 46 are water, the hues
	 * either side are not.
	 */
	@Test
	public void hueBandEdgesAreWhereTheyAreDocumented()
	{
		assertFalse(WaterDetector.isWaterColor(hsl(32, 4, 50)));
		assertTrue(WaterDetector.isWaterColor(hsl(33, 4, 50)));
		assertTrue(WaterDetector.isWaterColor(hsl(46, 4, 50)));
		assertFalse(WaterDetector.isWaterColor(hsl(47, 4, 50)));
	}
}
