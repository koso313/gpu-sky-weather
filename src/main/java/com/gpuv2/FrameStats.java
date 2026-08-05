package com.gpuv2;

import java.util.Arrays;

/**
 * Rolling frame-time statistics for the performance overlay.
 *
 * <p>Keeps the last {@link #WINDOW} frame durations in a ring buffer, overwriting the
 * oldest. Nothing is allocated once it is running - this is stepped on every frame, and an
 * allocation there would show up in the very numbers it is trying to report.
 *
 * <p>Frame times rather than frame rates, throughout. Averaging rates is wrong: two frames
 * at 500fps and 20fps average to 260fps by that arithmetic, when the honest answer is the
 * 38fps you would actually have felt. Times average correctly and convert at the end.
 */
class FrameStats
{
	/**
	 * How many frames the window covers. At a few hundred fps this is a handful of seconds
	 * - long enough for the numbers to sit still, short enough that a stutter you just felt
	 * is still in it.
	 */
	static final int WINDOW = 1000;

	private final long[] frameNanos = new long[WINDOW];
	private final long[] sorted = new long[WINDOW];

	private int count;
	private int next;
	private long lastNanos;

	/**
	 * Records the frame that just ended.
	 *
	 * <p>The first call after a gap only establishes a starting point. Without that, the
	 * pause while a scene loads or the window sits minimised would enter the window as one
	 * enormous frame and sit in the 1% low for the next thousand frames.
	 */
	void frame(long nowNanos)
	{
		if (lastNanos != 0)
		{
			long delta = nowNanos - lastNanos;
			if (delta > 0 && delta < 1_000_000_000L)
			{
				frameNanos[next] = delta;
				next = (next + 1) % WINDOW;
				if (count < WINDOW)
				{
					++count;
				}
			}
			else
			{
				// A gap rather than a frame: drop the window instead of poisoning it.
				reset();
			}
		}

		lastNanos = nowNanos;
	}

	void reset()
	{
		count = 0;
		next = 0;
		lastNanos = 0;
	}

	boolean hasData()
	{
		return count > 0;
	}

	/** Frames per second over the window, from the mean frame time. */
	double averageFps()
	{
		if (count == 0)
		{
			return 0;
		}

		long total = 0;
		for (int i = 0; i < count; ++i)
		{
			total += frameNanos[i];
		}
		return total == 0 ? 0 : 1e9 / ((double) total / count);
	}

	/** Frames per second of the most recent frame. */
	double currentFps()
	{
		if (count == 0)
		{
			return 0;
		}

		long last = frameNanos[(next - 1 + WINDOW) % WINDOW];
		return last <= 0 ? 0 : 1e9 / last;
	}

	/**
	 * The 1% low: the frame rate of the slowest one percent of frames.
	 *
	 * <p>This is what a stutter feels like, and why it is worth showing next to an average
	 * that hides it.
	 *
	 * <p>The mean of the slowest one percent, not the single frame at the 99th percentile.
	 * One sample off a sorted list lands on whichever frame happens to sit at the boundary,
	 * which both jitters between readings and is trivial to put an index either side of; a
	 * mean over the tail says the same thing and holds still.
	 */
	double onePercentLow()
	{
		if (count == 0)
		{
			return 0;
		}

		System.arraycopy(frameNanos, 0, sorted, 0, count);
		Arrays.sort(sorted, 0, count);

		// At least one frame, so a short window still reports its worst.
		int slowest = Math.max(1, count / 100);

		long total = 0;
		for (int i = count - slowest; i < count; ++i)
		{
			total += sorted[i];
		}

		double mean = (double) total / slowest;
		return mean <= 0 ? 0 : 1e9 / mean;
	}

	/** Mean frame time in milliseconds, which is where a stutter actually shows. */
	double averageFrameMs()
	{
		double fps = averageFps();
		return fps <= 0 ? 0 : 1000.0 / fps;
	}
}
