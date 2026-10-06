package com.gpuskyweather;

import static org.lwjgl.opengl.GL33C.*;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.gpu.api.PBOListener;

/**
 * Measures how bright the picture on screen is, and from that works out an exposure.
 *
 * <p>The measurement comes from the GPU plugin's frame readback: each frame it hands over
 * a pixel buffer holding an earlier finished frame, already copied off the card, so reading
 * it costs no stall. A sparse grid of pixels from the middle of the 3D view is enough - the
 * answer wanted is one number, and it is averaged over time anyway.
 *
 * <p>What comes out is the eye adjusting: step from a dark cave into daylight and the
 * picture is too bright for a moment, then settles; go the other way and the dark slowly
 * becomes readable. It is deliberately narrow. Night is meant to be dark and a snowfield is
 * meant to be bright, and an exposure free to cancel those out would flatten every scene
 * to the same grey.
 */
@Slf4j
final class ExposureMeter implements PBOListener
{
	// At most this often. The reading is smoothed over seconds, so sampling every frame
	// would map the buffer hundreds of times a second to learn nothing new.
	private static final long SAMPLE_INTERVAL_NANOS = 100_000_000L;

	// Sample grid, taken from the middle of the 3D view so the interface around the edges
	// and the minimap in the corner stay out of the reading.
	private static final int GRID_X = 32;
	private static final int GRID_Y = 18;
	private static final float REGION = 0.6f;

	// Brightness the picture is steered toward by day, and how far the exposure may move to
	// get there. Night aims lower, so it stays night.
	private static final float KEY_DAY = 0.42f;
	private static final float KEY_NIGHT = 0.26f;
	private static final float MIN_EXPOSURE = 0.80f;
	private static final float MAX_EXPOSURE = 1.35f;

	// Seconds for the exposure to cover most of a change. Slower going dark than going
	// bright, as eyes are.
	private static final float ADAPT_TO_BRIGHT_SECONDS = 0.8f;
	private static final float ADAPT_TO_DARK_SECONDS = 2.2f;

	private final Client client;

	private ByteBuffer mapped;
	private long lastSampleNanos;
	private long lastUpdateNanos;

	// Brightness last read off the screen, 0..1, and how many readings there have been.
	private float measured = -1f;
	private long frames;
	private long samples;

	// The exposure in force, before the strength setting is applied.
	private float exposure = 1f;

	ExposureMeter(Client client)
	{
		this.client = client;
	}

	/**
	 * Called by the GPU plugin on the client thread with a finished frame. The buffer is
	 * only valid inside this call.
	 */
	@Override
	public void onFrame(int pbo, int width, int height)
	{
		++frames;

		long now = System.nanoTime();
		if (now - lastSampleNanos < SAMPLE_INTERVAL_NANOS || width <= 0 || height <= 0)
		{
			return;
		}
		lastSampleNanos = now;

		// The frame is the whole window. Find the 3D view inside it, which is given in
		// canvas pixels from the top left, while the frame is in window pixels from the
		// bottom up and may be scaled by stretched mode or the display's DPI.
		int canvasW = client.getCanvasWidth();
		int canvasH = client.getCanvasHeight();
		int viewW = client.getViewportWidth();
		int viewH = client.getViewportHeight();
		if (canvasW <= 0 || canvasH <= 0 || viewW <= 0 || viewH <= 0)
		{
			return;
		}

		float sx = (float) width / canvasW;
		float sy = (float) height / canvasH;
		float left = (client.getViewportXOffset() + viewW * (1f - REGION) * 0.5f) * sx;
		float top = (client.getViewportYOffset() + viewH * (1f - REGION) * 0.5f) * sy;
		float spanX = viewW * REGION * sx;
		float spanY = viewH * REGION * sy;

		long length = (long) width * height * Integer.BYTES;

		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
		try
		{
			ByteBuffer pixels = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0L, length, GL_MAP_READ_BIT, mapped);
			if (pixels == null)
			{
				return;
			}
			mapped = pixels;

			float sum = 0f;
			int n = 0;
			for (int gy = 0; gy < GRID_Y; ++gy)
			{
				int yTop = (int) (top + spanY * (gy + 0.5f) / GRID_Y);
				int row = height - 1 - yTop;
				if (row < 0 || row >= height)
				{
					continue;
				}

				for (int gx = 0; gx < GRID_X; ++gx)
				{
					int x = (int) (left + spanX * (gx + 0.5f) / GRID_X);
					if (x < 0 || x >= width)
					{
						continue;
					}

					// GL_BGRA as packed 8888 words: blue, green, red, alpha in memory.
					int at = (row * width + x) * Integer.BYTES;
					int b = pixels.get(at) & 0xFF;
					int g = pixels.get(at + 1) & 0xFF;
					int r = pixels.get(at + 2) & 0xFF;
					sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
					++n;
				}
			}

			glUnmapBuffer(GL_PIXEL_PACK_BUFFER);

			if (n > 0)
			{
				measured = sum / n;
				if (samples++ == 0)
				{
					log.info("GPU Sky/Weather exposure: first frame from the PBO listener, {}x{}, brightness {}",
						width, height, String.format("%.3f", measured));
				}
				else if (samples % 100 == 0)
				{
					log.debug("GPU Sky/Weather exposure: {} frames delivered, {} sampled, brightness {}, exposure {}",
						frames, samples, String.format("%.3f", measured), String.format("%.3f", exposure));
				}
			}
		}
		finally
		{
			glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		}
	}

	/**
	 * Advances the exposure toward what the last reading asks for, and returns the
	 * multiplier to draw with.
	 *
	 * @param strength 0 leaves the picture alone, 1 applies the full adjustment
	 * @param night    0 by day to 1 at night, which lowers what the picture is steered to
	 * @param applied  the multiplier that was in force for the frames being read, since
	 *                 the reading is of a picture that already had it applied
	 */
	float update(float strength, float night, float applied)
	{
		long now = System.nanoTime();
		float dt = lastUpdateNanos == 0 ? 0f : Math.min(0.25f, (now - lastUpdateNanos) / 1e9f);
		lastUpdateNanos = now;

		if (strength <= 0f || measured < 0f)
		{
			exposure = 1f;
			return 1f;
		}

		// Take the adjustment back out to get at the brightness of the scene itself, then
		// ask what exposure would put that at the key.
		float scene = Math.max(0.02f, measured / Math.max(0.1f, applied));
		float key = KEY_DAY + (KEY_NIGHT - KEY_DAY) * Math.max(0f, Math.min(1f, night));
		float wanted = Math.max(MIN_EXPOSURE, Math.min(MAX_EXPOSURE, key / scene));

		float seconds = wanted < exposure ? ADAPT_TO_BRIGHT_SECONDS : ADAPT_TO_DARK_SECONDS;
		exposure += (wanted - exposure) * Math.min(1f, dt / seconds);

		return 1f + (exposure - 1f) * Math.max(0f, Math.min(1f, strength));
	}

	void reset()
	{
		measured = -1f;
		exposure = 1f;
		lastUpdateNanos = 0;
		samples = 0;
		frames = 0;
	}

	long framesDelivered()
	{
		return frames;
	}
}
