package com.gpuv2.config;

/**
 * Screen-space precipitation drawn over the scene.
 *
 * <p>Each mode also pushes the sky toward an overcast colour and thickens the cloud deck,
 * so the weather and the sky agree rather than raining out of a clear blue sky.
 */
public enum WeatherMode
{
	OFF("Off", false, false, 0f, 0x000000, 0f, 0f, 0f, 0f),
	/**
	 * Resolved to one of the real conditions by the weather cycle. Never reaches the
	 * renderer itself, so its values here are unused placeholders.
	 */
	AUTO("Automatic", false, false, 0f, 0x000000, 0f, 0f, 0f, 0f),
	/**
	 * A plain clear day, identical in every value to {@link #OFF}.
	 *
	 * <p>It exists to be sayable. "Off" reads as switching the feature off rather than as
	 * choosing the weather, which makes it an awkward way to ask for a sunny day - and once
	 * every other condition takes the sun away, having one that plainly leaves it there is
	 * worth naming.
	 */
	SUNNY("Sunny", false, false, 0f, 0x000000, 0f, 0f, 0f, 0f),
	/**
	 * Cloud and nothing else: a sealed deck, no precipitation.
	 *
	 * <p>The only mode that closes the sky over completely. Every other one is capped short
	 * of that so the sun, moon and stars stay visible through the weather - here losing them
	 * behind the cloud is the entire point, by day and by night alike.
	 */
	OVERCAST("Overcast", false, false, 0f, 0x8E969E, 0.95f, 1f, 0f, 0.88f),
	/**
	 * Fog: nothing falls, but the air itself closes in.
	 *
	 * <p>The sky is treated as overcast - pale, sealed and sunless - and the rest is done on
	 * the ground, where {@link #mist()} thickens the ground mist and pulls the distance
	 * haze in close. A little gloom, since fog is dim without being dark.
	 */
	FOG("Fog", false, false, 0f, 0xA4ABAF, 0.90f, 1f, 0.10f, 0.85f),
	/*
	 * Every condition below takes the sun away outright.
	 *
	 * Partial values were tried and none of them held up. The disc is drawn incandescent, so
	 * even a tenth of it is a bright spot in an overcast sky, and any weather that leaves it
	 * showing reads as a sunny day with something falling through it. If there is enough
	 * cloud to rain or snow out of, there is enough to hide the sun behind.
	 */
	RAIN("Rain", true, true, 0f, 0x7C848C, 0.60f, 1f, 0.20f, 0.18f),
	STORM("Storm", true, true, 1f, 0x363C44, 0.90f, 1f, 0.34f, 0.42f),
	/*
	 * Snow skies are pale rather than grey - light bounces off the cloud base and the
	 * falling snow. Pale is not the same as bright, though, and the first attempt at these
	 * was near-white with the sun blazing through, which washed the whole scene out. They
	 * are heavily overcast days that happen to be light in colour: the sun goes, the deck
	 * closes up, and the sky settles a good way below white.
	 */
	SNOW("Snow", false, true, 0f, 0xBFC7D2, 0.86f, 1f, 0.12f, 0.48f),
	BLIZZARD("Blizzard", false, true, 1f, 0xC9D2DC, 0.95f, 1f, 0.20f, 0.72f);

	private final String name;
	private final boolean rainLike;
	private final boolean precipitates;
	private final float heavy;
	private final int overcastColor;
	private final float overcast;
	private final float sunHiding;
	private final float gloom;
	private final float cloudSealing;

	WeatherMode(String name, boolean rainLike, boolean precipitates, float heavy,
		int overcastColor, float overcast, float sunHiding, float gloom, float cloudSealing)
	{
		this.name = name;
		this.rainLike = rainLike;
		this.precipitates = precipitates;
		this.heavy = heavy;
		this.overcastColor = overcastColor;
		this.overcast = overcast;
		this.sunHiding = sunHiding;
		this.gloom = gloom;
		this.cloudSealing = cloudSealing;
	}

	/**
	 * How far the cloud deck is closed up, 0 broken to 1 solid.
	 *
	 * <p>Separate from {@link #overcast()}, which only shifts the coverage threshold of the
	 * noise - thickening it that way leaves gaps wherever the field happens to fall short,
	 * and it cannot exceed the general cap on weather cloud. This lifts the deck toward
	 * solid instead, so a mode that should look properly socked in can be.
	 *
	 * <p>Anything above zero also opts the mode out of that cap, since a mode asking for a
	 * closed deck has already said what it wants the sky to look like.
	 */
	public float cloudSealing()
	{
		return cloudSealing;
	}

	/**
	 * How much the weather darkens the world beneath it, 0 none to 1 pitch dark.
	 *
	 * <p>Hiding the sun in the sky is only half of it. With the ground still lit as though
	 * the sun were out, rain reads as a sunny day with water falling through it - the light
	 * has to go when the sun does.
	 *
	 * <p>Snow and blizzard stay at zero for the same reason they keep their sun: those skies
	 * are bright, and snow throws light back up rather than swallowing it.
	 */
	public float gloom()
	{
		return gloom;
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
	 * How much this weather fills the air with mist, 0 none to 1 thick fog.
	 *
	 * <p>Only fog does. Declared here rather than tested for by name at each place that
	 * cares, so a second misty condition later needs no hunting for those places.
	 */
	public float mist()
	{
		return this == FOG ? 1f : 0f;
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
	 * Whether this is no weather at all - nothing falling, nothing over the sun.
	 *
	 * <p>Both an explicitly sunny day and having weather switched off leave the sky exactly
	 * as configured, and every caller that used to test for OFF means this. Asked as a
	 * question rather than compared against a list, so adding another clear condition later
	 * cannot leave one of those callers behind.
	 */
	public boolean isClear()
	{
		return this == OFF || this == SUNNY;
	}

	/**
	 * Whether the deck is closed enough to take the sun and moon with it.
	 */
	public boolean sealsSky()
	{
		return cloudSealing >= 0.8f;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
