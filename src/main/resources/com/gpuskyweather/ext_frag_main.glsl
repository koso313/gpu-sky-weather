  // GPU Sky/Weather: the renderer has already written FragColor. This redoes the composition from
  // the unfogged colour, so lighting and weather land under the fog rather than on top.
  if (gsw_enabled > 0.5) {
    vec3 shaded = c.rgb;

    // Reconstructed once and shared by lighting and the ground weather below.
    vec3 n = gsw_faceNormal();

    // Snow and rain settle on the ground and on scenery, not on people: a character does
    // not stand still long enough to collect either, and a white cap on every head and
    // shoulder reads as a fault.
    if (n != vec3(0.0) && gsw_dynamic < 0.5) {
      if (gsw_groundSnow > 0.001) {
        shaded = gsw_applySnowCover(shaded, n);
      }
      if (gsw_groundWet > 0.001) {
        shaded = gsw_applyWetGround(shaded, n);
      }
    }

    shaded = gsw_applyLighting(shaded, n);
    shaded = gsw_applyPointLights(shaded, n);

    /*
     * Lightning lights the world, not just the screen.
     *
     * The flash was drawn as a wash over the finished frame, so the sky lit up while the
     * ground it was supposedly illuminating stayed exactly as dark - which reads as a screen
     * effect rather than as something happening in the world.
     *
     * Applied outside gsw_applyLighting rather than folded into its ambient term, because that
     * term is scaled by the lighting strength setting - at a low setting a strike would
     * barely register, and a lightning strike should be visible whatever the ambient
     * lighting is set to. Cool-tinted, since the light is blue-white rather than neutral.
     */
    if (gsw_lightningFlash > 0.001)
    {
      shaded += shaded * vec3(0.55, 0.65, 0.95) * gsw_lightningFlash;
    }

    if (gsw_cloudShadow > 0.001) {
      shaded = gsw_applyCloudShadow(shaded);
    }

    // Before distance fog, so mist reads as lying on the ground rather than sitting on
    // top of the haze.
    if (gsw_heightFog > 0.001) {
      shaded = gsw_applyHeightFog(shaded);
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
    if (gsw_aerial > 0.001) {
      float dist = length(gsw_worldPos - gsw_cameraPos);
      // Squared falloff so nearby geometry stays clean and the effect gathers with range.
      float t = clamp(dist / 8000.0, 0.0, 1.0);
      // Suppressed gsw_underground - there is no sky to pick colour up from in a cave.
      shaded = mix(shaded, gsw_fogColor, t * t * gsw_aerial * (1.0 - gsw_underground));
    }

    /*
     * Underground: darker and cooler, since the only light is whatever is carried or lit
     * rather than daylight. Desaturated too - colour perception falls away in low light.
     */
    if (gsw_underground > 0.001) {
      float grey = dot(shaded, vec3(0.2126, 0.7152, 0.0722));
      vec3 cave = mix(shaded, vec3(grey), 0.35) * vec3(0.62, 0.66, 0.78);
      shaded = mix(shaded, cave, gsw_underground);
    }

    // Shadowed and lit before fog, so fogged distance blends toward the sky colour rather
    // than having those terms applied on top of it.
    vec3 mixedColor = mix(shaded, gsw_fogColor, fFogAmount);

    /*
     * Tone mapped before grading, not after. The roll-off is what brings out-of-range values
     * back into 0..1; grading afterwards then works on a picture that has no clipped areas in
     * it, rather than trying to pull contrast out of a region that is already flat white.
     */
    FragColor = vec4(gsw_applyGrade(gsw_applyToneMap(mixedColor, gsw_toneMap)), c.a);
  }
