  // gpu-v2: the renderer has already written FragColor. This redoes the composition from
  // the unfogged colour, so lighting and weather land under the fog rather than on top.
  if (gv2_enabled > 0.5) {
    vec3 shaded = c.rgb;

    // Reconstructed once and shared by lighting and the ground weather below.
    vec3 n = gv2_faceNormal();

    // Snow and rain settle on the ground and on scenery, not on people: a character does
    // not stand still long enough to collect either, and a white cap on every head and
    // shoulder reads as a fault.
    if (n != vec3(0.0) && gv2_dynamic < 0.5) {
      if (gv2_groundSnow > 0.001) {
        shaded = gv2_applySnowCover(shaded, n);
      }
      if (gv2_groundWet > 0.001) {
        shaded = gv2_applyWetGround(shaded, n);
      }
    }

    shaded = gv2_applyLighting(shaded, n);
    shaded = gv2_applyPointLights(shaded, n);

    /*
     * Lightning lights the world, not just the screen.
     *
     * The flash was drawn as a wash over the finished frame, so the sky lit up while the
     * ground it was supposedly illuminating stayed exactly as dark - which reads as a screen
     * effect rather than as something happening in the world.
     *
     * Applied outside gv2_applyLighting rather than folded into its ambient term, because that
     * term is scaled by the lighting strength setting - at a low setting a strike would
     * barely register, and a lightning strike should be visible whatever the ambient
     * lighting is set to. Cool-tinted, since the light is blue-white rather than neutral.
     */
    if (gv2_lightningFlash > 0.001)
    {
      shaded += shaded * vec3(0.55, 0.65, 0.95) * gv2_lightningFlash;
    }

    if (gv2_cloudShadow > 0.001) {
      shaded = gv2_applyCloudShadow(shaded);
    }

    // Before distance fog, so mist reads as lying on the ground rather than sitting on
    // top of the haze.
    if (gv2_heightFog > 0.001) {
      shaded = gv2_applyHeightFog(shaded);
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
    if (gv2_aerial > 0.001) {
      float dist = length(gv2_worldPos - gv2_cameraPos);
      // Squared falloff so nearby geometry stays clean and the effect gathers with range.
      float t = clamp(dist / 8000.0, 0.0, 1.0);
      // Suppressed gv2_underground - there is no sky to pick colour up from in a cave.
      shaded = mix(shaded, gv2_fogColor, t * t * gv2_aerial * (1.0 - gv2_underground));
    }

    /*
     * Underground: darker and cooler, since the only light is whatever is carried or lit
     * rather than daylight. Desaturated too - colour perception falls away in low light.
     */
    if (gv2_underground > 0.001) {
      float grey = dot(shaded, vec3(0.2126, 0.7152, 0.0722));
      vec3 cave = mix(shaded, vec3(grey), 0.35) * vec3(0.62, 0.66, 0.78);
      shaded = mix(shaded, cave, gv2_underground);
    }

    // Shadowed and lit before fog, so fogged distance blends toward the sky colour rather
    // than having those terms applied on top of it.
    vec3 mixedColor = mix(shaded, gv2_fogColor, fFogAmount);

    /*
     * Tone mapped before grading, not after. The roll-off is what brings out-of-range values
     * back into 0..1; grading afterwards then works on a picture that has no clipped areas in
     * it, rather than trying to pull contrast out of a region that is already flat white.
     */
    FragColor = vec4(gv2_applyGrade(gv2_applyToneMap(mixedColor, gv2_toneMap)), c.a);
  }
