/*
 * gpu-v2 scene enhancements, injected into the GPU plugin's scene fragment shader.
 *
 * Every name is prefixed gv2_ so nothing here can collide with the renderer's own
 * identifiers or with another extension's.
 *
 * gv2_enabled gates the whole thing and, like every uniform, defaults to zero - so the
 * scene is drawn exactly as the renderer would draw it until values have been pushed, and
 * whenever the effects are switched off.
 */
uniform float gv2_enabled;

// Sky colour the distance fog, ground mist and aerial haze all fade into.
uniform vec3 gv2_fogColor;

// Colour grading. Neutral at 1.0 / 0.0.
uniform float gv2_gradeGamma;
uniform float gv2_gradeContrast;
uniform float gv2_gradeSaturation;
uniform float gv2_gradeTemperature;
// Highlight roll-off, 0 off.
uniform float gv2_toneMap;
// Lightning flash brightening the world, 0..1.
uniform float gv2_lightningFlash;

// Ground weather. Both 0 leaves surfaces untouched.
uniform float gv2_groundSnow;
uniform float gv2_groundWet;

// Cloud shadows. 0 disables.
uniform float gv2_cloudShadow;
uniform float gv2_cloudShadowTime;

// Aerial perspective. 0 disables.
uniform float gv2_aerial;

// How far underground the player is, 0 open sky to 1 fully enclosed.
uniform float gv2_underground;

// Point lights from fires, torches and lanterns. Count 0 disables.
#define GV2_MAX_LIGHTS 64
uniform int gv2_lightCount;
uniform vec3 gv2_lightPos[GV2_MAX_LIGHTS];
uniform vec3 gv2_lightColor[GV2_MAX_LIGHTS];
uniform float gv2_lightRadius[GV2_MAX_LIGHTS];
uniform float gv2_lightFlicker;

// Ground mist. 0 disables.
uniform float gv2_heightFog;       // overall density
uniform float gv2_heightFogEye;    // how far above the camera the mist tops out
uniform float gv2_heightFogDepth;  // how far below that it takes to reach full density

// Ambient + directional lighting. Strength 0 makes gv2_applyLighting a no-op.
uniform float gv2_lightStrength;
uniform vec3 gv2_lightAmbient;
uniform vec3 gv2_lightSunColor;
uniform vec3 gv2_lightSunDir;
// Moonlight. Colour zero by day, under cloud and at new moon.
uniform vec3 gv2_lightMoonColor;
uniform vec3 gv2_lightMoonDir;

// Handed over by the vertex stage, which is the only one that has them.
in vec3 gv2_worldPos;
flat in vec3 gv2_cameraPos;
flat in float gv2_dynamic;

