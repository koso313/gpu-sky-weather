#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform int weatherType;      // 1 = rain family, 2 = snow family
uniform float weatherTime;
uniform float weatherAmount;  // 0..1
uniform float weatherHeavy;   // 0 = calm, 1 = storm/blizzard
uniform float lightning;      // 0..1 flash this frame
uniform float aspect;         // viewport width / height

float hash12(vec2 p)
{
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

/*
 * Layered falling streaks. Each layer uses a different cell scale and fall speed, which
 * reads as depth without needing any actual depth information.
 *
 * uv.y increases upward, so falling means ADDING time to p.y: a droplet holding a fixed
 * p.y then sits at a lower uv.y each frame.
 */
float rain(vec2 uv, float t)
{
	float total = 0.0;
	float speedUp = mix(1.0, 2.1, weatherHeavy);
	float slant = mix(0.16, 0.42, weatherHeavy);

	for (int layer = 0; layer < 3; ++layer)
	{
		float fl = float(layer);
		float scale = 1.0 + fl * 0.7;

		vec2 p = uv * vec2(52.0 * scale * aspect, 5.5 * scale);
		p.x += uv.y * slant * 26.0;                       // wind-driven slant
		p.y += t * (5.2 + fl * 2.4) * speedUp;            // + => falls downward

		vec2 cell = floor(p);
		float h = hash12(cell + fl * 31.7);

		float density = mix(0.88, 0.72, weatherHeavy) + fl * 0.02;
		if (h < density)
		{
			continue;
		}

		vec2 f = fract(p);

		// Narrow across, long along - a rain streak, not a dash.
		float across = 1.0 - smoothstep(0.0, 0.055, abs(f.x - 0.5));
		float along = smoothstep(0.0, 0.30, f.y) * (1.0 - smoothstep(0.45, 1.0, f.y));

		total += across * along * (0.5 + 0.5 * h) / (1.0 + fl * 0.5);
	}

	return total;
}

/*
 * Drifting flakes: round, slower, swaying sideways, with per-flake size variation so they
 * don't all read as identical dots.
 */
float snow(vec2 uv, float t)
{
	float total = 0.0;
	float speedUp = mix(1.0, 3.4, weatherHeavy);
	float drift = mix(0.6, 3.2, weatherHeavy);

	/*
	 * Wind arrives in gusts rather than at a constant rate. Two slow, non-harmonic terms
	 * so the pattern doesn't visibly repeat, staying near 1 in calm snow and swinging
	 * hard in a blizzard.
	 */
	float gust = 1.0 + weatherHeavy * (0.55 * sin(t * 0.31) + 0.30 * sin(t * 0.13 + 1.7));

	for (int layer = 0; layer < 3; ++layer)
	{
		float fl = float(layer);
		float scale = 1.0 + fl * 0.85;

		vec2 p = uv * vec2(22.0 * scale * aspect, 22.0 * scale);
		p.y += t * (1.15 + fl * 0.55) * speedUp;          // + => falls downward
		p.x += sin(t * (0.6 + fl * 0.35) + uv.y * 7.0) * drift;
		p.x += t * drift * 0.55 * weatherHeavy * gust;    // blizzards blow sideways

		vec2 cell = floor(p);
		float h = hash12(cell + fl * 17.3);

		float density = mix(0.945, 0.86, weatherHeavy) + fl * 0.01;
		if (h < density)
		{
			continue;
		}

		// Jitter within the cell so flakes don't sit on a visible lattice.
		vec2 jitter = vec2(hash12(cell + 5.1), hash12(cell + 9.7)) - 0.5;
		vec2 q = fract(p) - 0.5 - jitter * 0.55;

		// Wind smears flakes along its direction, so they streak instead of staying round.
		q.x /= 1.0 + weatherHeavy * 1.6 * abs(gust);

		float d = length(q);

		// Vary flake size; the smaller ones read as further away.
		float radius = mix(0.14, 0.34, hash12(cell + 3.3));

		/*
		 * Six-point silhouette: modulating the radius by cos(6*theta) pulls the outline
		 * into arms. Each flake gets its own rotation and tumbles slowly as it falls, so
		 * they aren't all aligned or frozen. The nearest layer gets the most pronounced
		 * arms - distant flakes are too small for the shape to survive, so they stay round.
		 */
		float spin = (hash12(cell + 27.4) - 0.5) * 1.6;
		float theta = atan(q.y, q.x) + hash12(cell + 12.9) * 6.2831 + t * spin;
		float arms = mix(0.30, 0.0, min(fl, 1.0));
		float shaped = radius * (1.0 - arms + arms * abs(cos(theta * 3.0)));

		// Solid core with a soft edge, so flakes read as opaque rather than smoky.
		float flake = 1.0 - smoothstep(shaped * 0.55, shaped, d);

		total += flake * (0.7 + 0.3 * h) / (1.0 + fl * 0.35);
	}

	return total;
}

void main()
{
	vec2 uv = fNdc * 0.5 + 0.5;
	float t = weatherTime;

	float amount;
	vec3 color;

	if (weatherType == 1)
	{
		amount = rain(uv, t) * weatherAmount;
		color = vec3(0.78, 0.84, 0.94);
	}
	else
	{
		// Snow is scaled less by the amount slider than rain: thinning snowfall should
		// mean fewer flakes, not translucent grey ones.
		amount = snow(uv, t) * (0.55 + 0.45 * weatherAmount);
		color = vec3(1.0, 1.0, 1.0);
	}

	amount = clamp(amount, 0.0, 1.0);

	/*
	 * Lightning washes the whole frame, not just the droplets. Kept fairly translucent:
	 * the bolt is drawn in the sky pass, which runs before the scene, so this pass sits
	 * on top of it - too strong a wash and the flash erases the very bolt it belongs to.
	 */
	float flash = clamp(lightning, 0.0, 1.0);
	vec3 rgb = color * amount + vec3(0.85, 0.88, 1.0) * flash * 0.7;
	float alpha = clamp(amount + flash * 0.34, 0.0, 1.0);

	FragColor = vec4(rgb, alpha);
}
