#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform vec3 skyColor;    // at the horizon
uniform vec3 zenithColor; // directly overhead
uniform float night;      // 0 = full daylight, 1 = full dark
uniform float starDensity;
uniform float halfW;      // viewportWidth  / (2 * clientScale)
uniform float halfH;      // viewportHeight / (2 * clientScale)
uniform float cosPitch;
uniform float sinPitch;
uniform float cosYaw;
uniform float sinYaw;
uniform float starTime;   // seconds, drives star twinkle
uniform float cloudTime;  // continuous seconds-of-day, drives cloud drift
uniform vec3 sunDir;      // normalised world direction toward the sun
uniform vec3 moonDir;     // normalised world direction toward the moon
uniform float showMoon;
uniform float showSun;
uniform float sunGlow;    // brightness of the sun disc
uniform float sunGlare;   // strength of the halo and streaks around it
uniform float moonGlow;   // brightness of the moon disc and its halo
uniform float moonPhase;  // 0 and 1 new, 0.5 full
uniform float cloudAmount;   // 0 = clear, 1 = overcast
uniform float cloudOpacity;

/*
 * Shooting stars. Whether one is flying, and where, is decided on the CPU rather than
 * hashed here - that way the plugin knows the moment one spawns and can play a sound for
 * it, which it cannot do if the decision only ever exists inside the shader.
 */
// Sample counts, driven by the effect quality setting.
uniform int meteorSamples;
uniform int cloudOctaves;

uniform float meteorActive;
uniform float meteorTravel;   // 0..1 along the flight; above 1 means finished
uniform vec4 meteorPath;      // start bearing, start height, bearing arc, height drop

// Aurora. 0 disables.
uniform float auroraStrength;
uniform float auroraTime;   // monotonic seconds, already scaled by the speed setting

// Lightning bolt drawn in the sky. boltStrength 0 disables it.
uniform float boltStrength;
uniform float boltSeed;
uniform vec2 boltDirXZ;      // normalised horizontal direction of the strike

/*
 * World-space view direction for this pixel.
 *
 * The scene is drawn with clip = Scale * P * Rx(pitch) * Ry(yaw) * T * world, so a
 * camera-space ray maps back to world space by undoing the two rotations in reverse:
 * dir_world = Ry^T * Rx^T * dir_cam.
 *
 * P is projection(w, h, n) = { 2/w, -2/h, ... }, which after the perspective divide
 * gives ndc.x = scale * (2/w) * view.x / view.z, so view.x/view.z = ndc.x * w/(2*scale)
 * - that is what halfW and halfH carry in.
 */
vec3 viewDirection()
{
	vec3 d = vec3(fNdc.x * halfW, -fNdc.y * halfH, 1.0);

	// Rx^T - undo pitch
	vec3 a = vec3(
		d.x,
		cosPitch * d.y + sinPitch * d.z,
		-sinPitch * d.y + cosPitch * d.z
	);

	// Ry^T - undo yaw
	vec3 w = vec3(
		cosYaw * a.x - sinYaw * a.z,
		a.y,
		sinYaw * a.x + cosYaw * a.z
	);

	return normalize(w);
}

