// gpu-v2: world and camera position, forwarded to the fragment stage, and this plugin's
// own fog depth in place of the renderer's.
uniform float gv2_enabled;
// Tiles of fog in from the edge of the drawn world. 0 disables.
uniform float gv2_fogDepth;
out vec3 gv2_worldPos;
flat out vec3 gv2_cameraPos;
// 1 for players, NPCs and anything else animated; 0 for the static scenery in a zone.
flat out float gv2_dynamic;
