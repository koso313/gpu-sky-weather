package com.gpuskyweather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FrameStatsTest
{
	private static final long MS = 1_000_000L;

	/** Feeds n frames of the given length, starting from an established clock. */
	private static FrameStats atSteady(double frameMs, int frames)
	{
		FrameStats s = new FrameStats();
		long t = 0;
		s.frame(t);
		for (int i = 0; i < frames; ++i)
		{
			t += (long) (frameMs * MS);
			s.frame(t);
		}
		return s;
	}

	@Test
	public void steadyFramesGiveTheirOwnRate()
	{
		FrameStats s = atSteady(10, 100);
		assertEquals(100.0, s.averageFps(), 0.01);
		assertEquals(100.0, s.recentFps(250 * MS), 0.01);
		assertEquals(10.0, s.averageFrameMs(), 0.01);
	}

	/**
	 * The reason this averages frame times rather than frame rates. Half the frames at
	 * 500fps and half at 20fps average to 260fps if you average the rates - a number nobody
	 * experienced. The honest answer is what the total time actually works out to.
	 */
	@Test
	public void averagesTimeNotRate()
	{
		FrameStats s = new FrameStats();
		long t = 0;
		s.frame(t);
		for (int i = 0; i < 50; ++i)
		{
			t += 2 * MS;   // 500fps
			s.frame(t);
			t += 50 * MS;  // 20fps
			s.frame(t);
		}

		// 100 frames in 2.6s is a shade over 38fps, nowhere near 260.
		assertEquals(38.46, s.averageFps(), 0.5);
	}

	/**
	 * The whole point of showing a 1% low beside an average: a run of smooth frames with
	 * occasional stutters has a healthy average and a low that tells the truth.
	 */
	@Test
	public void onePercentLowFindsTheStutter()
	{
		FrameStats s = new FrameStats();
		long t = 0;
		s.frame(t);
		for (int i = 0; i < 1000; ++i)
		{
			// One frame in a hundred takes 50ms; the rest take 4ms.
			t += (i % 100 == 0 ? 50 : 4) * MS;
			s.frame(t);
		}

		assertTrue("average should still look healthy, was " + s.averageFps(),
			s.averageFps() > 100);
		assertEquals("1% low should report the stutter", 20.0, s.onePercentLow(), 1.0);
	}

	/**
	 * A scene load or a minimised window produces one enormous gap. Counting it as a frame
	 * would park it in the 1% low for the next thousand frames, long after the pause ended.
	 */
	@Test
	public void aLongPauseIsDiscardedRatherThanCounted()
	{
		FrameStats s = atSteady(5, 200);
		double before = s.onePercentLow();

		// Five seconds of nothing, then normal frames resume.
		s.frame(10_000L * MS);
		for (int i = 1; i <= 200; ++i)
		{
			s.frame((10_000L + i * 5L) * MS);
		}

		assertEquals("the gap should not have entered the window",
			before, s.onePercentLow(), 1.0);
	}

	/**
	 * The displayed rate spans a fixed slice of time rather than a fixed number of frames.
	 * A frame count would cover a fifth of a second at 300fps and three seconds at 20 - the
	 * wrong way round, since the slower it runs the more responsive it needs to be.
	 */
	@Test
	public void recentRateSpansTimeNotFrameCount()
	{
		// 5ms frames: a 250ms window is 50 of them, and the rate is 200 either way.
		assertEquals(200.0, atSteady(5, 500).recentFps(250 * MS), 1.0);

		// 100ms frames: only two or three fit the window, and it still reports 10.
		assertEquals(10.0, atSteady(100, 50).recentFps(250 * MS), 1.0);
	}

	/** A recent change has to show up rather than being buried under the older window. */
	@Test
	public void recentRateFollowsAChangeTheAverageHasNotCaughtUpWith()
	{
		FrameStats s = atSteady(4, 900);

		// The frame rate collapses for the last stretch.
		long now = 900 * 4L * MS;
		for (int i = 0; i < 10; ++i)
		{
			now += 40 * MS;
			s.frame(now);
		}

		assertEquals("recent should show the collapse", 25.0, s.recentFps(250 * MS), 2.0);
		assertTrue("the long average should still be dominated by the fast frames",
			s.averageFps() > 100);
	}

	@Test
	public void reportsNothingUntilItHasAFrame()
	{
		FrameStats s = new FrameStats();
		assertFalse(s.hasData());
		assertEquals(0.0, s.averageFps(), 1e-9);

		// A single call only establishes the clock - there is no interval yet.
		s.frame(1234);
		assertFalse(s.hasData());
	}

	/** The window is finite, so old frames have to leave it rather than accumulate. */
	@Test
	public void oldFramesFallOutOfTheWindow()
	{
		FrameStats s = atSteady(50, FrameStats.WINDOW);
		assertEquals(20.0, s.averageFps(), 0.01);

		// Refill entirely with fast frames; nothing of the slow run should remain.
		FrameStats t = s;
		long now = FrameStats.WINDOW * 50L * MS;
		for (int i = 0; i < FrameStats.WINDOW; ++i)
		{
			now += 5 * MS;
			t.frame(now);
		}

		assertEquals(200.0, t.averageFps(), 0.01);
		assertEquals(200.0, t.onePercentLow(), 1.0);
	}
}
