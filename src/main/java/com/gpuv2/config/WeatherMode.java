package com.gpuv2.config;

/**
 * Screen-space precipitation drawn over the scene.
 *
 * <p>Each mode also pushes the sky toward an overcast colour and thickens the cloud deck,
 * so the weather and the sky agree rather than raining out of a clear blue sky.
 */
public enum WeatherMode
{
	OFF("Off", false, false, 0f, 0x000000, 0f, 0f),
	/**
	 * Resolved to one of the real conditions by the weather cycle. Never reaches the
	 * renderer itself, so its values here are unused placeholders.
	 */
	AUTO("Automatic", false, false, 0f, 0x000000, 0f, 0f),
	/**
	 * Cloud and nothing else: a sealed deck, no precipitation.
	 *
	 * <p>The only mode that closes the sky over completely. Every other one is capped short
	 * of that so the sun, moon and stars stay visible through the weather - here losing them
	 * behind the cloud is the entire point, by day and by night alike.
	 */
	OVERCAST("Overcast", false, false, 0f, 0x8E969E, 0.95f, 1f),
	// Rain leaves the sun as a dull smear behind the cloud rather than removing it; a storm
	// takes it away entirely, which is most of what makes a storm feel like one.
	RAIN("Rain", true, true, 0f, 0x7C848C, 0.60f, 0.70f),
	STORM("Storm", true, true, 1f, 0x4A5057, 0.88f, 1f),
	// Snow skies are bright and heavy rather than grey - the light bounces off the cloud
	// base and the falling snow, so they read almost white.
	SNOW("Snow", false, true, 0f, 0xD6DCE2, 0.75f, 0f),
	BLIZZARD("Blizzard", false, true, 1f, 0xE6EBEF, 0.95f, 0f);

	private final String name;
	private final boolean rainLike;
	private final boolean precipitates;
	private final float heavy;
	private final int overcastColor;
	private final float overcast;
	private final float sunHiding;

	WeatherMode(String name, boolean rainLike, boolean precipitates, float heavy,
		int overcastColor, float overcast, float sunHiding)
	{
		this.name = name;
		this.rainLike = rainLike;
		this.precipitates = precipitates;
		this.heavy = heavy;
		this.overcastColor = overcastColor;
		this.overcast = overcast;
		this.sunHiding = sunHiding;
	}

	/**
	 * How far the sun is hidden behind the weather, 0 untouched to 1 gone.
	 *
	 * <p>Kept separate from {@link #overcast()}, which is a sky colour and a cloud
	 * thickness. Those are capped short of covering the sun on purpose, so without this the
	 * sun burned merrily through a downpour - and a storm with a visible sun does not read
	 * as a storm at all.
	 *
	 * <p>Snow and blizzard are left untouched deliberately. Their skies are bright rather
	 * than dark, and the light in them plainly comes from somewhere.
	 */
	public float sunHiding()
	{
		return sunHiding;
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
	 * Whether anything actually falls, and so whether the particle pass runs at all.
	 *
	 * <p>Declared per mode rather than derived by excluding the ones that do not. The list
	 * of exceptions was already OFF and AUTO before overcast joined them, and each addition
	 * is a chance to forget one somewhere.
	 */
	public boolean hasPrecipitation()
	{
		return precipitates;
	}

	/**
	 * Whether the cloud deck closes over completely, taking the sun and moon with it.
	 *
	 * <p>Everything else is deliberately capped short of sealing: a blizzard sky at 0.95
	 * cover would otherwise blot out the sun, moon and stars as a side effect of the snow,
	 * which is not what anyone picking "blizzard" is asking for. Overcast is the one mode
	 * where that is exactly the request.
	 */
	public boolean sealsSky()
	{
		return this == OVERCAST;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
