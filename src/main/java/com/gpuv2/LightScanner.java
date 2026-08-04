package com.gpuv2;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Finds nearby objects that should cast light.
 *
 * <p>Which object ids count is configured rather than built in. The alternative is a
 * bundled database of every fire, torch and lantern in the game, which is exactly the
 * kind of asset weight this plugin is meant to avoid - and guessing ids without checking
 * has already gone wrong once here. {@code ::lightids} reports what is nearby so the list
 * can be built from what is actually on screen.
 */
class LightScanner
{
	/** Must match MAX_LIGHTS in frag.glsl. */
	static final int MAX_LIGHTS = 12;

	/** How far around the player to look, in tiles. */
	private static final int RADIUS = 12;

	private static final int TILE = 128;

	private final Client client;

	/** Flattened xyz per light, filled by {@link #scan}. */
	final float[] positions = new float[MAX_LIGHTS * 3];
	int count;

	LightScanner(Client client)
	{
		this.client = client;
	}

	/**
	 * Rebuilds the light list. Must run on the client thread.
	 */
	void scan(java.util.Set<Integer> ids)
	{
		count = 0;
		if (ids.isEmpty())
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		Player player = client.getLocalPlayer();
		if (wv == null || player == null)
		{
			return;
		}

		LocalPoint origin = player.getLocalLocation();
		if (origin == null)
		{
			return;
		}

		Tile[][][] tiles = wv.getScene().getTiles();
		int plane = wv.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return;
		}

		int cx = origin.getSceneX();
		int cy = origin.getSceneY();

		List<TileObject> found = new ArrayList<>();

		for (int x = Math.max(0, cx - RADIUS); x <= Math.min(tiles[plane].length - 1, cx + RADIUS); ++x)
		{
			for (int y = Math.max(0, cy - RADIUS); y <= Math.min(tiles[plane][x].length - 1, cy + RADIUS); ++y)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}

				collect(found, ids, tile);

				if (found.size() >= MAX_LIGHTS)
				{
					// More lights than the shader can take; the nearest are what matter.
					break;
				}
			}
		}

		for (TileObject obj : found)
		{
			if (count >= MAX_LIGHTS)
			{
				break;
			}

			LocalPoint lp = obj.getLocalLocation();
			if (lp == null)
			{
				continue;
			}

			positions[count * 3] = lp.getX();
			// Lifted off the floor so the light sits in the flame rather than under it.
			positions[count * 3 + 1] = obj.getZ() - TILE * 0.4f;
			positions[count * 3 + 2] = lp.getY();
			++count;
		}
	}

	private static void collect(List<TileObject> out, java.util.Set<Integer> ids, Tile tile)
	{
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject obj : gameObjects)
			{
				if (obj != null && ids.contains(obj.getId()))
				{
					out.add(obj);
				}
			}
		}

		if (tile.getWallObject() != null && ids.contains(tile.getWallObject().getId()))
		{
			out.add(tile.getWallObject());
		}
		if (tile.getGroundObject() != null && ids.contains(tile.getGroundObject().getId()))
		{
			out.add(tile.getGroundObject());
		}
		if (tile.getDecorativeObject() != null && ids.contains(tile.getDecorativeObject().getId()))
		{
			out.add(tile.getDecorativeObject());
		}
	}
}
