package com.gpuv2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Finds nearby objects that should cast light.
 *
 * <p>Objects are matched on their names rather than a list of ids. Nothing in the game
 * data marks an object as emitting light - the stock client has no dynamic lighting, so
 * it never needed such a flag - but names are exposed, and anything that glows is
 * generally called what it is: a fire, a torch, a brazier. That works across the whole
 * game without shipping an id database or asking anyone to compile one.
 *
 * <p>Name lookups are cached per object id, since the same handful of ids recur across
 * every tile in a scene.
 */
@Slf4j
class LightScanner
{
	/** Must match MAX_LIGHTS in frag.glsl. */
	static final int MAX_LIGHTS = 12;

	/** How far around the player to look, in tiles. */
	private static final int RADIUS = 12;

	private static final int TILE = 128;

	/**
	 * Substrings that mark an object as a light source, lowercased.
	 *
	 * <p>"fire" also catches campfire, bonfire and fireplace, which is wanted.
	 */
	private static final String[] LIGHT_WORDS = {
		"fire", "torch", "lantern", "candle", "brazier", "lamp", "flame",
		"furnace", "forge", "firepit", "beacon",
	};

	/**
	 * Names containing a light word that are not lights - matching on substrings is broad
	 * by design, so the obvious traps are excluded rather than narrowing the word list and
	 * missing real ones.
	 */
	private static final String[] NOT_LIGHTS = {
		"fire rune", "firemaking", "fire cape", "fire altar", "extinguish",
		"burnt", "burned", "unlit", "lamp post",
	};

	private final Client client;

	/** Object id to whether it lights, so each id is looked up once. */
	private final Map<Integer, Boolean> lightCache = new HashMap<>();

	/** Flattened xyz per light, filled by {@link #scan}. */
	final float[] positions = new float[MAX_LIGHTS * 3];
	int count;

	LightScanner(Client client)
	{
		this.client = client;
	}

	/**
	 * Rebuilds the light list. Must run on the client thread.
	 *
	 * @param extraIds ids always treated as lights, for anything the names miss
	 */
	void scan(Set<Integer> extraIds)
	{
		count = 0;

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
				if (tile != null)
				{
					collect(found, extraIds, tile);
				}

				if (found.size() >= MAX_LIGHTS)
				{
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

	private void collect(List<TileObject> out, Set<Integer> extraIds, Tile tile)
	{
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject obj : gameObjects)
			{
				if (obj != null && isLight(obj.getId(), extraIds))
				{
					out.add(obj);
				}
			}
		}

		if (tile.getWallObject() != null && isLight(tile.getWallObject().getId(), extraIds))
		{
			out.add(tile.getWallObject());
		}
		if (tile.getGroundObject() != null && isLight(tile.getGroundObject().getId(), extraIds))
		{
			out.add(tile.getGroundObject());
		}
		if (tile.getDecorativeObject() != null && isLight(tile.getDecorativeObject().getId(), extraIds))
		{
			out.add(tile.getDecorativeObject());
		}
	}

	private boolean isLight(int id, Set<Integer> extraIds)
	{
		if (extraIds.contains(id))
		{
			return true;
		}

		Boolean cached = lightCache.get(id);
		if (cached != null)
		{
			return cached;
		}

		boolean light = false;
		try
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			if (comp != null)
			{
				// Some objects vary by player state - a lit brazier and an unlit one share
				// an id and differ only by impostor, so resolve to the active one.
				if (comp.getImpostorIds() != null && comp.getImpostor() != null)
				{
					comp = comp.getImpostor();
				}
				light = nameSuggestsLight(comp.getName());
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("could not resolve object {}", id, ex);
		}

		lightCache.put(id, light);
		return light;
	}

	static boolean nameSuggestsLight(String name)
	{
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			return false;
		}

		String lower = name.toLowerCase(Locale.ROOT);

		for (String no : NOT_LIGHTS)
		{
			if (lower.contains(no))
			{
				return false;
			}
		}

		for (String word : LIGHT_WORDS)
		{
			if (lower.contains(word))
			{
				return true;
			}
		}

		return false;
	}
}
