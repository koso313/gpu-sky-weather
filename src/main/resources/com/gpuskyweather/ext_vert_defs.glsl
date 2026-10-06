// GPU Sky/Weather: world and camera position, forwarded to the fragment stage, and this plugin's
// own fog depth in place of the renderer's.
uniform float gsw_enabled;
// Tiles of fog in from the edge of the drawn world. 0 disables.
uniform float gsw_fogDepth;
out vec3 gsw_worldPos;
flat out vec3 gsw_cameraPos;
// 1 for players, NPCs and anything else animated; 0 for the static scenery in a zone.
flat out float gsw_dynamic;
