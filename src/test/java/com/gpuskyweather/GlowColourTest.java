package com.gpuskyweather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GlowColourTest
{
	private static int hsl(int hue, int sat, int lum)
	{
		return hue << 10 | sat << 7 | lum;
	}

	private static int[] faces(int colour, int n)
	{
		int[] out = new int[n];
		java.util.Arrays.fill(out, colour);
		return out;
	}

	/** Fire keeps the configured colour, in any shade from red to yellow. */
	@Test
	public void fireHasNoColourOfItsOwn()
	{
		assertEquals(-1, FlameDetector.glowRgb(faces(hsl(6, 7, 90), 8)));
		assertEquals(-1, FlameDetector.glowRgb(faces(hsl(0, 7, 80), 8)));
		assertEquals(-1, FlameDetector.glowRgb(faces(hsl(12, 6, 100), 8)));
		// The top of the wheel is red again.
		assertEquals(-1, FlameDetector.glowRgb(faces(hsl(63, 7, 80), 8)));
	}

	@Test
	public void aBlueFlameIsBlue()
	{
		int rgb = FlameDetector.glowRgb(faces(hsl(42, 7, 90), 6));
		assertTrue("expected a colour", rgb >= 0);
		int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
		assertTrue("blue should lead: " + Integer.toHexString(rgb), b > r && b > g);
	}

	@Test
	public void aGreenLanternIsGreen()
	{
		int rgb = FlameDetector.glowRgb(faces(hsl(21, 6, 80), 6));
		assertTrue("expected a colour", rgb >= 0);
		int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
		assertTrue("green should lead: " + Integer.toHexString(rgb), g > r && g > b);
	}

	/** A torch with a coloured bracket is still a torch. */
	@Test
	public void mostlyFireStaysFire()
	{
		int[] model = new int[10];
		java.util.Arrays.fill(model, 0, 7, hsl(6, 7, 90));
		java.util.Arrays.fill(model, 7, 10, hsl(42, 7, 90));
		assertEquals(-1, FlameDetector.glowRgb(model));
	}

	/** Dull and dark faces are the object's body, not its glow. */
	@Test
	public void dullOrDarkFacesAreIgnored()
	{
		assertEquals(-1, FlameDetector.glowRgb(faces(hsl(42, 2, 90), 8)));
		assertEquals(-1, FlameDetector.glowRgb(faces(hsl(42, 7, 30), 8)));
	}

	/** Textured faces carry a light level, which reads as hue and saturation zero. */
	@Test
	public void texturedFacesAreIgnored()
	{
		assertEquals(-1, FlameDetector.glowRgb(faces(90, 8)));
		assertEquals(-1, FlameDetector.glowRgb(null));
		assertEquals(-1, FlameDetector.glowRgb(new int[0]));
	}
}
