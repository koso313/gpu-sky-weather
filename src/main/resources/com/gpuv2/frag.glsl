/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 330

//#define FRAG_UVS
//#define ZBUF_DEBUG

#include colorblind_mode
#include texture_config

uniform sampler2DArray textures;
uniform float brightness;
uniform float smoothBanding;
uniform vec4 fogColor;
uniform float textureLightMode;

// Colour grading. All neutral at 1.0 / 0.0, in which case applyGrade is a no-op.
uniform float gradeGamma;
uniform float gradeContrast;
uniform float gradeSaturation;
uniform float gradeTemperature;

// Retro stylisation. 0 / <0.5 leaves the image untouched.
uniform float retroNoTextures;
uniform float retroPosterize;   // colour levels per channel; 0 disables
uniform vec3 textureDefaultColors[TEXTURE_COUNT];

// Water. waterFlags[i] is 1.0 for texture ids treated as water.
uniform float waterFlags[TEXTURE_COUNT];
uniform float waterStrength;
uniform float waterChoppiness;
uniform float waterTime;
uniform vec3 waterTint;
uniform vec3 cameraPos;

// Ambient + directional lighting. lightStrength 0 makes applyLighting a no-op.
uniform float lightStrength;
uniform vec3 lightAmbient;
uniform vec3 lightSunColor;
uniform vec3 lightSunDir;

in vec4 fColor;
noperspective centroid in float fHsl;
flat in int fTextureId;
in vec2 fUv;
in float fFogAmount;
in vec3 fWorldPos;
#ifdef ZBUF_DEBUG
in float fDepth;
#endif

out vec4 FragColor;

/*
 * Final grade, applied after fog so it acts on the composed image rather than on
 * surface colours alone. Order is temperature -> gamma -> contrast -> saturation:
 * gamma before contrast, so contrast pivots around mid-grey in the corrected space.
 */
/*
 * Ambient + directional light, modulated over the colour the client already produced.
 *
 * The vertex format carries no normals, so the face normal is reconstructed from the
 * screen-space derivatives of world position. That yields a true per-face (flat) normal,
 * which suits OSRS's low-poly geometry - smooth-shaded surfaces will read faceted.
 *
 * The client bakes its own lighting into vertex colours, so this modulates rather than
 * replaces: the light term is centred on 1.0 so neutral settings leave the image alone.
 */
/*
 * Water surface: animated ripple normal, sky reflection weighted by Fresnel, and a
 * specular glint from the sun.
 *
 * Deliberately analytic rather than texture-driven - crossing sine waves at different
 * frequencies avoid the cost and the asset of a normal map, and at OSRS's scale the
 * difference isn't visible.
 *
 * World Y is negative-up, so the ripple normal points along -Y.
 */
vec3 applyWater(vec3 c)
{
  vec2 p = fWorldPos.xz * 0.018;
  float t = waterTime;

  float rx = sin(p.x * 1.7 + t * 1.3) + sin(p.y * 2.3 - t * 0.9) * 0.6;
  float rz = cos(p.y * 1.9 + t * 1.1) + cos(p.x * 2.1 - t * 0.7) * 0.6;

  // Larger vertical term = flatter water; choppiness scales the horizontal wobble.
  vec3 n = normalize(vec3(rx * waterChoppiness, -4.0, rz * waterChoppiness));

  vec3 v = normalize(cameraPos - fWorldPos);
  float facing = clamp(dot(n, v), 0.0, 1.0);

  // Schlick-style Fresnel: grazing angles reflect the sky, straight-down shows the water.
  float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);

  vec3 body = mix(c, c * waterTint, 0.65);
  vec3 surface = mix(body, fogColor.rgb, clamp(fresnel, 0.0, 0.85));

  // Sun glint. lightSunDir is uploaded regardless of whether scene lighting is on.
  vec3 h = normalize(normalize(lightSunDir) + v);
  float spec = pow(clamp(dot(n, h), 0.0, 1.0), 64.0);
  surface += vec3(1.0, 0.97, 0.9) * spec * 0.5;

  return mix(c, surface, waterStrength);
}

