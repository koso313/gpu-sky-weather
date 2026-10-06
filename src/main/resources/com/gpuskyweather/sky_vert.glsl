#version 330

out vec2 fNdc;

/*
 * Fullscreen triangle generated from gl_VertexID - no vertex buffer or attributes
 * needed, so this can be drawn from an empty VAO.
 *
 * id 0 -> (0,0), id 1 -> (2,0), id 2 -> (0,2), which in NDC covers the whole screen.
 */
void main()
{
	vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
	fNdc = p * 2.0 - 1.0;
	gl_Position = vec4(fNdc, 0.0, 1.0);
}
