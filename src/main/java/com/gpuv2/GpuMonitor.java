package com.gpuv2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Samples GPU temperature and utilisation from nvidia-smi.
 *
 * <p>Java has no way to read either, so this shells out. That has consequences worth being
 * blunt about: it is NVIDIA-only, it costs a process launch per sample, and it is therefore
 * off by default and sampled slowly.
 *
 * <p>Never on the render thread. Launching a process takes tens of milliseconds, which as a
 * render-thread stall would be a far bigger performance problem than anything the overlay
 * could report - and would corrupt the frame times being measured beside it.
 */
@Slf4j
class GpuMonitor
{
	/**
	 * Seconds between samples. Temperature moves slowly and a process launch is not cheap,
	 * so there is nothing to gain from going faster.
	 */
	private static final int PERIOD_SECONDS = 2;

	private static final int TIMEOUT_SECONDS = 5;

	private ScheduledExecutorService executor;

	/** Last reading, or -1 for none. Written by the sampler, read by the overlay. */
	private volatile int temperature = -1;
	private volatile int utilisation = -1;

	/** False once a sample has failed, so a machine without nvidia-smi stops being asked. */
	private volatile boolean available = true;

	void start()
	{
		if (executor != null)
		{
			return;
		}

		executor = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "gpu-v2-monitor");
			t.setDaemon(true);
			return t;
		});
		executor.scheduleWithFixedDelay(this::sample, 0, PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	void stop()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}

		temperature = -1;
		utilisation = -1;
		available = true;
	}

	boolean isAvailable()
	{
		return available && temperature >= 0;
	}

	int temperature()
	{
		return temperature;
	}

	int utilisation()
	{
		return utilisation;
	}

	private void sample()
	{
		if (!available)
		{
			return;
		}

		Process process = null;
		try
		{
			process = new ProcessBuilder(
				"nvidia-smi",
				"--query-gpu=temperature.gpu,utilization.gpu",
				"--format=csv,noheader,nounits")
				.redirectErrorStream(true)
				.start();

			String line;
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
			{
				line = reader.readLine();
			}

			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
			{
				process.destroyForcibly();
				markUnavailable("nvidia-smi timed out");
				return;
			}

			if (line == null)
			{
				markUnavailable("nvidia-smi returned nothing");
				return;
			}

			String[] parts = line.split(",");
			if (parts.length < 2)
			{
				markUnavailable("unexpected nvidia-smi output: " + line);
				return;
			}

			temperature = Integer.parseInt(parts[0].trim());
			utilisation = Integer.parseInt(parts[1].trim());
		}
		catch (NumberFormatException ex)
		{
			markUnavailable("could not parse nvidia-smi output");
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception ex)
		{
			// The expected path on any machine without an NVIDIA card, so this is not a
			// warning - it just means there is nothing here to read.
			markUnavailable("nvidia-smi unavailable: " + ex.getMessage());
		}
		finally
		{
			if (process != null && process.isAlive())
			{
				process.destroyForcibly();
			}
		}
	}

	/**
	 * Stops sampling for good. One failure is enough - whatever the reason, retrying every
	 * two seconds forever would spawn a process each time to fail the same way.
	 */
	private void markUnavailable(String reason)
	{
		log.debug("GPU monitoring off: {}", reason);
		available = false;
		temperature = -1;
		utilisation = -1;
	}
}