float hash13(vec3 p3)
{
	p3 = fract(p3 * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

float hash12(vec2 p)
{
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p)
{
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);

	float a = hash12(i);
	float b = hash12(i + vec2(1.0, 0.0));
	float c = hash12(i + vec2(0.0, 1.0));
	float d = hash12(i + vec2(1.0, 1.0));

	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p)
{
	float total = 0.0;
	float amplitude = 0.5;
	for (int i = 0; i < 5; ++i)
	{
		total += valueNoise(p) * amplitude;
		p *= 2.02;
		amplitude *= 0.5;
	}
	return total;
}

/*
 * Cloud field that reshapes as well as travels.
 *
 * Scrolling a static fbm slides one fixed pattern past forever - the same clouds, in the
 * same shapes, every time. Two things break that up:
 *
 *  - a slow domain warp, which bends the whole field and changes the large structures
 *  - a different drift rate and direction per octave, so detail moves relative to the
 *    shapes containing it rather than the deck translating rigidly
 *
 * Rates are kept low enough that offsets stay small over a day; large offsets would push
 * the hash into the range where its precision falls apart and the noise turns to banding.
 */
float cloudFbm(vec2 p, float t)
{
	vec2 warp = vec2(
		valueNoise(p * 0.35 + vec2(t * 0.0006, 0.0)),
		valueNoise(p * 0.35 + vec2(17.3, -t * 0.0004))
	) - 0.5;
	p += warp * 1.6;

	float total = 0.0;
	float amplitude = 0.5;
	// Octave count follows the quality setting; fewer means softer, cheaper clouds.
	int octaves = max(cloudOctaves, 2);
	for (int i = 0; i < octaves; ++i)
	{
		float fi = float(i);
		vec2 drift = vec2(t * (0.0007 + fi * 0.0004), t * (-0.0005 + fi * 0.0003));
		total += valueNoise(p + drift) * amplitude;
		p *= 2.02;
		amplitude *= 0.5;
	}
	return total;
}

/*
 * Starfield keyed on world direction, so stars stay pinned to the world as the camera
 * rotates rather than sliding across the screen.
 */
float starField(vec3 dir)
{
	vec3 g = dir * 140.0;
	vec3 cell = floor(g);
	float h = hash13(cell);

	float threshold = 1.0 - starDensity;
	if (h < threshold)
	{
		return 0.0;
	}

	// Jitter the star inside its cell so the grid doesn't read as a lattice.
	vec3 offset = vec3(
		hash13(cell + 17.0),
		hash13(cell + 43.0),
		hash13(cell + 79.0)
	) - 0.5;

	float d = length(fract(g) - 0.5 - offset * 0.6);
	float brightness = (h - threshold) / max(starDensity, 1e-5);

	// Ascending edges only - smoothstep is undefined when edge0 >= edge1, so invert
	// rather than passing them backwards to get a falloff.
	float star = (1.0 - smoothstep(0.0, 0.30, d)) * brightness;
	star *= 0.65 + 0.35 * sin(starTime * 1.7 + h * 120.0);
	return star;
}

/*
 * A celestial disc plus its glow. Angular sizes are exaggerated well past life-size -
 * a true half-degree disc is only a few pixels and reads as a dead pixel in game.
 */
vec2 celestial(vec3 dir, vec3 bodyDir, float discCos, float glowCos)
{
	float md = dot(dir, bodyDir);
	float disc = smoothstep(discCos, discCos + (1.0 - discCos) * 0.35, md);
	float glow = smoothstep(glowCos, 1.0, md);
	return vec2(disc, glow);
}

float hash11(float x)
{
	return fract(sin(x * 127.1 + 311.7) * 43758.5453);
}

/*
 * Impact craters at one scale: a bright raised rim around a darker floor.
 *
 * Returns a signed brightness offset, so craters can be layered by simply summing them.
 */
float craterField(vec2 p, float scale, float threshold)
{
	vec2 g = p * scale;
	vec2 cell = floor(g);
	float h = hash12(cell);
	if (h < threshold)
	{
		return 0.0;
	}

	// Offset within the cell so craters don't sit on a visible grid.
	vec2 jitter = vec2(hash12(cell + 7.1), hash12(cell + 13.3)) - 0.5;
	float d = length(fract(g) - 0.5 - jitter * 0.55);
	float radius = mix(0.13, 0.34, hash12(cell + 3.7));

	float rim = (1.0 - smoothstep(radius * 0.72, radius, d))
		* smoothstep(radius * 0.30, radius * 0.72, d);
	float floor_ = 1.0 - smoothstep(0.0, radius * 0.72, d);

	return rim * 0.30 - floor_ * 0.34;
}

/*
 * The sun: a smooth incandescent disc.
 *
 * Granulation and sunspots were tried and removed. At the sun's on-screen size any
 * surface feature collapses into scattered dark dots, which reads as a pale rocky ball -
 * the same as the moon - rather than something too bright to look at. A sun is defined by
 * being blown out, so the disc is deliberately featureless and heavily overdriven, and
 * all the character lives in the glare around it.
 */
vec3 sunSurface(vec3 dir, float discCos)
{
	float sd = dot(dir, sunDir);
	if (sd < discCos)
	{
		return vec3(0.0);
	}

	vec3 right = normalize(cross(vec3(0.0, -1.0, 0.0), sunDir));
	vec3 upv = cross(sunDir, right);

	float discRadius = sqrt(max(1.0 - discCos * discCos, 1e-6));
	float u = dot(dir, right) / discRadius;
	float v = dot(dir, upv) / discRadius;

	float r = sqrt(clamp(u * u + v * v, 0.0, 1.0));

	// Blazing white core easing to a warm rim, with the edge feathered into the glare
	// rather than stopping at a hard circle.
	vec3 col = mix(vec3(1.0, 0.99, 0.95), vec3(1.0, 0.78, 0.42), smoothstep(0.35, 1.0, r));
	float edge = 1.0 - smoothstep(0.80, 1.05, r);

	return col * edge * 2.2 * sunGlow;
}

/*
 * Glare around the sun: a tight halo, a wide bloom, and streaks radiating outward.
 *
 * The streaks are oriented in the sun's own frame rather than screen space, so they stay
 * fixed to it as the camera turns - screen-aligned streaks read as a camera artifact
 * pinned to the display, which is wrong for something being looked at in the world.
 */
vec3 sunGlareColor(vec3 dir)
{
	if (sunGlare < 0.001)
	{
		return vec3(0.0);
	}

	float sd = dot(dir, sunDir);
	if (sd <= 0.0)
	{
		return vec3(0.0);
	}

	// Tight halo hugging the disc, then a broad falloff reaching well out into the sky.
	float halo = pow(smoothstep(0.988, 1.0, sd), 2.0);
	float wide = pow(smoothstep(0.55, 1.0, sd), 3.0);

	vec3 right = normalize(cross(vec3(0.0, -1.0, 0.0), sunDir));
	vec3 upv = cross(sunDir, right);
	float a = atan(dot(dir, upv), dot(dir, right));

	// Four main spikes with four shorter ones between them.
	float spikes = pow(abs(cos(a * 2.0)), 28.0)
		+ pow(abs(cos(a * 2.0 + 0.7854)), 40.0) * 0.45;
	spikes *= pow(smoothstep(0.72, 1.0, sd), 1.6);

	vec3 warm = vec3(1.0, 0.80, 0.50);
	return warm * (halo * 0.9 + wide * 0.35 + spikes * 0.55) * sunGlare;
}

/*
 * The moon as a lit sphere rather than a flat disc.
 *
 * Each pixel inside the disc is projected back onto the sphere it represents, and the
 * surface is sampled in those coordinates - which is what makes craters crowd together
 * toward the limb instead of staying evenly spaced to the edge, the giveaway that a disc
 * is flat.
 */
vec3 moonSurface(vec3 dir, float discCos)
{
	float md = dot(dir, moonDir);
	if (md < discCos)
	{
		return vec3(0.0);
	}

	// Local frame on the disc. Moon elevation is shallow, so world up is never parallel
	// to moonDir and this cannot degenerate.
	vec3 right = normalize(cross(vec3(0.0, -1.0, 0.0), moonDir));
	vec3 upv = cross(moonDir, right);

	float discRadius = sqrt(max(1.0 - discCos * discCos, 1e-6));
	float u = dot(dir, right) / discRadius;
	float v = dot(dir, upv) / discRadius;

	float r2 = clamp(u * u + v * v, 0.0, 1.0);
	// Height of the sphere's surface above the disc plane - 1 at the centre, 0 at the limb.
	float w = sqrt(1.0 - r2);

	// Dividing by w would blow up at the limb, so this is a bounded approximation of the
	// same compression.
	vec2 suv = vec2(u, v) / (0.35 + 0.65 * w);

	float surface = 1.0;
	surface += craterField(suv, 5.0, 0.52);
	surface += craterField(suv, 11.0, 0.62) * 0.7;
	surface += craterField(suv, 23.0, 0.72) * 0.4;

	// Maria: the broad dark plains.
	surface -= smoothstep(0.42, 0.78, valueNoise(suv * 1.7 + 4.3)) * 0.20;

	// Limb darkening, plus a soft edge so the disc doesn't alias against the sky.
	surface *= 0.72 + 0.28 * w;
	float edge = 1.0 - smoothstep(0.86, 1.0, sqrt(r2));

	/*
	 * Phase. The terminator is not a straight line across the disc but an ellipse: it is
	 * the circular edge of the lit hemisphere seen at an angle, so it bows and its width
	 * follows the cosine of the phase angle. A straight cut reads as a wipe rather than a
	 * sphere being lit from the side.
	 *
	 * The unlit side is dimmed rather than removed, since earthshine keeps a real moon's
	 * dark limb faintly visible.
	 */
	float d = cos(6.28318 * moonPhase);
	float limb = sqrt(max(0.0, 1.0 - v * v));

	/*
	 * Waxing lights from one side, waning from the other - and the terminator mirrors
	 * with it. Flipping only which side is tested, while reusing the same terminator,
	 * inverts the whole disc: it makes new and full swap places, so a full moon renders
	 * dark. The waning half needs -d, not d.
	 *
	 *   waxing p=0   -> d=1,  lit where u >  limb  -> nothing (new)
	 *   waxing p=0.5 -> d=-1, lit where u > -limb  -> everything (full)
	 *   waning p=0.5 -> -d=1, lit where u <  limb  -> everything (full, continuous)
	 *   waning p=1   -> -d=-1, lit where u < -limb -> nothing (new)
	 */
	bool waxing = moonPhase < 0.5;
	float edgeX = (waxing ? d : -d) * limb;
	float side = waxing ? 1.0 : -1.0;

	float litness = smoothstep(-0.06, 0.06, (u - edgeX) * side);
	float lit = mix(0.10, 1.0, litness);

	return vec3(0.96, 0.95, 0.88) * clamp(surface, 0.0, 1.4) * edge * lit;
}

/*
 * A world direction from a compass bearing and a height above the horizon.
 */
vec3 dirFromBearingHeight(float bearing, float height)
{
	float h = clamp(height, 0.0, 1.0);
	float horiz = sqrt(max(0.0, 1.0 - h * h));
	// World Y is negative-up, hence the negated vertical component.
	return normalize(vec3(sin(bearing) * horiz, -h, cos(bearing) * horiz));
}

/*
 * Shooting stars.
 *
 * Time is cut into fixed slots, and each slot is hashed to decide whether a meteor flies
 * in it and where it goes - so every pixel independently agrees on the same meteor
 * without any state being stored between frames.
 *
 * The trail is accumulated by sampling backwards along the flight path from the current
 * head position, which gives the taper for free: earlier samples are both further back
 * and weighted lower.
 */
vec3 shootingStar(vec3 dir, float night)
{
	float travel = meteorTravel;
	if (meteorActive < 0.5 || night < 0.35 || travel > 1.0)
	{
		return vec3(0.0);
	}

	vec3 a = dirFromBearingHeight(meteorPath.x, meteorPath.y);
	vec3 b = dirFromBearingHeight(meteorPath.x + meteorPath.z, meteorPath.y - meteorPath.w);

	// Sample back along the path; nearer the head is brighter and tighter.
	int tailSamples = max(meteorSamples, 4);
	const float TAIL = 0.22;
	float glow = 0.0;

	for (int i = 0; i < tailSamples; ++i)
	{
		float f = float(i) / float(tailSamples - 1);
		float tt = travel - f * TAIL;
		if (tt < 0.0)
		{
			break;
		}

		vec3 p = normalize(mix(a, b, tt));
		float ang = 1.0 - dot(dir, p);

		float w = 1.0 - f;
		// Head is a tight point; the trail widens and dims behind it.
		glow += w * w * exp(-ang * mix(90000.0, 2200000.0, w));
	}

	// Fade in quickly, fade out over the last stretch of the flight.
	float life = smoothstep(0.0, 0.06, travel) * (1.0 - smoothstep(0.62, 1.0, travel));

	return vec3(1.0, 0.96, 0.88) * glow * life * night;
}

/*
 * Aurora: shimmering curtains low in the northern sky.
 *
 * Confined to a compass sector rather than ringing the horizon, since an aurora that
 * surrounds you reads as a screen effect rather than something in the distance. The
 * curtain structure is noise sampled across bearing and height, scrolling in both, which
 * gives the vertical streaking and the slow drift along the band.
 */
vec3 aurora(vec3 dir, float up, float night)
{
	if (auroraStrength < 0.001 || night < 0.25)
	{
		return vec3(0.0);
	}

	vec2 dxz = dir.xz;
	float len = length(dxz);
	if (len < 1e-5)
	{
		return vec3(0.0);
	}
	dxz /= len;

	/*
	 * Northern sky only - these are the northern lights, and one ringing the horizon
	 * would read as a screen effect rather than something far away to the north.
	 *
	 * The arc is deliberately wide though: strong from northwest through northeast and
	 * only tailing off past due east and west, so it covers the northern sky rather than
	 * demanding the camera point exactly at it.
	 */
	float north = dxz.y;
	float sector = smoothstep(-0.30, 0.55, north);
	if (sector <= 0.0)
	{
		return vec3(0.0);
	}

	float h = clamp(up, 0.0, 1.0);
	float bearing = atan(dxz.x, dxz.y);
	float t = auroraTime;

	/*
	 * An aurora is a hanging ribbon of vertical rays, not a cloud. Three things define
	 * the look, and isotropic blobs have none of them:
	 *
	 *  - a bottom edge that snakes across the sky, which the curtain hangs up from
	 *  - rays running strictly vertically, so the field must vary fast across bearing
	 *    and slowly with height
	 *  - a sharp bright lower edge fading out toward the top
	 *
	 * Motion is waves travelling ALONG the ribbon rather than the field churning in
	 * place: the base line undulates and the rays shift sideways, which is what reads as
	 * an aurora rippling.
	 */
	vec3 total = vec3(0.0);

	for (int band = 0; band < 2; ++band)
	{
		float fb = float(band);

		/*
		 * Where this curtain's lower edge sits. Two sines give the broad snake, and a
		 * noise term roughens it - a purely sinusoidal edge reads as a clean drawn curve
		 * rather than the ragged bottom a real curtain has.
		 */
		float base = 0.13 + fb * 0.075
			+ sin(bearing * 2.3 + t * 0.055 + fb * 2.1) * 0.030
			+ sin(bearing * 4.7 - t * 0.037 + fb * 4.3) * 0.017
			+ (valueNoise(vec2(bearing * 9.0 + t * 0.04, fb * 5.0)) - 0.5) * 0.026;

		float above = h - base;
		if (above < 0.0)
		{
			continue;
		}

		/*
		 * Ray length varies along the ribbon, so some shoot well above the rest instead
		 * of the whole curtain ending at one flat height.
		 */
		float lengthVary = valueNoise(vec2(bearing * 14.0 + t * 0.03, fb * 2.0 + 3.1));
		float height = mix(0.20, 0.14, fb) * (0.55 + 1.15 * lengthVary);

		// Bright, tight lower edge fading up - the defining aurora gradient. The base is
		// softened rather than a hard cut, so the bottom dissolves instead of stopping.
		float fade = (1.0 - smoothstep(0.0, height, above)) * smoothstep(0.0, 0.030, above);

		/*
		 * Vertical rays. The large bearing multiplier against a small height one is what
		 * makes the structure vertical - sampling fast horizontally and slowly upward
		 * stretches the noise into columns.
		 */
		float sway = t * 0.06 + fb * 7.0;
		float rays = valueNoise(vec2(bearing * 34.0 + sway, h * 1.6));
		rays *= 0.55 + 0.45 * valueNoise(vec2(bearing * 78.0 - sway * 0.6, h * 2.4));
		rays = pow(clamp(rays, 0.0, 1.0), 1.7);

		// Slow brightening and dimming along the ribbon, so it pulses rather than sitting.
		float pulse = 0.55 + 0.45 * valueNoise(vec2(bearing * 1.7 + t * 0.045, fb * 3.0));

		/*
		 * Green along the bottom edge climbing into violet. The transition sits low in
		 * the curtain on purpose: putting it near the top hides the violet entirely,
		 * because that is exactly where the brightness fade has already gone to nothing.
		 * The violet is also boosted, since it is competing with a much brighter green.
		 */
		float up01 = smoothstep(0.0, height * 0.45, above);
		vec3 col = mix(vec3(0.20, 1.0, 0.55), vec3(0.70, 0.30, 1.0), up01);
		col *= 1.0 + up01 * 0.6;

		total += col * rays * fade * pulse * mix(1.0, 0.6, fb);
	}

	return total * sector * night * auroraStrength;
}

/*
 * Horizontal wander of the bolt as it descends, as a signed angular offset.
 *
 * Built from straight segments with random endpoints rather than summed sines: real
 * lightning is piecewise-linear with sharp corners, and sines - however many you layer -
 * always read as a smooth snake.
 */
float boltWander(float h, float seed)
{
	// Coarse zigzag: the overall path of the bolt.
	float segs = 12.0;
	float x = h * segs + seed;
	float i = floor(x);
	float f = fract(x);
	float coarse = mix(hash11(i) - 0.5, hash11(i + 1.0) - 0.5, f);

	// Fine kinks on top, so each straight run isn't perfectly clean. Kept modest: a very
	// high segment count makes the path move further per pixel row than the stroke is
	// wide, which breaks it into disconnected fragments.
	float fsegs = 30.0;
	float fx = h * fsegs + seed * 3.0;
	float fi = floor(fx);
	float ff = fract(fx);
	float fine = mix(hash11(fi + 91.3) - 0.5, hash11(fi + 92.3) - 0.5, ff);

	return coarse * 0.085 + fine * 0.016;
}

/*
 * Perpendicular distance from the bolt's path, rather than the horizontal gap.
 *
 * Measuring horizontally makes a steeply-slanting stroke effectively thinner - and where
 * the path moves further per pixel row than the stroke is wide, it breaks up entirely.
 * Dividing by sqrt(1 + slope^2) converts the horizontal gap into true perpendicular
 * distance, keeping the width constant however sharply the bolt zigzags.
 */
float boltDistance(float h, float ang, float seed)
{
	const float eps = 0.004;
	float w = boltWander(h, seed);
	float slope = (boltWander(h + eps, seed) - boltWander(h - eps, seed)) / (2.0 * eps);
	return abs(ang - w) / sqrt(1.0 + slope * slope);
}

/*
 * A bolt descending from the cloud deck toward the horizon at a fixed compass bearing,
 * so it stays put in the world as the camera turns.
 */
vec3 lightningBolt(vec3 dir, float up)
{
	vec2 dxz = dir.xz;
	float len = length(dxz);
	if (len < 1e-5)
	{
		return vec3(0.0);
	}
	dxz /= len;

	// Only visible when looking toward the strike.
	float facing = dot(dxz, boltDirXZ);
	if (facing <= 0.0)
	{
		return vec3(0.0);
	}

	// Signed bearing offset from the strike direction.
	float side = dxz.x * boltDirXZ.y - dxz.y * boltDirXZ.x;
	float ang = atan(side, facing);

	float h = clamp(up, 0.0, 1.0);

	// Runs from just above the horizon up into the cloud deck, tapering at both ends.
	float extent = smoothstep(0.01, 0.06, h) * (1.0 - smoothstep(0.34, 0.52, h));
	if (extent <= 0.0)
	{
		return vec3(0.0);
	}

	float main_ = boltDistance(h, ang, boltSeed);

	// A fork branching off partway down, offset sideways and living only over the lower
	// stretch - a single unbroken line reads as a wire rather than a strike.
	float forkOffset = (hash11(boltSeed + 4.7) - 0.5) * 0.10;
	float fork = boltDistance(h, ang - forkOffset, boltSeed + 19.3);
	float forkExtent = smoothstep(0.02, 0.07, h) * (1.0 - smoothstep(0.18, 0.30, h));

	// Tight core, wide soft glow. The narrow core is what makes it read as sharp.
	float core = (1.0 - smoothstep(0.0, 0.0045, main_))
		+ (1.0 - smoothstep(0.0, 0.0032, fork)) * forkExtent;
	float glow = (1.0 - smoothstep(0.0, 0.055, main_))
		+ (1.0 - smoothstep(0.0, 0.035, fork)) * forkExtent * 0.7;

	// Overdriven core so the bolt stays legible against a bright overcast sky - it is
	// competing with the frame-wide flash firing at the same moment.
	vec3 c = vec3(1.6, 1.58, 1.5) * clamp(core, 0.0, 1.0)
		+ vec3(0.60, 0.70, 1.0) * clamp(glow, 0.0, 1.0) * 0.45;
	return c * extent * boltStrength;
}

void main()
{
	vec3 dir = viewDirection();
	vec3 col = skyColor;

	// World Y is negative-up, so this is how far above the horizon we are looking.
	float up = clamp(-dir.y, 0.0, 1.0);
	float horizonFade = smoothstep(0.0, 0.18, up);
	float day = 1.0 - night;

	/*
	 * Horizon colour grading into the zenith colour with height.
	 *
	 * This is a hue shift, not just a darkening. At sunset the horizon burns orange while
	 * overhead stays blue, and colouring the whole dome from the horizon alone is what
	 * makes a sunset look dipped in orange paint. The curve is weighted low so the warm
	 * band hugs the horizon rather than filling half the sky.
	 */
	col = mix(col, zenithColor, pow(smoothstep(0.0, 0.75, up), 0.75));

	// --- Sun ---
	if (showSun > 0.5 && day > 0.001)
	{
		const float SUN_DISC_COS = 0.9975;
		float vis = horizonFade * day;

		// Glare is additive and goes down first, so the disc blazes on top of it.
		col += sunGlareColor(dir) * vis;
		col += sunSurface(dir, SUN_DISC_COS) * vis;
	}

	// --- Moon and stars ---
	float nightVis = night * horizonFade;
	if (nightVis > 0.001)
	{
		// Drawn under the stars so they still read through it.
		col += aurora(dir, up, night) * horizonFade;

		col += vec3(1.0, 0.98, 0.92) * starField(dir) * nightVis;

		// Above the starfield, so a meteor reads as passing in front of the stars.
		col += shootingStar(dir, night) * horizonFade;

		if (showMoon > 0.5)
		{
			const float MOON_DISC_COS = 0.9985;

			// Halo first, so the disc sits on top of it.
			float glow = smoothstep(0.985, 1.0, dot(dir, moonDir));
			col += vec3(0.40, 0.42, 0.50) * glow * 0.5 * nightVis * moonGlow;

			vec3 moon = moonSurface(dir, MOON_DISC_COS) * moonGlow;
			// Replaces the sky rather than adding to it, so the surface keeps its
			// contrast instead of the maria being washed out by whatever is behind.
			col = mix(col, moon, min(length(moon), 1.0) * nightVis);
		}
	}

	// --- Clouds ---
	// Projected onto a flat layer overhead, which naturally compresses them toward the
	// horizon the way a real cloud deck looks.
	if (cloudAmount > 0.001 && up > 0.02)
	{
		vec2 uv = dir.xz / max(up, 0.06) * 0.55;

		// Driven by the sky clock rather than a free-running timer, so the deck advances
		// with the hour: dawn and dusk show a different sky, and scrubbing the preview
		// hour moves the clouds along with the sun instead of leaving them put.
		uv += vec2(cloudTime * 0.0020, cloudTime * 0.0010);

		float n = cloudFbm(uv * 1.4, cloudTime);
		float cover = mix(0.72, 0.28, cloudAmount);
		float c = smoothstep(cover, cover + 0.22, n);

		// Fade out at the horizon where the projection stretches into mush, and fade in
		// with how far above the horizon we're looking.
		c *= smoothstep(0.02, 0.16, up);

		// Lit white by day, dim blue-grey by night, warmed slightly toward the sun.
		vec3 lit = mix(vec3(0.22, 0.25, 0.34), vec3(1.0, 0.99, 0.96), day);
		float sunward = max(dot(dir, sunDir), 0.0);
		lit = mix(lit, vec3(1.0, 0.80, 0.60), pow(sunward, 8.0) * day * 0.6);

		col = mix(col, lit, c * cloudOpacity);
	}

	// Drawn over the clouds - the bolt hangs below the deck it comes out of.
	if (boltStrength > 0.001)
	{
		col += lightningBolt(dir, up);
	}

	FragColor = vec4(col, 1.0);
}
