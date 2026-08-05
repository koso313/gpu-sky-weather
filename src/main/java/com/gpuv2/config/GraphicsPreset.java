package com.gpuv2.config;

/**
 * Master switch for everything this plugin adds on top of plain GPU rendering.
 *
 * <p>Deliberately only two entries. The earlier quality presets never worked out: they
 * wrote a shared baseline into config before applying themselves, which silently
 * overwrote settings they had no opinion about - the sky mode among them, which is how
 * picking a preset could turn a time-of-day sky flat grey. Settings a preset destroys are
 * settings you have to rebuild by hand afterwards.
 *
 * <p>So this writes nothing at all. It is read at render time and gates the effects, which
 * makes it a flip switch rather than an edit: go to {@link #DEFAULT} to see the game
 * rendered by the GPU and nothing else, come back to {@link #CUSTOM} and every slider is
 * exactly where you left it.
 */
public enum GraphicsPreset
{
	/**
	 * GPU rendering only - no sky, weather, lighting, fog or post-processing. The user's
	 * settings are left untouched and simply not read.
	 */
	DEFAULT("Default (GPU only)"),

	/**
	 * Everything the user has configured.
	 */
	CUSTOM("Custom");

	private final String name;

	GraphicsPreset(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
