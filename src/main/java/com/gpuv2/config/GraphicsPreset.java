package com.gpuv2.config;

/**
 * Quality presets.
 *
 * <p>Everything except {@link #CUSTOM} is a placeholder for now - selecting one changes
 * nothing until its settings are filled in. That is deliberate: an earlier version wrote
 * a shared baseline before applying each preset, which silently discarded settings the
 * preset was not really trying to control (the sky mode in particular). Presets should
 * only write what they actually mean to set.
 */
public enum GraphicsPreset
{
	/**
	 * Whatever the user has configured. Never writes anything.
	 */
	CUSTOM("Custom"),
	PERFORMANCE("Performance (not yet configured)"),
	MID("Mid (not yet configured)"),
	LOW("Low (not yet configured)");

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
