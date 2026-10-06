#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform sampler2D src;
uniform int rayPass;        // 0 = bright extract toward the sun, 1 = radial blur
uniform vec2 sunUv;         // sun position in 0..1 screen space
uniform float threshold;
uniform float decay;
uniform float density;
uniform float intensity;   // 1.0 while blurring; scales the result when compositing
uniform int rayCount;      // shaft samples; follows the effect quality setting

/*
 * Screen-space light shafts.
 *
 * Pass 0 keeps only what is bright enough to be a light source and fades it with distance
 * from the sun, so the shafts come from the sun rather than from every bright pixel.
 *
 * Pass 1 smears that radially outward from the sun by marching samples back toward it,
 * dimming each step - the classic accumulation approach, which needs no depth buffer.
 */
void main()
{
	vec2 uv = fNdc * 0.5 + 0.5;

	if (rayPass == 0)
	{
		vec3 c = texture(src, uv).rgb;
		float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
		float k = max(luma - threshold, 0.0) / max(1.0 - threshold, 1e-4);

		// Only near the sun contributes, so shafts radiate from it rather than from
		// every bright surface in the scene.
		float near = 1.0 - smoothstep(0.0, 0.75, distance(uv, sunUv));

		FragColor = vec4(c * k * near, 1.0);
		return;
	}

	// Sample count is the dominant cost here, so it follows the quality setting.
	int samples = max(rayCount, 4);

	vec2 delta = (uv - sunUv) * (density / float(samples));
	vec2 pos = uv;
	float weight = 1.0;
	vec3 acc = vec3(0.0);

	for (int i = 0; i < samples; ++i)
	{
		pos -= delta;
		acc += texture(src, pos).rgb * weight;
		weight *= decay;
	}

	FragColor = vec4(acc / float(samples) * intensity, 1.0);
}
