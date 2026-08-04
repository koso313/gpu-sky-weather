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

	FragColor = vec4(col, 1.0);
}
