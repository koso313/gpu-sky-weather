#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform sampler2D src;
uniform vec2 texel;      // 1 / resolution
uniform float useFxaa;
uniform float sharpen;   // 0 disables
uniform float vignette;  // 0 disables

float luma(vec3 c)
{
	return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

/*
 * Compact FXAA.
 *
 * Finds the local contrast across a pixel's four neighbours, works out which way the edge
 * runs from where the brightness sits, and takes one blended tap along it. Far simpler
 * than the full algorithm, but it handles the case that matters here: the aurora, clouds,
 * meteors and lightning are drawn by shaders rather than geometry, so MSAA cannot touch
 * their edges at all.
 */
vec3 fxaa(vec2 uv)
{
	vec3 rgbM = texture(src, uv).rgb;

	float lM = luma(rgbM);
	float lNW = luma(texture(src, uv + vec2(-texel.x, -texel.y)).rgb);
	float lNE = luma(texture(src, uv + vec2(texel.x, -texel.y)).rgb);
	float lSW = luma(texture(src, uv + vec2(-texel.x, texel.y)).rgb);
	float lSE = luma(texture(src, uv + vec2(texel.x, texel.y)).rgb);

	float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
	float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));

	// Flat areas are left alone - blending them would just soften the whole image.
	if (lMax - lMin < max(0.05, lMax * 0.125))
	{
		return rgbM;
	}

	vec2 dir = vec2(
		-((lNW + lNE) - (lSW + lSE)),
		((lNW + lSW) - (lNE + lSE))
	);

	float reduce = max((lNW + lNE + lSW + lSE) * 0.03125, 0.0078125);
	float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
	dir = clamp(dir * rcp, -8.0, 8.0) * texel;

	vec3 a = 0.5 * (texture(src, uv + dir * (1.0 / 3.0 - 0.5)).rgb
		+ texture(src, uv + dir * (2.0 / 3.0 - 0.5)).rgb);
	vec3 b = a * 0.5 + 0.25 * (texture(src, uv - dir * 0.5).rgb
		+ texture(src, uv + dir * 0.5).rgb);

	// The wider tap overshoots on strong edges; fall back to the tighter one there.
	float lB = luma(b);
	return (lB < lMin || lB > lMax) ? a : b;
}

void main()
{
	vec2 uv = fNdc * 0.5 + 0.5;

	vec3 col = useFxaa > 0.5 ? fxaa(uv) : texture(src, uv).rgb;

	if (sharpen > 0.001)
	{
		// Unsharp mask against the four neighbours - cheap, and it does not ring the way
		// a wider kernel does.
		vec3 n = texture(src, uv + vec2(0.0, -texel.y)).rgb;
		vec3 s = texture(src, uv + vec2(0.0, texel.y)).rgb;
		vec3 e = texture(src, uv + vec2(texel.x, 0.0)).rgb;
		vec3 w = texture(src, uv + vec2(-texel.x, 0.0)).rgb;

		col += (col * 4.0 - n - s - e - w) * sharpen;
	}

	if (vignette > 0.001)
	{
		// Distance from centre, normalised so the corners reach 1.
		float d = length(uv - 0.5) * 1.4142;
		col *= mix(1.0, 1.0 - vignette, smoothstep(0.35, 1.0, d));
	}

	FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