float gv2_gHash12(vec2 p)
{
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float gv2_gNoise(vec2 p)
{
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(gv2_gHash12(i), gv2_gHash12(i + vec2(1.0, 0.0)), u.x),
             mix(gv2_gHash12(i + vec2(0.0, 1.0)), gv2_gHash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

/*
 * Face normal from the screen-space derivatives of world position. The vertex format
 * carries no normals, so this is reconstructed per fragment; it is a true per-face
 * (flat) normal. Returns zero for degenerate slivers.
 */
vec3 gv2_faceNormal()
{
  vec3 n = cross(dFdx(gv2_worldPos), dFdy(gv2_worldPos));
  float len = length(n);
  return len < 1e-6 ? vec3(0.0) : n / len;
}

/*
 * Mirrors the sky's cloudFbm - domain warp plus per-octave drift - so the shadows on the
 * ground evolve the same way the deck casting them does.
 */
float gv2_gFbm(vec2 p, float t)
{
  vec2 warp = vec2(
    gv2_gNoise(p * 0.35 + vec2(t * 0.0006, 0.0)),
    gv2_gNoise(p * 0.35 + vec2(17.3, -t * 0.0004))
  ) - 0.5;
  p += warp * 1.6;

  float total = 0.0;
  float amp = 0.5;
  for (int i = 0; i < 4; ++i)
  {
    float fi = float(i);
    vec2 drift = vec2(t * (0.0007 + fi * 0.0004), t * (-0.0005 + fi * 0.0003));
    total += gv2_gNoise(p + drift) * amp;
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
vec3 gv2_applyCloudShadow(vec3 c)
{
  // Same drift rates as the sky deck, on the same clock, so the shadows travel and
  // reshape with the clouds overhead rather than on their own schedule.
  vec2 uv = gv2_worldPos.xz * 0.0011
    + vec2(gv2_cloudShadowTime * 0.0020, gv2_cloudShadowTime * 0.0010);
  float n = gv2_gFbm(uv * 1.4, gv2_cloudShadowTime);

  // Broad soft patches: most of the ground is lit, with shadow pooling under the thicker
  // parts of the deck.
  float shade = smoothstep(0.42, 0.72, n);

  return c * (1.0 - shade * gv2_cloudShadow);
}

/*
 * Ground mist that pools in low terrain.
 *
 * World Y is negative-up, so a *larger* y is lower ground. Everything below (gv2_cameraPos.y - gv2_heightFogEye)
 * accumulates mist, reaching full density gv2_heightFogDepth further down - which means dips
 * and valley floors fill while raised ground stays clear.
 *
 * The level is anchored relative to the camera rather than to absolute world height,
 * because absolute ground height varies enormously between regions and any fixed value
 * would drown some areas and miss others entirely.
 */
vec3 gv2_applyHeightFog(vec3 c)
{
  float below = gv2_worldPos.y - (gv2_cameraPos.y - gv2_heightFogEye);
  if (below <= 0.0)
  {
    return c;
  }

  float depth = clamp(below / max(gv2_heightFogDepth, 1.0), 0.0, 1.0);

  // Drifting patchiness, on the same slow scale the cloud shadows use - uniform mist
  // reads as a flat wash rather than something lying on the ground.
  float drift = gv2_gNoise(gv2_worldPos.xz * 0.0016 + vec2(gv2_cloudShadowTime * 0.0009, 0.0)) * 0.65
    + gv2_gNoise(gv2_worldPos.xz * 0.0047 - vec2(0.0, gv2_cloudShadowTime * 0.0006)) * 0.35;
  float patch = 0.55 + 0.45 * drift;

  // Mist builds up over distance too, so nearby ground stays readable.
  float dist = length(gv2_worldPos.xz - gv2_cameraPos.xz);
  float far = smoothstep(200.0, 2600.0, dist);

  float amount = clamp(depth * patch * (0.25 + 0.75 * far) * gv2_heightFog, 0.0, 0.92);
  return mix(c, gv2_fogColor, amount);
}

/*
 * Snow settling on upward-facing surfaces, broken up by noise so it looks drifted rather
 * than painted on. World Y is negative-up, hence -n.y for "faces the sky".
 */
vec3 gv2_applySnowCover(vec3 c, vec3 n)
{
  float up = clamp(-n.y, 0.0, 1.0);
  /*
   * Steep faces shed snow; anything gentler than about forty-five degrees holds all of it.
   *
   * The normal here is per triangle, and terrain is a mesh of triangles each tilted a
   * little differently. A cutoff that keeps falling across those small tilts gives every
   * triangle its own amount of snow, and the ground comes out as a patchwork of facets.
   * Saturating early means rolling ground is evenly covered and only real slopes thin out.
   */
  float flat_ = smoothstep(0.30, 0.70, up);
  if (flat_ < 0.01)
  {
    return c;
  }

  /*
   * Drift noise runs at a few tiles per cycle, not a few per tile. World units are ~128
   * per tile, so anything much above 0.005 varies within a single tile and reads as
   * dirty speckle rather than snow.
   *
   * The thin patches let roughly half the ground through. With the slope no longer
   * varying the cover from one triangle to the next, this is the only thing left to break
   * it up, and a blanket that never thins turns the whole scene one flat white.
   */
  float drift = gv2_gNoise(gv2_worldPos.xz * 0.0018) * 0.65 + gv2_gNoise(gv2_worldPos.xz * 0.0055) * 0.35;
  float patch = 0.52 + 0.48 * smoothstep(0.28, 0.72, drift);
  float cover = clamp(patch * flat_ * gv2_groundSnow, 0.0, 1.0);

  /*
   * Slightly blue-shadowed white rather than pure white, and carrying the brightness of
   * what it lies on. The game's own shading and its textures are in that brightness, so
   * hills keep their relief and paths, bricks and roof tiles stay readable under the snow
   * instead of everything flattening to one value.
   */
  float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
  vec3 snow = vec3(0.94, 0.96, 1.0) * (0.68 + 0.32 * smoothstep(0.04, 0.42, luma));
  return mix(c, snow, cover);
}

/*
 * Wet ground: darkens flat surfaces, then pools brighter reflective puddles in the low
 * patches of a noise field, with a sun glint off them.
 */
vec3 gv2_applyWetGround(vec3 c, vec3 n)
{
  float up = clamp(-n.y, 0.0, 1.0);
  // Saturates early for the same reason the snow does: see gv2_applySnowCover.
  float flat_ = smoothstep(0.35, 0.75, up);
  if (flat_ < 0.01)
  {
    return c;
  }

  // Wet surfaces are darker and slightly less saturated.
  vec3 wet = mix(c * 0.66, c, 0.25);
  c = mix(c, wet, flat_ * gv2_groundWet * 0.85);

  /*
   * Puddles favour level ground, but as a gradual preference and not a cutoff. The
   * normal is one value per triangle, so anything sharp here draws the triangles: a pool
   * would stop dead along the edge where one tilts a little more than its neighbour.
   * A long gentle ramp makes that step small enough not to show.
   */
  float level = clamp((up - 0.70) / 0.30, 0.0, 1.0);

  // Small pools, a tile or two across, with a wide soft rim. World units are ~128 per
  // tile.
  float pool = gv2_gNoise(gv2_worldPos.xz * 0.0046) * 0.60 + gv2_gNoise(gv2_worldPos.xz * 0.0115) * 0.40;
  float puddle = smoothstep(0.62, 0.86, pool) * level * gv2_groundWet;
  if (puddle < 0.001)
  {
    return c;
  }

  /*
   * Shallow water over the ground: the ground a little darker, with the sky lying on it.
   *
   * The reflection has a floor. Fresnel alone gives almost none looking straight down,
   * and the game's camera mostly looks down - so a puddle was nothing but its darkening,
   * and read as a stain on the ground rather than as water.
   */
  vec3 v = normalize(gv2_cameraPos - gv2_worldPos);
  float fresnel = 0.04 + 0.96 * pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 5.0);
  vec3 surface = mix(c * 0.80, gv2_fogColor, clamp(0.30 + fresnel, 0.0, 0.80));

  vec3 h = normalize(normalize(gv2_lightSunDir) + v);
  surface += vec3(1.0, 0.98, 0.92) * pow(clamp(dot(n, h), 0.0, 1.0), 48.0) * 0.4;

  return mix(c, surface, clamp(puddle, 0.0, 0.70));
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
vec3 gv2_applyPointLights(vec3 c, vec3 n)
{
  if (gv2_lightCount <= 0)
  {
    return c;
  }

  vec3 accum = vec3(0.0);

  for (int i = 0; i < gv2_lightCount && i < GV2_MAX_LIGHTS; ++i)
  {
    vec3 delta = gv2_lightPos[i] - gv2_worldPos;
    float dist = length(delta);
    float radius = max(gv2_lightRadius[i], 1.0);
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

    accum += gv2_lightColor[i] * atten * facing;
  }

  // Flicker applies to the accumulated contribution, so nearby lights pulse together
  // rather than each having its own visible rhythm.
  accum *= gv2_lightFlicker;

  return c * (1.0 + accum);
}

vec3 gv2_applyLighting(vec3 c, vec3 n)
{
  // Degenerate on slivers and perfectly edge-on faces; leave those unlit.
  if (gv2_lightStrength < 0.001 || n == vec3(0.0))
  {
    return c;
  }

  float diffuse = max(dot(n, normalize(gv2_lightSunDir)), 0.0);
  vec3 light = gv2_lightAmbient + gv2_lightSunColor * diffuse;

  // Skipped outright when there is no moonlight: the direction is not meaningful then,
  // and normalising it would not be either.
  if (gv2_lightMoonColor != vec3(0.0))
  {
    light += gv2_lightMoonColor * max(dot(n, normalize(gv2_lightMoonDir)), 0.0);
  }

  return c * mix(vec3(1.0), light, gv2_lightStrength);
}

vec3 gv2_applyGrade(vec3 c)
{
  c *= vec3(1.0 + gv2_gradeTemperature * 0.20, 1.0, 1.0 - gv2_gradeTemperature * 0.20);
  c = pow(max(c, 0.0), vec3(1.0 / max(gv2_gradeGamma, 0.01)));
  c = (c - 0.5) * gv2_gradeContrast + 0.5;

  float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(luma), c, gv2_gradeSaturation);

  return clamp(c, 0.0, 1.0);
}

vec3 gv2_acesFilmic(vec3 c)
{
	const float a = 2.51;
	const float b = 0.03;
	const float d = 2.43;
	const float e = 0.59;
	const float f = 0.14;

	return clamp((c * (a * c + b)) / (c * (d * c + e) + f), 0.0, 1.0);
}

vec3 gv2_applyToneMap(vec3 c, float amount)
{
	if (amount < 0.001)
	{
		return c;
	}

	return mix(c, gv2_acesFilmic(c), amount);
}
