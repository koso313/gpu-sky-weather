package com.gpuv2.config;

/**
 * One-click starting points that write the individual settings underneath.
 *
 * <p>Selecting one overwrites those settings; the dropdown does not track later manual
 * edits, so it reads as "what was last applied" rather than "what is currently set".
 */
public enum GraphicsPreset
{
	/**
	 * Applies nothing - the settings below are whatever you last chose.
	 */
	CUSTOM("Custom"),
	/**
	 * Stock renderer look: no sky, no grading, no lighting.
	 */
	VANILLA("Vanilla"),
	/**
	 * Time-of-day sky with gentle grading and lighting.
	 */
	NATURAL("Natural"),
	/**
	 * Heavier grade, deeper fog, stronger light.
	 */
	CINEMATIC("Cinematic"),
	/**
	 * Old-school: flat colours, reduced palette, vanilla vertex snapping.
	 */
	RETRO("Retro"),
	/**
	 * Cheapest settings that still render correctly.
	 */
	PERFORMANCE("Performance");

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
