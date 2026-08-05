package com.gpuv2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FlameDetectorTest
{
	/** Packs a colour the way the client does: 6 bits hue, 3 saturation, 7 luminance. */
	private static int hsl(int hue, int sat, int lum)
	{
		return ((hue & 0x3F) << 10) | ((sat & 0x7) << 7) | (lum & 0x7F);
	}

	private static final int FLAME = hsl(6, 7, 110);
	private static final int WOOD = hsl(6, 3, 40);

	private static int[] model(int flameFaces, int otherFaces)
	{
		int[] faces = new int[flameFaces + otherFaces];
		for (int i = 0; i < faces.length; ++i)
		{
			faces[i] = i < flameFaces ? FLAME : WOOD;
		}
		return faces;
	}

	@Test
	public void brightSaturatedOrangeIsFire()
	{
		assertTrue(FlameDetector.isFlameColor(hsl(0, 7, 120)));   // red
		assertTrue(FlameDetector.isFlameColor(hsl(6, 6, 90)));    // orange
		assertTrue(FlameDetector.isFlameColor(hsl(11, 7, 100)));  // yellow
	}

	/**
	 * The two floors are what separate flame from the bracket it is mounted on: brown is
	 * only dark unsaturated orange, and it sits in the same hue arc.
	 */
	@Test
	public void mutedOrangeIsTimberNotFire()
	{
		assertFalse("dark", FlameDetector.isFlameColor(hsl(6, 7, 40)));
		assertFalse("washed out", FlameDetector.isFlameColor(hsl(6, 2, 110)));
		assertFalse("both", FlameDetector.isFlameColor(WOOD));
	}

	@Test
	public void coolColoursAreNeverFire()
	{
		assertFalse("green", FlameDetector.isFlameColor(hsl(20, 7, 110)));
		assertFalse("blue", FlameDetector.isFlameColor(hsl(40, 7, 110)));
		assertFalse("violet", FlameDetector.isFlameColor(hsl(55, 7, 110)));
	}

	/** A torch is mostly bracket, so a minority of burning faces still counts. */
	@Test
	public void aMostlyWoodenTorchStillBurns()
	{
		assertTrue(FlameDetector.looksLikeFlame(model(4, 20)));
	}

	@Test
	public void anObjectWithOneWarmHighlightDoesNot()
	{
		assertFalse("too few faces", FlameDetector.looksLikeFlame(model(2, 4)));
		assertFalse("too small a share", FlameDetector.looksLikeFlame(model(3, 200)));
	}

	@Test
	public void anEntirelyColdModelDoesNot()
	{
		assertFalse(FlameDetector.looksLikeFlame(model(0, 30)));
	}

	@Test
	public void handlesMissingAndEmptyModels()
	{
		assertFalse(FlameDetector.looksLikeFlame(null));
		assertFalse(FlameDetector.looksLikeFlame(new int[0]));
	}

	/**
	 * The client marks a hidden face with -1. It has to fail rather than be read as a hue,
	 * and masking to 0xFFFF lands it at hue 63 - the far side of the wheel from fire.
	 */
	@Test
	public void hiddenFacesAreNotFire()
	{
		assertFalse(FlameDetector.isFlameColor(-1));
	}
}
