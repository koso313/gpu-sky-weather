package com.gpuskyweather.config;

/**
 * Where the sky (and the fog that fades into it) takes its colour from.
 */
public enum SkyMode
{
	/**
	 * Whatever the game sets for the current area.
	 */
	GAME("Game default"),
	/**
	 * A single fixed colour chosen in the config.
	 */
	CUSTOM("Custom colour"),
	/**
	 * Interpolated across the day from the player's local system clock.
	 */
	TIME_OF_DAY("Time of day");

	private final String name;

	SkyMode(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
