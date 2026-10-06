  gv2_worldPos = worldPos.xyz;
  gv2_cameraPos = cameraPos;
  if (gv2_enabled > 0.5) {
    // Same falloff the renderer uses, measured with this plugin's fog depth.
    fFogAmount = gv2_fogDepth > 0.0
      ? fogFactorLinear(fogDistance, 0.f, gv2_fogDepth * TILE_SIZE)
      : 0.0;
  }
  // Static scenery is drawn zone by zone with the zone's offset in base; everything that
  // moves is drawn in world coordinates with base at zero. The one zone whose own offset is
  // zero is told apart by its vertices lying inside a single zone's extent.
  gv2_dynamic = (base == ivec3(0)
    && (vertf.x < 0.0 || vertf.x > 1024.0 || vertf.z < 0.0 || vertf.z > 1024.0)) ? 1.0 : 0.0;
