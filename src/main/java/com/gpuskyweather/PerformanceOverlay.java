package com.gpuskyweather;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Frame rate and GPU readout, drawn in the corner of the viewport.
 *
 * <p>Deliberately plain text rather than a bordered panel, to match the client's own FPS
 * counter - this sits in the same corner and is read the same way, so looking like a
 * different kind of thing would only make both harder to read.
 */
@Singleton
class PerformanceOverlay extends Overlay
{
	// Matches the client's own counter rather than inventing a palette next to it.
	private static final Color GOOD = Color.GREEN;
	private static final Color WARN = Color.YELLOW;
	private static final Color BAD = Color.RED;
	private static final Color SHADOW = Color.BLACK;

	/**
	 * How often the readout is allowed to change.
	 *
	 * <p>At three hundred frames a second the numbers turn over faster than they can be
	 * read. Four times a second is quick enough to catch a dip you just felt and slow enough
	 * that the digits hold still long enough to look at.
	 */
	private static final long REFRESH_NANOS = 250_000_000L;

	/** Window the displayed rate is averaged over, matching the refresh interval. */
	private static final long SAMPLE_NANOS = 250_000_000L;

	/** Frame rates below this read as a problem worth colouring. */
	private static final int WARN_FPS = 60;
	private static final int BAD_FPS = 30;

	private final GpuSkyWeatherConfig config;
	private final FrameStats stats;
	private final GpuMonitor gpu;

	private final List<String> lines = new ArrayList<>();
	private final List<Color> colours = new ArrayList<>();

	private long lastRefreshNanos;

	@Inject
	PerformanceOverlay(GpuSkyWeatherConfig config, FrameStats stats, GpuMonitor gpu)
	{
		this.config = config;
		this.stats = stats;
		this.gpu = gpu;

		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.perfOverlay() || !stats.hasData())
		{
			return null;
		}

		/*
		 * Rebuilt on a timer, not per frame. Everything here is held between refreshes so
		 * the whole readout changes together and holds still in between.
		 */
		long now = System.nanoTime();
		if (lines.isEmpty() || now - lastRefreshNanos >= REFRESH_NANOS)
		{
			lastRefreshNanos = now;
			rebuild();
		}

		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics metrics = g.getFontMetrics();

		int width = 0;
		for (String line : lines)
		{
			width = Math.max(width, metrics.stringWidth(line));
		}

		int lineHeight = metrics.getHeight();
		int y = metrics.getAscent();

		for (int i = 0; i < lines.size(); ++i)
		{
			String line = lines.get(i);

			// Right-aligned, so the numbers stay put as their width changes rather than
			// jittering the whole block every time the frame rate crosses a digit.
			int x = width - metrics.stringWidth(line);

			g.setColor(SHADOW);
			g.drawString(line, x + 1, y + 1);
			g.setColor(colours.get(i));
			g.drawString(line, x, y);

			y += lineHeight;
		}

		return new Dimension(width, lineHeight * lines.size());
	}

	private void rebuild()
	{
		lines.clear();
		colours.clear();

		double current = stats.recentFps(SAMPLE_NANOS);
		add(Math.round(current) + " FPS", fpsColour(current));

		if (config.perfShowFrameTime())
		{
			add(String.format("%.1f ms", stats.averageFrameMs()), GOOD);
		}

		if (config.perfShowAverage())
		{
			double avg = stats.averageFps();
			add("avg " + Math.round(avg), fpsColour(avg));
		}

		if (config.perfShowLows())
		{
			double low = stats.onePercentLow();
			add("1% " + Math.round(low), fpsColour(low));
		}

		// Hidden rather than shown empty when there is no NVIDIA card to ask.
		if (config.perfShowGpu() && gpu.isAvailable())
		{
			add("GPU " + gpu.temperature() + "°C " + gpu.utilisation() + "%", GOOD);
		}
	}

	private void add(String text, Color colour)
	{
		lines.add(text);
		colours.add(colour);
	}

	private static Color fpsColour(double fps)
	{
		if (fps < BAD_FPS)
		{
			return BAD;
		}
		return fps < WARN_FPS ? WARN : GOOD;
	}
}