vec3 applyLighting(vec3 c)
{
  if (lightStrength < 0.001)
  {
    return c;
  }

  vec3 dx = dFdx(fWorldPos);
  vec3 dy = dFdy(fWorldPos);
  vec3 n = cross(dx, dy);

  // Degenerate on slivers and perfectly edge-on faces; leave those unlit.
  float len = length(n);
  if (len < 1e-6)
  {
    return c;
  }
  n /= len;

  float diffuse = max(dot(n, normalize(lightSunDir)), 0.0);
  vec3 light = lightAmbient + lightSunColor * diffuse;

  return c * mix(vec3(1.0), light, lightStrength);
}

vec3 applyGrade(vec3 c)
{
  c *= vec3(1.0 + gradeTemperature * 0.20, 1.0, 1.0 - gradeTemperature * 0.20);
  c = pow(max(c, 0.0), vec3(1.0 / max(gradeGamma, 0.01)));
  c = (c - 0.5) * gradeContrast + 0.5;

  float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(luma), c, gradeSaturation);

  c = clamp(c, 0.0, 1.0);

  // Posterise last, so it quantises the final graded image rather than being smeared
  // back into a gradient by the grade.
  if (retroPosterize > 1.5)
  {
    c = floor(c * retroPosterize + 0.5) / retroPosterize;
  }

  return c;
}

#include "hsl_to_rgb.glsl"

#if COLORBLIND_MODE > 0
#include "colorblind.glsl"
#endif

#ifdef ZBUF_DEBUG
float linear_depth(float depth) {
  // depth is computed as 100/z, solve for z
  float z = 100 / depth;
  return 1 - z / 10000;  // we don't have a far plane, but the client uses 10000
}
#endif

void main() {
  vec4 c;

  if (fTextureId > 0 && retroNoTextures > 0.5) {
    // Textures off on a textured face. Its fHsl is a 0-127 lightness, not packed HSL,
    // and its vertex colour is not a usable surface colour - so take the texture's own
    // average colour, the way the vanilla client does without textures, and shade it by
    // that lightness.
    // Matches the textured path's own maths - gamma, then scale by lightness - so
    // toggling textures off changes detail without changing overall brightness.
    float light = fHsl / 127.f;
    vec3 base = pow(textureDefaultColors[fTextureId - 1], vec3(brightness));
    c = vec4(base * light, fColor.a);
  } else if (fTextureId > 0) {
    int textureIdx = fTextureId - 1;

    vec4 textureColor = texture(textures, vec3(fUv, float(textureIdx)));
    vec4 textureColor0 = textureLod(textures, vec3(fUv, float(textureIdx)), 0.f);

    if (textureColor0.a < 1.f)
      discard;

    textureColor = vec4(textureColor.rgb, 1.f);

    textureColor = pow(textureColor, vec4(brightness, brightness, brightness, 1.f));

    // textured triangles hsl is a 7 bit lightness 2-126
    float light = fHsl / 127.f;
    vec3 mul = (1.f - textureLightMode) * vec3(light) + textureLightMode * fColor.rgb;
    c = textureColor * vec4(mul, fColor.a);
  } else {
    // pick interpolated hsl or rgb depending on smooth banding setting
    vec3 hsl = vec3(int(fHsl) >> 10 & 63, int(fHsl) >> 7 & 7, int(fHsl) & 127);
    vec3 rgb = mix(fColor.rgb, hslToRgb(hsl), smoothBanding);
    c = vec4(rgb, fColor.a);
  }

#if COLORBLIND_MODE > 0
  c.rgb = colorblind(c.rgb);
#endif

  vec3 shaded = c.rgb;
  if (waterStrength > 0.001 && fTextureId > 0 && waterFlags[fTextureId - 1] > 0.5) {
    shaded = applyWater(shaded);
  }

  // Light before fog, so fogged distance blends toward the sky colour rather than
  // having the light term applied on top of it.
  vec3 mixedColor = mix(applyLighting(shaded), fogColor.rgb, fFogAmount);
  FragColor = vec4(applyGrade(mixedColor), c.a);

#ifdef FRAG_UVS
  if (fTextureId > 0) {
    FragColor = vec4(fUv.x, 0, fUv.y, 1);
  }
#endif

#ifdef ZBUF_DEBUG
  float dc = linear_depth(fDepth);
  if (dc > 1.0) {
    FragColor = vec4(1, 0, 0, 1);
  } else if (dc < -1.0) {
    FragColor = vec4(0, 0, 1, 1);
  } else if (dc < 0.0) {
    FragColor = vec4(0, 1, 0, 1);
  } else {
    FragColor = vec4(dc, dc, dc, 1);
  }
#endif
}
