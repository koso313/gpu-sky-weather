# GPU Sky/Weather: porting gpu-v2 to the GPU extension API

Notes from rebuilding this plugin on Adam's `gpu-api` branch
(https://github.com/Adam-/runelite/tree/gpu-api) instead of on a fork of the renderer.

Tested against `6b5a9193f` ("add pbo callback"), built locally with
`./gradlew publishAllToMavenLocal` and consumed as `1.13.0-SNAPSHOT`.

## Result

Nearly all of the plugin now runs as an extension, with the core GPU plugin owning the
renderer:

- **Sky** - day/night gradient, sun, moon and phases, stars, shooting stars, aurora,
  clouds, lightning bolts. Drawn from `drawSkybox()` with its own program.
- **Scene** - ambient and sun lighting, point lights from fires and torches, fog colour
  and depth, ground mist, aerial perspective, cloud shadows, snow and wet ground,
  underground darkening, tone mapping, colour grading. Injected through the four
  `rlst_*` hooks and driven by uniforms set on the program from `onProgramCreate`.
- **Weather particles, bloom, god rays, FXAA, sharpening, vignette** - drawn from
  `onPostDrawToplevel()` into the scene framebuffer that is still bound there.

Code: `EnhancementExtension`, `PostEffects`, `GpuV2ExtensionPlugin`, and the
`ext_*.glsl` shader fragments.

## How the uniforms get set

`onProgramCreate(int program)` hands over the scene program; uniform locations are looked
up there, again after every recompile. Values are pushed each frame from `drawSkybox()`,
which runs with the scene program bound just before the scene is drawn. In areas with a
skybox model that call does not happen, so they are pushed from `onPostDrawToplevel()`
instead and take effect a frame later.

Everything injected is prefixed `gv2_` and gated on one uniform that defaults to zero, so
the renderer's output is untouched until values have been pushed.

## Problems in the branch

**Registering an extension with no GL context crashes the JVM.** `registerExtension` calls
`recompileShaders()`, which calls `shutdownProgram()` -> `glDeleteProgram`. With the GPU
plugin switched off there is no context and the process dies with an access violation:

```
EXCEPTION_ACCESS_VIOLATION
j  org.lwjgl.opengl.GL20C.glDeleteProgram(I)V+0
j  net.runelite.client.plugins.gpu.GpuPlugin.shutdownProgram()V+3
j  net.runelite.client.plugins.gpu.GpuPlugin.lambda$recompileShaders$6()V+12
```

Seen at client startup with an extension plugin enabled and the GPU plugin disabled.
Worked around here by waiting until `client.getDrawCallbacks()` is the GPU plugin before
registering. `unregisterExtension` takes the same path and is avoided the same way.

**`drawSkybox` is skipped where the scene has a skybox model**, so an extension cannot
replace the sky there, and has no pre-scene callback at all in those areas. Read from the
code; not hit in testing.

**`registerExtension` does not call `onContextCreate`** for an extension registered while
the plugin is running. It does now get `onProgramCreate`, so building programs there as
well covers it.

## Things an extension author needs to know

- `@PluginDependency(GpuPlugin.class)` is required to get `GpuApi` injected.
- GL state is the extension's to save and restore. Program, VAO, blend, depth, cull,
  viewport, texture and framebuffer bindings all matter; the renderer carries straight on
  with whatever is left bound.
- Static scenery is drawn with its zone offset in `base`; players, NPCs and other dynamic
  models are drawn with `base` at zero. That is the only way found to tell them apart in
  the shader (used here to keep snow off characters).
- `client.getCameraFpYaw()` / `getCameraFpPitch()` are in radians and match what the
  renderer uses. The integer accessors are in a different unit.

## What still does not port

- **Smooth lighting** - needs per-vertex normals, and `Model.getVertexNormalsX/Y/Z()` is
  null for scene models. No way to attach per-vertex data.
- **Hiding trees and ground clutter** - needs a hook at scene upload.
- **Draw distance, anti-aliasing, frame rate, threads, UI scaling** - the core plugin's
  own settings now.

## To run it

Build Adam's branch to the local Maven repository, keep `runeLiteVersion` in
`build.gradle` at `1.13.0-SNAPSHOT`, then `./gradlew run`. Enable the stock **GPU** plugin
and **GPU Sky/Weather (extension)**. `master` is the fork and builds against the released client.
