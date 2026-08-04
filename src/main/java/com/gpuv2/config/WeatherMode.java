package com.gpuv2.config;

/**
 * Screen-space precipitation drawn over the scene.
 *
 * <p>Each mode also pushes the sky toward an overcast colour and thickens the cloud deck,
 * so the weather and the sky agree rather than raining out of a clear blue sky.
 */
public enum WeatherMode
{
	OFF("Off", false, 0f, 0x000000, 0f),
	/**
	 * Resolved to one of the real conditions by the weather cycle. Never reaches the
	 * renderer itself, so its values here are unused placeholders.
	 */
	AUTO("Automatic", false, 0f, 0x000000, 0f),
	RAIN("Rain", true, 0f, 0x7C848C, 0.60f),
	STORM("Storm", true, 1f, 0x4A5057, 0.88f),
	// Snow skies are bright and heavy rather than grey - the light bounces off the cloud
	// base and the falling snow, so they read almost white.
	SNOW("Snow", false, 0f, 0xD6DCE2, 0.75f),
	BLIZZARD("Blizzard", false, 1f, 0xE6EBEF, 0.95f);

	private final String name;
	private final boolean rainLike;
	private final float heavy;
	private final int overcastColor;
	private final float overcast;

	WeatherMode(String name, boolean rainLike, float heavy, int overcastColor, float overcast)
	{
		this.name = name;
		this.rainLike = rainLike;
		this.heavy = heavy;
		this.overcastColor = overcastColor;
		this.overcast = overcast;
	}

	/**
	 * Rain-family modes draw streaks; the others draw flakes.
	 */
	public boolean isRainLike()
	{
		return rainLike;
	}

	/**
	 * 0 for the calm variant, 1 for the severe one (storm, blizzard). Scales fall speed,
	 * density and sideways drift in the shader.
	 */
	public float heavy()
	{
		return heavy;
	}

	/**
	 * Colour the sky is pushed toward, packed 0xRRGGBB.
	 */
	public int overcastColor()
	{
		return overcastColor;
	}

	/**
	 * How far toward {@link #overcastColor()} the sky goes, 0..1.
	 */
	public float overcast()
	{
		return overcast;
	}

	/**
	 * Only storms produce lightning.
	 */
	public boolean hasLightning()
	{
		return this == STORM;
	}

	/**
	 * Whether anything actually falls. OFF has nothing to draw and AUTO is a selection
	 * rather than a condition, so neither should run the particle pass.
	 */
	public boolean hasPrecipitation()
	{
		return this != OFF && this != AUTO;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
