#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform int weatherType;      // 1 = rain, 2 = snow
uniform float weatherTime;
uniform float weatherAmount;  // 0..1
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
 */
float rain(vec2 uv, float t)
{
	float total = 0.0;

	for (int layer = 0; layer < 3; ++layer)
	{
		float fl = float(layer);
		float scale = 1.0 + fl * 0.8;
		float speed = 1.0 + fl * 0.7;

		vec2 p = uv * vec2(70.0 * scale * aspect, 13.0 * scale);
		p.x += p.y * 0.22;            // slant, so rain doesn't fall dead vertical
		p.y -= t * speed * 14.0;

		vec2 cell = floor(p);
		float h = hash12(cell + fl * 31.7);

		// Higher layers are sparser, so the near layer reads as the dominant one.
		float density = 0.94 + fl * 0.015;
		if (h < density)
		{
			continue;
		}

		vec2 f = fract(p);
		float across = 1.0 - smoothstep(0.0, 0.10, abs(f.x - 0.5));
		float along = smoothstep(0.0, 0.55, f.y) * (1.0 - smoothstep(0.55, 1.0, f.y));
		total += across * along * (0.35 + 0.65 * h) / (1.0 + fl);
	}

	return total;
}

/*
 * Drifting flakes: same cell approach, but round, slower, and swaying sideways.
 */
float snow(vec2 uv, float t)
{
	float total = 0.0;

	for (int layer = 0; layer < 3; ++layer)
	{
		float fl = float(layer);
		float scale = 1.0 + fl * 0.9;
		float speed = 0.35 + fl * 0.22;

		vec2 p = uv * vec2(26.0 * scale * aspect, 26.0 * scale);
		p.y -= t * speed * 4.0;
		p.x += sin(t * (0.5 + fl * 0.3) + p.y * 0.35) * 0.6;

		vec2 cell = floor(p);
		float h = hash12(cell + fl * 17.3);

		float density = 0.965 + fl * 0.008;
		if (h < density)
		{
			continue;
		}

		// Jitter within the cell so flakes don't sit on a visible lattice.
		vec2 jitter = vec2(hash12(cell + 5.1), hash12(cell + 9.7)) - 0.5;
		float d = length(fract(p) - 0.5 - jitter * 0.5);

		total += (1.0 - smoothstep(0.0, 0.22, d)) * (0.4 + 0.6 * h) / (1.0 + fl * 0.6);
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
		color = vec3(0.72, 0.78, 0.88);
	}
	else
	{
		amount = snow(uv, t) * weatherAmount;
		color = vec3(0.96, 0.97, 1.0);
	}

	FragColor = vec4(color * amount, clamp(amount, 0.0, 1.0));
}
