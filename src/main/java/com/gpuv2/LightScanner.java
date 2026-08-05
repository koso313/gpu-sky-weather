package com.gpuv2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
	static final int MAX_LIGHTS = 32;

	/**
	 * How far around the player to look, in tiles.
	 *
	 * <p>Has to comfortably exceed the light radius setting, because a light stops existing
	 * the moment it leaves this patch - and a light that is still within its own reach when
	 * that happens vanishes mid-throw. The setting caps at 20 tiles, so 25 leaves exactly
	 * the fade band below spare.
	 */
	private static final int RADIUS = 25;

	/**
	 * Width of the band at the edge of the scan, in tiles, over which a light dims to
	 * nothing.
	 *
	 * <p>Without it, walking away snaps a torch off the instant it crosses the boundary, and
	 * walking back snaps it on. The band means what leaves the set is already dark.
	 */
	private static final float FADE_BAND = 5f;

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

	/** Per-light 0..1 multiplier that dims lights approaching the edge of the scan. */
	final float[] fade = new float[MAX_LIGHTS];

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
		Set<Long> seen = new HashSet<>();

		for (int x = Math.max(0, cx - RADIUS); x <= Math.min(tiles[plane].length - 1, cx + RADIUS); ++x)
		{
			for (int y = Math.max(0, cy - RADIUS); y <= Math.min(tiles[plane][x].length - 1, cy + RADIUS); ++y)
			{
				Tile tile = tiles[plane][x][y];
				if (tile != null)
				{
					collect(found, seen, extraIds, tile);
				}
			}
		}

		/*
		 * Nearest first, then take the budget off the front.
		 *
		 * The scan walks the patch corner to corner, so taking the first MAX_LIGHTS it
		 * happened to trip over meant the budget filled from whichever corner the loop
		 * started in, regardless of where the player was looking. In a place as dense with
		 * torches as Falador that is spent well before the loop reaches you, so the lights
		 * beside you stayed dark while ones behind a building half a street away burned.
		 *
		 * Sorting also makes the eviction survivable: what gets dropped when the budget
		 * overflows is now always the farthest light, which the fade below has already
		 * dimmed towards nothing.
		 */
		found.sort(Comparator.comparingInt(o -> distanceSq(o, origin)));

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

			fade[count] = fadeAt((float) Math.sqrt(distanceSq(obj, origin)) / TILE);
			++count;
		}
	}

	/**
	 * How brightly a light this many tiles away should burn, 0..1, purely as a function of
	 * the scan boundary. Full until the fade band, then linearly down to nothing at the
	 * edge. The light's own radius falloff applies on top of this in the shader.
	 */
	static float fadeAt(float tilesAway)
	{
		return Math.max(0f, Math.min(1f, (RADIUS - tilesAway) / FADE_BAND));
	}

	/**
	 * Squared distance in local units, squared to keep the sort off square roots.
	 * Objects with no location sort last rather than throwing.
	 */
	private static int distanceSq(TileObject obj, LocalPoint origin)
	{
		LocalPoint lp = obj.getLocalLocation();
		if (lp == null)
		{
			return Integer.MAX_VALUE;
		}

		int dx = lp.getX() - origin.getX();
		int dy = lp.getY() - origin.getY();
		return dx * dx + dy * dy;
	}

	private void collect(List<TileObject> out, Set<Long> seen, Set<Integer> extraIds, Tile tile)
	{
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject obj : gameObjects)
			{
				if (obj != null && isLight(obj.getId(), extraIds))
				{
					/*
					 * A multi-tile object is referenced from every tile it covers, so a
					 * single large brazier arrives here several times over. Each copy used
					 * to take its own slot out of the budget and then stack its light on
					 * the same spot, which both wasted the budget and made that one object
					 * brighter than its neighbours for no reason.
					 */
					if (seen.add(obj.getHash()))
					{
						out.add(obj);
					}
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

		String name = resolveName(id);
		if (name == null)
		{
			/*
			 * Not cached. A lookup can fail transiently - the definition may not be loaded
			 * yet just after a scene change - and caching that "no" would leave the object
			 * permanently dark for the rest of the session, long after the data arrived.
			 */
			return false;
		}

		boolean light = nameSuggestsLight(name);
		lightCache.put(id, light);
		return light;
	}

	/**
	 * The name detection actually sees for an object id, or null if it could not be
	 * resolved. Shared with the ::lightids diagnostic so what it prints is what is judged.
	 */
	String resolveName(int id)
	{
		try
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			if (comp == null)
			{
				return null;
			}

			// Some objects vary by player state - a lit brazier and an unlit one share
			// an id and differ only by impostor, so resolve to the active one.
			if (comp.getImpostorIds() != null)
			{
				ObjectComposition impostor = comp.getImpostor();
				/*
				 * Only when the impostor actually has a name. Some resolve to a nameless
				 * placeholder, and taking that over the base composition threw away the
				 * one name that would have matched.
				 */
				if (impostor != null && !isBlank(impostor.getName()))
				{
					comp = impostor;
				}
			}

			return comp.getName();
		}
		catch (RuntimeException ex)
		{
			log.debug("could not resolve object {}", id, ex);
			return null;
		}
	}

	private static boolean isBlank(String name)
	{
		return name == null || name.isEmpty() || "null".equals(name);
	}

	static boolean nameSuggestsLight(String name)
	{
		if (isBlank(name))
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
