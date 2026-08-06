# Porting gpu-v2 to the GPU extension API

Notes from trying to rebuild this plugin on Adam's `gpu-api` branch
(https://github.com/Adam-/runelite/tree/gpu-api) instead of forking the renderer.

Tested against `ff01a5857` ("Revert 'add a scope for each extension'"), built locally
with `./gradlew publishAllToMavenLocal` and consumed as `1.12.34-SNAPSHOT`.

## What the API is

- `GpuApi` — `registerExtension(Plugin, GpuExtension)` / `unregisterExtension(...)`
- `GpuExtension` — `onContextCreate()`, `onContextDestroy()`,
  `getShaderExtension(String hook)`, `drawSkybox()`, `onPostDrawToplevel()`
- Four shader injection sites: `rlst_vert_definitions`, `rlst_vert_main_post`,
  `rlst_frag_definitions`, `rlst_frag_main_post`
- A UBO carrying `worldProj`, `cameraPos`, `cameraYaw`, `cameraPitch`

## Blocker: GpuApi is not injectable from another plugin

This stops the port before anything else can be tested.

`GpuApi` is bound in `GpuPlugin.configure(Binder)`. RuneLite gives each plugin its own
child injector built from that plugin as a module, so the binding is only visible inside
the GPU plugin itself. A separate plugin asking for it fails to start:

```
net.runelite.client.plugins.PluginInstantiationException: com.google.inject.CreationException:
1) No implementation for net.runelite.client.plugins.gpu.api.GpuApi was bound.
  while locating net.runelite.client.plugins.gpu.api.GpuApi
    for field at com.gpuv2.ext.SkyExtensionPlugin.gpuApi
```

Reproduces with a plugin whose entire body is an `@Inject GpuApi` field and a
`registerExtension` call in `startUp()`. See `src/main/java/com/gpuv2/ext/`.

Everything below is therefore read from the code rather than observed running.

## What looks like it ports cleanly

**The sky.** This was the encouraging part. gpu-v2's sky pass already owns its own
program, its own uniforms and a vertex shader that builds a fullscreen triangle from
`gl_VertexID`, so it needs no vertex buffer and never touches the scene shader. It maps
onto `drawSkybox()` almost unchanged - `SkyExtension.java` is that port, and it compiles
against the branch.

**Post-processing**, in principle, via `onPostDrawToplevel()`. Not attempted: the
extension would need the scene colour texture, and the API hands over no handle for it.
`glCopyTexImage2D` from the bound framebuffer would probably work.

## What does not port

**Per-frame uniforms on the scene program.** The largest gap. `rlst_frag_definitions`
can declare uniforms, but nothing can set them before the scene draws - `onContextCreate`
runs once, `onPostDrawToplevel` runs after the fact. Everything time-varying in this
plugin needs them: fog colour and depth, sun direction, ambient and sun colour, weather
gloom, lightning flash.

There may be a workaround - grabbing `GL_CURRENT_PROGRAM` during `onPostDrawToplevel`
and setting uniforms for the *next* frame, since uniform values persist in the program
object. Untested, one frame of latency, and dependent on which program happens to be
bound.

**Per-vertex data.** `Model.getVertexNormalsX/Y/Z()` is null for every scene model
measured here (0 with, 14061 without), so smooth lighting means computing normals at
upload and oct-encoding them into a spare short in the vertex attribute. With the vertex
format owned by the renderer there is no way to attach that.

**Scene upload filtering.** Hiding trees and ground clutter happens at upload, which is
cheaper than hiding it downstream. No hook. Note upload runs on `[Map Loader]`.

## Two smaller things in the branch

`drawSkybox` only fires when the scene has no skybox model - the extension call sits
inside the `skybox == null` branch of `GpuPlugin.drawSkybox`, so in any area that defines
one, extensions are never asked and a plugin-drawn sky would vanish there.

`extensionDrawSkybox()`'s return value is discarded. `ExtensionManager` ORs it across
extensions, `GpuPlugin` ignores the result. The `glClear` already happens before the
call, so it is unclear what it would gate. Possibly unfinished. Related: nothing decides
what happens when two extensions both return true.

## Verdict

The shape of the API is right for this plugin. The sky - the single biggest piece - fits
it well, and losing the renderer fork would be a straight win. Getting the rest across
needs a way to set uniforms per frame; the vertex and upload hooks matter less and have
plausible workarounds.
