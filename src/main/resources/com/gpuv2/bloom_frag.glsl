#version 330

in vec2 fNdc;
out vec4 FragColor;

uniform sampler2D src;
uniform int bloomPass;    // 0 = bright extract, 1 = blur, 2 = passthrough for compositing
uniform vec2 blurDir;     // one texel step along the blur axis
uniform float threshold;
uniform float intensity;

void main()
{
	vec2 uv = fNdc * 0.5 + 0.5;

	if (bloomPass == 0)
	{
		// Keep only what is brighter than the threshold, rescaled so the retained part
		// ramps from zero rather than stepping straight to full brightness.
		vec3 c = texture(src, uv).rgb;
		float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
		float k = max(luma - threshold, 0.0) / max(1.0 - threshold, 1e-4);
		FragColor = vec4(c * k, 1.0);
	}
	else if (bloomPass == 1)
	{
		// Separable gaussian - run once horizontally, once vertically.
		float w0 = 0.227027;
		float w1 = 0.194594;
		float w2 = 0.121621;
		float w3 = 0.054054;
		float w4 = 0.016216;

		vec3 acc = texture(src, uv).rgb * w0;
		acc += texture(src, uv + blurDir * 1.0).rgb * w1;
		acc += texture(src, uv - blurDir * 1.0).rgb * w1;
		acc += texture(src, uv + blurDir * 2.0).rgb * w2;
		acc += texture(src, uv - blurDir * 2.0).rgb * w2;
		acc += texture(src, uv + blurDir * 3.0).rgb * w3;
		acc += texture(src, uv - blurDir * 3.0).rgb * w3;
		acc += texture(src, uv + blurDir * 4.0).rgb * w4;
		acc += texture(src, uv - blurDir * 4.0).rgb * w4;

		FragColor = vec4(acc, 1.0);
	}
	else
	{
		// Drawn with additive blending, so this is just the scaled bloom contribution.
		FragColor = vec4(texture(src, uv).rgb * intensity, 1.0);
	}
}
