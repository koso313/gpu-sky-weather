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
// Highlight roll-off, 0 off. See tonemap.glsl.
uniform float toneMap;


// Still needed by wet-ground puddles, height fog and aerial perspective.
uniform vec3 cameraPos;

// Ground weather. Both 0 leaves surfaces untouched.
uniform float groundSnow;
uniform float groundWet;

// Cloud shadows. 0 disables.
uniform float cloudShadow;
uniform float cloudShadowTime;

// Aerial perspective: distance haze independent of the fog slider. 0 disables.
uniform float aerial;

// How far underground the player is, 0 open sky to 1 fully enclosed.
uniform float underground;

// Point lights from fires, torches and lanterns. Count 0 disables.
#define MAX_LIGHTS 64
uniform int lightCount;
uniform vec3 lightPos[MAX_LIGHTS];
uniform vec3 lightColor[MAX_LIGHTS];
uniform float lightRadius[MAX_LIGHTS];
uniform float lightFlicker;   // shared flicker phase, animated on the CPU

// Ground mist. 0 disables.
uniform float heightFog;       // overall density
uniform float heightFogTop;    // world Y the mist thins out at
uniform float heightFogDepth;  // how far below that it takes to reach full density

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
float gHash12(vec2 p)
{
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float gNoise(vec2 p)
{
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(gHash12(i), gHash12(i + vec2(1.0, 0.0)), u.x),
             mix(gHash12(i + vec2(0.0, 1.0)), gHash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

/*
 * Face normal from the screen-space derivatives of world position. The vertex format
 * carries no normals, so this is reconstructed per fragment; it is a true per-face
 * (flat) normal. Returns zero for degenerate slivers.
 */
vec3 faceNormal()
{
  vec3 n = cross(dFdx(fWorldPos), dFdy(fWorldPos));
  float len = length(n);
  return len < 1e-6 ? vec3(0.0) : n / len;
}

/*
 * Mirrors the sky's cloudFbm - domain warp plus per-octave drift - so the shadows on the
 * ground evolve the same way the deck casting them does.
 */
float gFbm(vec2 p, float t)
{
  vec2 warp = vec2(
    gNoise(p * 0.35 + vec2(t * 0.0006, 0.0)),
    gNoise(p * 0.35 + vec2(17.3, -t * 0.0004))
  ) - 0.5;
  p += warp * 1.6;

  float total = 0.0;
  float amp = 0.5;
  for (int i = 0; i < 4; ++i)
  {
    float fi = float(i);
    vec2 drift = vec2(t * (0.0007 + fi * 0.0004), t * (-0.0005 + fi * 0.0003));
    total += gNoise(p + drift) * amp;
    p *= 2.03;
    amp *= 0.5;
  }
  return total;
}

/*
 * Cloud shadows drifting across the world.
 *
 * Uses the same kind of fbm field as the sky clouds and drifts at the same rate, so the
 * dapple on the ground belongs to the deck overhead rather than looking like a separate
 * effect. Applied to everything, not just flat ground - a cloud shadow falls across walls
 * and trees too.
 */
vec3 applyCloudShadow(vec3 c)
{
  // Same drift rates as the sky deck, on the same clock, so the shadows travel and
  // reshape with the clouds overhead rather than on their own schedule.
  vec2 uv = fWorldPos.xz * 0.0011
    + vec2(cloudShadowTime * 0.0020, cloudShadowTime * 0.0010);
  float n = gFbm(uv * 1.4, cloudShadowTime);

  // Broad soft patches: most of the ground is lit, with shadow pooling under the thicker
  // parts of the deck.
  float shade = smoothstep(0.42, 0.72, n);

  return c * (1.0 - shade * cloudShadow);
}

/*
 * Ground mist that pools in low terrain.
 *
 * World Y is negative-up, so a *larger* y is lower ground. Everything below heightFogTop
 * accumulates mist, reaching full density heightFogDepth further down - which means dips
 * and valley floors fill while raised ground stays clear.
 *
 * The level is anchored relative to the camera rather than to absolute world height,
 * because absolute ground height varies enormously between regions and any fixed value
 * would drown some areas and miss others entirely.
 */
vec3 applyHeightFog(vec3 c)
{
  float below = fWorldPos.y - heightFogTop;
  if (below <= 0.0)
  {
    return c;
  }

  float depth = clamp(below / max(heightFogDepth, 1.0), 0.0, 1.0);

  // Drifting patchiness, on the same slow scale the cloud shadows use - uniform mist
  // reads as a flat wash rather than something lying on the ground.
  float drift = gNoise(fWorldPos.xz * 0.0016 + vec2(cloudShadowTime * 0.0009, 0.0)) * 0.65
    + gNoise(fWorldPos.xz * 0.0047 - vec2(0.0, cloudShadowTime * 0.0006)) * 0.35;
  float patch = 0.55 + 0.45 * drift;

  // Mist builds up over distance too, so nearby ground stays readable.
  float dist = length(fWorldPos.xz - cameraPos.xz);
  float far = smoothstep(200.0, 2600.0, dist);

  float amount = clamp(depth * patch * (0.25 + 0.75 * far) * heightFog, 0.0, 0.92);
  return mix(c, fogColor.rgb, amount);
}

/*
 * Snow settling on upward-facing surfaces, broken up by noise so it looks drifted rather
 * than painted on. World Y is negative-up, hence -n.y for "faces the sky".
 */
vec3 applySnowCover(vec3 c, vec3 n)
{
  float up = clamp(-n.y, 0.0, 1.0);
  // Steep faces shed snow; the power sharpens the cutoff between flat and sloped.
  float flat_ = pow(up, 3.0);
  if (flat_ < 0.01)
  {
    return c;
  }

  /*
   * Drift noise runs at a few tiles per cycle, not a few per tile. World units are ~128
   * per tile, so anything much above 0.005 varies within a single tile and reads as
   * dirty speckle rather than snow.
   *
   * Snow also lies as a near-continuous blanket with thinner patches, so this is mostly
   * uniform coverage with the noise only taking a bite out of it - not noise deciding
   * where snow exists at all.
   */
  float drift = gNoise(fWorldPos.xz * 0.0018) * 0.65 + gNoise(fWorldPos.xz * 0.0055) * 0.35;
  float patch = 0.72 + 0.28 * smoothstep(0.30, 0.70, drift);
  float cover = clamp(patch * flat_ * groundSnow, 0.0, 1.0);

  // Slightly blue-shadowed white rather than pure white, which reads as flat paint.
  vec3 snow = vec3(0.94, 0.96, 1.0);
  return mix(c, snow, cover);
}

/*
 * Wet ground: darkens flat surfaces, then pools brighter reflective puddles in the low
 * patches of a noise field, with a sun glint off them.
 */
vec3 applyWetGround(vec3 c, vec3 n)
{
  float up = clamp(-n.y, 0.0, 1.0);
  float flat_ = pow(up, 4.0);
  if (flat_ < 0.01)
  {
    return c;
  }

  // Wet surfaces are darker and slightly less saturated.
  vec3 wet = mix(c * 0.66, c, 0.25);
  c = mix(c, wet, flat_ * groundWet * 0.85);

  // Same scale reasoning as the snow drift above - puddles pool over several tiles.
  float pool = gNoise(fWorldPos.xz * 0.0022) * 0.65 + gNoise(fWorldPos.xz * 0.0065) * 0.35;
  float puddle = smoothstep(0.60, 0.76, pool) * flat_ * groundWet;
  if (puddle < 0.001)
  {
    return c;
  }

  vec3 v = normalize(cameraPos - fWorldPos);
  float fresnel = 0.04 + 0.96 * pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 5.0);
  vec3 surface = mix(c * 0.55, fogColor.rgb, clamp(fresnel, 0.0, 0.75));

  vec3 h = normalize(normalize(lightSunDir) + v);
  surface += vec3(1.0, 0.98, 0.92) * pow(clamp(dot(n, h), 0.0, 1.0), 48.0) * 0.4;

  return mix(c, surface, clamp(puddle, 0.0, 1.0));
}

/*
 * Point lights from fires and torches.
 *
 * Inverse-square falloff cut off at the light's radius, so a light cannot reach further
 * than its own range - without the cutoff every light contributes something everywhere,
 * which washes the whole scene out as more are added.
 *
 * Surfaces facing the light get more of it, but the term is biased upward rather than
 * clamped at zero: the reconstructed normals are per-face, and a hard cosine on faceted
 * geometry makes flat ground under a fire look blotchy.
 */
vec3 applyPointLights(vec3 c, vec3 n)
{
  if (lightCount <= 0)
  {
    return c;
  }

  vec3 accum = vec3(0.0);

  for (int i = 0; i < lightCount && i < MAX_LIGHTS; ++i)
  {
    vec3 delta = lightPos[i] - fWorldPos;
    float dist = length(delta);
    float radius = max(lightRadius[i], 1.0);
    if (dist >= radius)
    {
      continue;
    }

    float atten = 1.0 - dist / radius;
    atten *= atten;

    float facing = 1.0;
    if (n != vec3(0.0))
    {
      facing = 0.45 + 0.55 * max(dot(n, delta / max(dist, 0.001)), 0.0);
    }

    accum += lightColor[i] * atten * facing;
  }

  // Flicker applies to the accumulated contribution, so nearby lights pulse together
  // rather than each having its own visible rhythm.
  accum *= lightFlicker;

  return c * (1.0 + accum);
}

vec3 applyLighting(vec3 c, vec3 n)
{
  // Degenerate on slivers and perfectly edge-on faces; leave those unlit.
  if (lightStrength < 0.001 || n == vec3(0.0))
  {
    return c;
  }

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

  return clamp(c, 0.0, 1.0);
}

#include "tonemap.glsl"
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

  if (fTextureId > 0) {
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

  // Reconstructed once and shared by lighting and the ground weather below.
  vec3 n = faceNormal();

  if (n != vec3(0.0)) {
    if (groundSnow > 0.001) {
      shaded = applySnowCover(shaded, n);
    }
    if (groundWet > 0.001) {
      shaded = applyWetGround(shaded, n);
    }
  }

  shaded = applyLighting(shaded, n);
  shaded = applyPointLights(shaded, n);

  if (cloudShadow > 0.001) {
    shaded = applyCloudShadow(shaded);
  }

  // Before distance fog, so mist reads as lying on the ground rather than sitting on
  // top of the haze.
  if (heightFog > 0.001) {
    shaded = applyHeightFog(shaded);
  }

  /*
   * Aerial perspective: everything picks up the sky's colour with distance, because
   * that is what looking through air does. Distinct from the fog slider, which only
   * kicks in near the scene edge - this builds gradually across the whole view, which
   * is what actually reads as depth.
   *
   * Applied to every surface regardless of texture, so it works everywhere in a world
   * that is mostly untextured flat-shaded geometry.
   */
  if (aerial > 0.001) {
    float dist = length(fWorldPos - cameraPos);
    // Squared falloff so nearby geometry stays clean and the effect gathers with range.
    float t = clamp(dist / 8000.0, 0.0, 1.0);
    // Suppressed underground - there is no sky to pick colour up from in a cave.
    shaded = mix(shaded, fogColor.rgb, t * t * aerial * (1.0 - underground));
  }

  /*
   * Underground: darker and cooler, since the only light is whatever is carried or lit
   * rather than daylight. Desaturated too - colour perception falls away in low light.
   */
  if (underground > 0.001) {
    float grey = dot(shaded, vec3(0.2126, 0.7152, 0.0722));
    vec3 cave = mix(shaded, vec3(grey), 0.35) * vec3(0.62, 0.66, 0.78);
    shaded = mix(shaded, cave, underground);
  }

  // Shadowed and lit before fog, so fogged distance blends toward the sky colour rather
  // than having those terms applied on top of it.
  vec3 mixedColor = mix(shaded, fogColor.rgb, fFogAmount);

  /*
   * Tone mapped before grading, not after. The roll-off is what brings out-of-range values
   * back into 0..1; grading afterwards then works on a picture that has no clipped areas in
   * it, rather than trying to pull contrast out of a region that is already flat white.
   */
  FragColor = vec4(applyGrade(applyToneMap(mixedColor, toneMap)), c.a);

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
