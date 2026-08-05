#version 330

/*
 * Straight texture copy over the whole screen.
 *
 * Exists so the sky can be drawn into a smaller target and stretched back up. A blit
 * would be the obvious way to do that, but the scene framebuffer is multisampled and
 * glBlitFramebuffer refuses a single-sampled source into a multisampled destination - so
 * the copy has to go through a draw.
 *
 * The magnification filter does the actual upscaling; this only reads. Bilinear across a
 * smooth gradient is indistinguishable from having rendered it at full size, which is the
 * whole reason the sky is worth doing this to and geometry is not.
 */

uniform sampler2D src;

in vec2 fNdc;
out vec4 FragColor;

void main()
{
	FragColor = texture(src, fNdc * 0.5 + 0.5);
}
