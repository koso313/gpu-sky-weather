/*
 * Highlight roll-off, shared by the scene and the sky.
 *
 * Lighting, sun glare and specular terms all add on top of the base colour, so bright
 * areas routinely come out above 1.0 - and an 8-bit framebuffer simply clips them. Clipping
 * is what turns a bright sky into a flat white shape with no detail in it, and a sun into a
 * disc with a hard edge rather than something that falls off.
 *
 * This is the ACES filmic curve as approximated by Krzysztof Narkowicz: five multiplies
 * that compress the top end into range instead of cutting it off, so a value of 2.0 lands
 * near white while still being distinguishable from 1.2.
 *
 * Applied as a blend rather than outright, because it does change the look - it slightly
 * darkens the midtones on the way to saving the highlights, and that is a matter of taste
 * rather than correctness.
 */
vec3 acesFilmic(vec3 c)
{
	const float a = 2.51;
	const float b = 0.03;
	const float d = 2.43;
	const float e = 0.59;
	const float f = 0.14;

	return clamp((c * (a * c + b)) / (c * (d * c + e) + f), 0.0, 1.0);
}

vec3 applyToneMap(vec3 c, float amount)
{
	if (amount < 0.001)
	{
		return c;
	}

	return mix(c, acesFilmic(c), amount);
}
