package com.gpuskyweather;

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

	/**
	 * The real measurement this is calibrated against: a Falador wall torch, 2 burning faces
	 * out of 150. Nearly all of it is textured bracket, whose faces carry light values with
	 * no hue. Any proportional test large enough to sound reasonable fails this.
	 */
	@Test
	public void aRealWallTorchBurns()
	{
		assertTrue(FlameDetector.looksLikeFlame(model(2, 148)));
	}

	@Test
	public void aSingleWarmHighlightDoesNot()
	{
		assertFalse(FlameDetector.looksLikeFlame(model(1, 40)));
	}

	/**
	 * Everything else measured in that same scene: correct fire hue, full saturation, and
	 * dim. Brightness is the whole discriminator, so these have to stay dark or the fix that
	 * lit the torch lights half of Falador with it.
	 */
	@Test
	public void brightlyHuedButDimSceneryStaysDark()
	{
		int[] measured = {
			hsl(5, 7, 9),   // hanging banner
			hsl(5, 7, 16),  // signpost
			hsl(6, 7, 8),   // javelin
			hsl(12, 7, 21), // ground decoration
			hsl(6, 6, 2),   // door
		};

		for (int c : measured)
		{
			assertFalse("sat " + ((c >> 7) & 0x7) + " lum " + (c & 0x7F),
				FlameDetector.isFlameColor(c));
		}
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
