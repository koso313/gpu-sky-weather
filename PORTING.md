# Porting gpu-v2 to the GPU extension API

Notes from rebuilding this plugin's sky on Adam's `gpu-api` branch
(https://github.com/Adam-/runelite/tree/gpu-api) instead of forking the renderer.

Tested against `ff01a5857` ("Revert 'add a scope for each extension'"), built locally with
`./gradlew publishAllToMavenLocal` and consumed as `1.12.34-SNAPSHOT`.

## Result: it works

The gpu-v2 sky renders through the extension API with no renderer fork - horizon
gradient, zenith, stars, cloud bands, sun and moon - with the world drawing over it
correctly and the stock GPU plugin owning the scene. 302 fps, zero GL errors.

The sky was a good candidate because it already owned its program and uniforms, and its
vertex shader builds a fullscreen triangle from `gl_VertexID`, so it needs no vertex
buffer and never touches the scene shader. `SkyExtension.java` is the port.

## What the API is

- `GpuApi` - `registerExtension(Plugin, GpuExtension)` / `unregisterExtension(...)`
- `GpuExtension` - `onContextCreate()`, `onContextDestroy()`,
  `getShaderExtension(String hook)`, `drawSkybox()`, `onPostDrawToplevel()`
- Four shader injection sites: `rlst_vert_definitions`, `rlst_vert_main_post`,
  `rlst_frag_definitions`, `rlst_frag_main_post`
- A UBO carrying `worldProj`, `cameraPos`, `cameraYaw`, `cameraPitch`

## Things that cost time

### registerExtension does not call onContextCreate

An extension registered while the GPU plugin is already running never gets
`onContextCreate()`, so it never builds its program and silently draws nothing. It stays
inert until the context happens to be recreated - toggling the GPU plugin off and on.

Measured:

```
21:42:34  gpu-v2 sky extension registered        <- plugin enabled
21:42:45  context created, program 3             <- only after cycling the GPU plugin
```

`registerExtension` already calls `recompileShaders()`; calling `onContextCreate()` on
the new extension when a context exists would match it.

### Extensions must restore program and VAO themselves

`drawSkybox()` runs inside the renderer's own draw, and the scene is drawn immediately
after with whatever program and VAO are bound. Restoring the obvious-looking defaults -
program 0, VAO 0 - leaves the renderer drawing the world with no program bound: the sky
appears and nothing else does.

Saving `GL_CURRENT_PROGRAM` and `GL_VERTEX_ARRAY_BINDING` on entry and restoring them
fixes it. Worth either documenting on the interface or wrapping the call.

### @PluginDependency is required and not obvious

`GpuApi` is bound in `GpuPlugin.configure(Binder)`, so it is only in scope for plugins
that declare `@PluginDependency(GpuPlugin.class)`. Without it, instantiation fails with
"No implementation for GpuApi was bound", which does not point at the cause.

## What does not port

**Per-frame uniforms on the scene program.** The largest gap. `rlst_frag_definitions` can
declare uniforms, but nothing can set them before the scene draws - `onContextCreate` runs
once, `onPostDrawToplevel` runs after. Everything time-varying here needs them: fog colour
and depth, sun direction, ambient and sun colour, weather gloom, lightning flash.

A possible workaround, untested: grab `GL_CURRENT_PROGRAM` during `onPostDrawToplevel` and
set uniforms for the *next* frame, since uniform values persist in the program object. One
frame of latency, and fragile.

**Per-vertex data.** `Model.getVertexNormalsX/Y/Z()` is null for every scene model measured
here (0 with, 14061 without), so smooth lighting means computing normals at upload and
oct-encoding them into a spare short in the vertex attribute. With the vertex format owned
by the renderer there is no way to attach that.

**Scene upload filtering.** Hiding trees and ground clutter happens at upload, cheaper than
hiding it downstream. No hook. Upload runs on `[Map Loader]`, not the render thread.

**Post-processing** would need the scene colour texture, which is not handed over.
`glCopyTexImage2D` from the bound framebuffer would probably serve.

## Two smaller things in the branch

`drawSkybox` only fires when the scene has no skybox model - the extension call sits inside
the `skybox == null` branch of `GpuPlugin.drawSkybox`, so in any area that defines one,
extensions are never asked and a plugin-drawn sky would vanish there. Not hit in testing.

`extensionDrawSkybox()`'s return value is discarded. `ExtensionManager` ORs it across
extensions, `GpuPlugin` ignores the result, and the `glClear` already happens before the
call. Nothing decides what happens when two extensions both return true.

## Verdict

The API fits this plugin. The sky - the biggest single piece - runs on it today, and losing
the fork would be a straight win. Getting the rest across needs a way to set uniforms per
frame; the vertex and upload hooks matter less and have plausible workarounds.
