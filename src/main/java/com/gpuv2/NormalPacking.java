package com.gpuv2;

/**
 * Squeezes a unit normal into the one spare 16-bit slot of the vertex.
 *
 * <p>The vertex is 24 bytes and every field in it is spoken for except the fourth component
 * of the texture attribute. Growing the vertex to carry three floats would have been the
 * obvious route and a much larger change - {@code VERT_SIZE} feeds buffer sizing across the
 * whole renderer - so the normal goes in the slot that is already there.
 *
 * <p>Octahedral encoding, which is the standard way to fit a direction into two numbers.
 * The sphere is projected onto an octahedron and unfolded flat, giving a pair of values in
 * -1..1 with no singularity at the poles and a worst-case error under a degree at 8 bits
 * each. That is far finer than lighting on this geometry can show.
 *
 * <p>Zero is reserved to mean "no normal here", so geometry the client gives us nothing for
 * can fall back to the flat normal rather than being lit as though it faced sideways.
 */
final class NormalPacking
{
	/** Packed value meaning the vertex has no usable normal. */
	static final int NONE = 0;

	/*
	 * Counters, so whether normals are actually arriving can be read off the log rather than
	 * judged by eye from two screenshots. Comparing shaded images is exactly the kind of
	 * question people cannot answer reliably, and it had already cost several rounds.
	 */
	static final java.util.concurrent.atomic.AtomicLong MODELS_WITH_NORMALS =
		new java.util.concurrent.atomic.AtomicLong();
	static final java.util.concurrent.atomic.AtomicLong MODELS_WITHOUT_NORMALS =
		new java.util.concurrent.atomic.AtomicLong();

	/**
	 * How many of a model's vertices ended up with a normal distinct from its neighbours'.
	 *
	 * <p>The number that actually matters. OSRS models often do not share vertices between
	 * faces, and where that is so a "vertex normal" is just the face normal - smooth shading
	 * then produces the identical picture to flat, with no bug involved and nothing to fix.
	 */
	static final java.util.concurrent.atomic.AtomicLong DISTINCT_NORMALS =
		new java.util.concurrent.atomic.AtomicLong();

	static void reset()
	{
		MODELS_WITH_NORMALS.set(0);
		MODELS_WITHOUT_NORMALS.set(0);
		DISTINCT_NORMALS.set(0);
	}

	private NormalPacking()
	{
	}

	/**
	 * Packs a normal into 16 bits, as two signed bytes of octahedral coordinates.
	 *
	 * <p>The input need not be normalised; a zero-length vector packs to {@link #NONE}.
	 */
	static int pack(float x, float y, float z)
	{
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		if (len < 1e-6f)
		{
			return NONE;
		}

		x /= len;
		y /= len;
		z /= len;

		// Project onto the octahedron: scale so the three axes sum to one.
		float sum = Math.abs(x) + Math.abs(y) + Math.abs(z);
		float px = x / sum;
		float py = y / sum;

		// Fold the lower hemisphere out across the diagonals.
		if (z < 0)
		{
			float ox = px;
			px = (1f - Math.abs(py)) * Math.signum(ox == 0 ? 1 : ox);
			py = (1f - Math.abs(ox)) * Math.signum(py == 0 ? 1 : py);
		}

		int qx = quantise(px);
		int qy = quantise(py);
		int packed = ((qx & 0xFF) << 8) | (qy & 0xFF);

		// NONE has to stay unambiguous. The value it collides with is one representable
		// direction out of 65536, so nudging it costs nothing anyone could see.
		return packed == NONE ? 1 : packed;
	}

	/** Maps -1..1 onto a signed byte, rounding to nearest. */
	private static int quantise(float v)
	{
		v = Math.max(-1f, Math.min(1f, v));
		return Math.round(v * 127f);
	}
}
