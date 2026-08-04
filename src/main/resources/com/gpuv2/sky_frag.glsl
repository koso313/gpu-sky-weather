#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform vec3 skyColor;
uniform float night;      // 0 = full daylight, 1 = full dark
uniform float starDensity;
uniform float halfW;      // viewportWidth  / (2 * clientScale)
uniform float halfH;      // viewportHeight / (2 * clientScale)
uniform float cosPitch;
uniform float sinPitch;
uniform float cosYaw;
uniform float sinYaw;
uniform float starTime;   // seconds, drives twinkle and cloud drift
uniform vec3 sunDir;      // normalised world direction toward the sun
uniform vec3 moonDir;     // normalised world direction toward the moon
uniform float showMoon;
uniform float showSun;
uniform float cloudAmount;   // 0 = clear, 1 = overcast
uniform float cloudOpacity;

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
 * Horizontal wander of the bolt as it descends, as a signed angular offset.
 *
 * Built from straight segments with random endpoints rather than summed sines: real
 * lightning is piecewise-linear with sharp corners, and sines - however many you layer -
 * always read as a smooth snake.
 */
float boltWander(float h, float seed)
{
	// Coarse zigzag: the overall path of the bolt.
	float segs = 22.0;
	float x = h * segs + seed;
	float i = floor(x);
	float f = fract(x);
	float coarse = mix(hash11(i) - 0.5, hash11(i + 1.0) - 0.5, f);

	// Fine kinks on top, so each straight run isn't perfectly clean.
	float fsegs = 74.0;
	float fx = h * fsegs + seed * 3.0;
	float fi = floor(fx);
	float ff = fract(fx);
	float fine = mix(hash11(fi + 91.3) - 0.5, hash11(fi + 92.3) - 0.5, ff);

	return coarse * 0.085 + fine * 0.022;
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

	float main_ = abs(ang - boltWander(h, boltSeed));

	// A fork branching off partway down, offset sideways and living only over the lower
	// stretch - a single unbroken line reads as a wire rather than a strike.
	float forkOffset = (hash11(boltSeed + 4.7) - 0.5) * 0.10;
	float fork = abs(ang - (boltWander(h, boltSeed + 19.3) + forkOffset));
	float forkExtent = smoothstep(0.02, 0.07, h) * (1.0 - smoothstep(0.18, 0.30, h));

	// Tight core, wide soft glow. The narrow core is what makes it read as sharp.
	float core = (1.0 - smoothstep(0.0, 0.0022, main_))
		+ (1.0 - smoothstep(0.0, 0.0016, fork)) * forkExtent;
	float glow = (1.0 - smoothstep(0.0, 0.045, main_))
		+ (1.0 - smoothstep(0.0, 0.030, fork)) * forkExtent * 0.7;

	vec3 c = vec3(1.0, 0.99, 0.95) * clamp(core, 0.0, 1.0)
		+ vec3(0.55, 0.65, 1.0) * clamp(glow, 0.0, 1.0) * 0.30;
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

	// Real skies are pale at the horizon and deepen overhead. Applied only by day - the
	// night sky already reads well flat, and darkening it further just crushes the stars.
	col = mix(col, col * 0.76, smoothstep(0.0, 0.6, up) * day);

	// --- Sun ---
	if (showSun > 0.5 && day > 0.001)
	{
		vec2 s = celestial(dir, sunDir, 0.9975, 0.96);
		float vis = horizonFade * day;
		col += vec3(1.0, 0.72, 0.42) * s.y * 0.55 * vis;
		col = mix(col, vec3(1.0, 0.96, 0.82), s.x * vis);
	}

	// --- Moon and stars ---
	float nightVis = night * horizonFade;
	if (nightVis > 0.001)
	{
		col += vec3(1.0, 0.98, 0.92) * starField(dir) * nightVis;

		if (showMoon > 0.5)
		{
			vec2 m = celestial(dir, moonDir, 0.9985, 0.985);
			col += vec3(0.40, 0.42, 0.50) * m.y * 0.5 * nightVis;
			col = mix(col, vec3(0.96, 0.95, 0.88), m.x * nightVis);
		}
	}

	// --- Clouds ---
	// Projected onto a flat layer overhead, which naturally compresses them toward the
	// horizon the way a real cloud deck looks.
	if (cloudAmount > 0.001 && up > 0.02)
	{
		vec2 uv = dir.xz / max(up, 0.06) * 0.55;
		uv += vec2(starTime * 0.004, starTime * 0.002);

		float n = fbm(uv * 1.4);
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
