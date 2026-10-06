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
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Finds nearby objects that should cast light.
 *
 * <p>Nothing in the game data marks an object as emitting light - the stock client has no
 * dynamic lighting, so it never needed such a flag. Two tests stand in for it, and between
 * them they need no id database and no list for anyone to maintain:
 *
 * <ol>
 *   <li><b>The name.</b> Anything you can click is generally called what it is: a fire, a
 *       torch, a brazier.
 *   <li><b>The model.</b> Names run out fast - most scenery has none at all, since the
 *       cache stores the literal string "null" for anything not interactable, and a wall
 *       torch is pure decoration. So an object that is animated and painted like fire
 *       counts too, whatever it is or is not called. See {@link FlameDetector}.
 * </ol>
 *
 * <p>Verdicts are cached per object id, since the same handful of ids recur across every
 * tile in a scene and the model test is the expensive one.
 */
@Slf4j
class LightScanner
{
	/**
	 * Hard ceiling on lights drawn at once. Must match MAX_LIGHTS in frag.glsl.
	 *
	 * <p>This is the size of the shader's uniform arrays, so it is fixed at compile time.
	 * How many of the slots are actually used is a setting, because every one of them costs
	 * an iteration of a loop that runs for every pixel on screen.
	 */
	static final int MAX_LIGHTS = 64;

	/**
	 * Headroom between how far a light reaches and how far out they are looked for, in
	 * tiles.
	 *
	 * <p>A light stops existing the moment it leaves the scanned patch, so scanning only as
	 * far as a light reaches would delete lights that are still lighting something. The
	 * margin also gives the fade somewhere to happen.
	 */
	private static final int SCAN_MARGIN = 6;

	/**
	 * Hard cap on the search radius, in tiles.
	 *
	 * <p>The walk is the expensive half of a scan and it grows with the square of this, so
	 * it is bounded independently of how far the player has pushed their draw distance.
	 */
	private static final int SCAN_CAP = 60;

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

	/** Object id to the colour it glows, looked up once per id like the verdict above. */
	private final Map<Integer, Integer> colourCache = new HashMap<>();

	/**
	 * Scenery lights, from {@link #scan}. Held separately because the tile walk that finds
	 * them only runs on the game tick, while the combined list below is rebuilt every frame.
	 */
	private final float[] staticPositions = new float[MAX_LIGHTS * 3];
	private final float[] staticFade = new float[MAX_LIGHTS];
	private final int[] staticColour = new int[MAX_LIGHTS];
	private int staticCount;

	/** How many lights may be drawn, and how far out to look, from config at scan time. */
	private int budget = MAX_LIGHTS;
	private int scanRadius = 25;
	private int lightRadius = 6;

	/** Set by the last scan so ::lightids can report whether the budget is what binds. */
	float lastEdgeTiles;
	int lastCandidates;

	int scanRadiusTiles()
	{
		return scanRadius;
	}

	/**
	 * Forgets the lights found by the last scan.
	 *
	 * <p>Needed because the frame collection keeps running when the scan does not. Clearing
	 * only the combined count would leave the last scan's torches in place, so they would be
	 * copied back in on the very next frame - lights would go on skipping the scan but never
	 * actually go out.
	 */
	void clearScenery()
	{
		staticCount = 0;
	}

	/** Flattened xyz per light, rebuilt each frame by {@link #collectFrame}. */
	final float[] positions = new float[MAX_LIGHTS * 3];

	/** Per-light 0..1 multiplier that dims lights approaching the edge of the scan. */
	final float[] fade = new float[MAX_LIGHTS];

	/** Per-light colour packed 0xRRGGBB, or -1 for ordinary fire, which takes the configured colour. */
	final int[] colour = new int[MAX_LIGHTS];

	int count;

	LightScanner(Client client)
	{
		this.client = client;
	}

	/**
	 * Rebuilds the scenery light list. Must run on the client thread.
	 *
	 * <p>On the game tick rather than per frame - the tile walk is the expensive half, and
	 * scenery does not move between ticks anyway.
	 */
	void scan(int lightRadiusTiles, int maxLights, int searchTiles)
	{
		staticCount = 0;
		budget = Math.max(1, Math.min(MAX_LIGHTS, maxLights));
		lightRadius = Math.max(1, lightRadiusTiles);

		/*
		 * Search as far as the world is drawn, not as far as a light reaches.
		 *
		 * Tying the two together meant a torch stopped existing a few tiles past its own
		 * glow, so its pool of light blinked into being as the player walked up to it even
		 * though the torch had been on screen the whole time. A light's contribution is
		 * already limited by its own falloff, so reaching further costs nothing but a slot
		 * in the budget - and buys lit scenery wherever it is visible.
		 */
		scanRadius = Math.min(SCAN_CAP, Math.max(lightRadius + SCAN_MARGIN, searchTiles));
		lastCandidates = 0;
		lastEdgeTiles = scanRadius;

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

		for (int x = Math.max(0, cx - scanRadius); x <= Math.min(tiles[plane].length - 1, cx + scanRadius); ++x)
		{
			for (int y = Math.max(0, cy - scanRadius); y <= Math.min(tiles[plane][x].length - 1, cy + scanRadius); ++y)
			{
				Tile tile = tiles[plane][x][y];
				if (tile != null)
				{
					collect(found, seen, tile);
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
		found.removeIf(o -> o.getLocalLocation() == null);
		found.sort(Comparator.comparingInt(o -> distanceSq(o, origin)));

		/*
		 * Where lights actually stop, which is not always the scan boundary.
		 *
		 * Once detection started finding torches by their models rather than only by name,
		 * a city block can hold far more than the budget within a few tiles. The budget then
		 * becomes the real edge: the 33rd nearest light is dropped outright, and walking a
		 * step is enough to swap which one that is. Fading against the scan radius does
		 * nothing about that, because at 6 tiles out the fade is still returning 1.
		 *
		 * So when the budget is what binds, the last light that fits marks the edge and the
		 * fade is measured against that instead. Whatever falls off the end has already
		 * dimmed to nothing on its way there.
		 */
		float edge = scanRadius;
		if (found.size() > budget)
		{
			float lastKept = (float) Math.sqrt(distanceSq(found.get(budget - 1), origin)) / TILE;

			/*
			 * Never fade a light that is still inside its own reach.
			 *
			 * Taking the budget edge on its own made the radius setting work backwards:
			 * turning it up pulls in more candidates, so the budget binds sooner, so the
			 * edge collapses closer to the player - and the fade then dims the very torches
			 * that were working. Asking for more light produced less.
			 *
			 * Where the budget bites inside the light radius there is no fade that helps.
			 * Every light being dropped is one that was visibly lighting something, and
			 * dimming the whole set to hide that costs more than the pop does. So the floor
			 * is the light radius: past that point, accept the pop and keep the brightness.
			 */
			edge = Math.min(edge, Math.max(lastKept, lightRadius));
		}

		lastCandidates = found.size();
		lastEdgeTiles = edge;

		for (TileObject obj : found)
		{
			if (staticCount >= budget)
			{
				break;
			}

			LocalPoint lp = obj.getLocalLocation();
			staticPositions[staticCount * 3] = lp.getX();
			// Lifted off the floor so the light sits in the flame rather than under it.
			staticPositions[staticCount * 3 + 1] = obj.getZ() - TILE * 0.4f;
			staticPositions[staticCount * 3 + 2] = lp.getY();

			float tilesAway = (float) Math.sqrt(distanceSq(obj, origin)) / TILE;
			staticFade[staticCount] = fadeAt(tilesAway, edge);
			staticColour[staticCount] = glowOf(obj);
			++staticCount;
		}
	}

	/**
	 * Builds the light list for this frame from the last scan.
	 *
	 * <p>Kept separate from {@link #scan} so the expensive tile walk stays on the game tick
	 * while the uniform upload happens per frame.
	 */
	void collectFrame()
	{
		count = 0;

		for (int i = 0; i < staticCount && count < budget; ++i)
		{
			positions[count * 3] = staticPositions[i * 3];
			positions[count * 3 + 1] = staticPositions[i * 3 + 1];
			positions[count * 3 + 2] = staticPositions[i * 3 + 2];
			fade[count] = staticFade[i];
			colour[count] = staticColour[i];
			++count;
		}
	}

	/**
	 * How brightly a light this many tiles away should burn, 0..1, purely as a function of
	 * the scan boundary. Full until the fade band, then linearly down to nothing at the
	 * edge. The light's own radius falloff applies on top of this in the shader.
	 */
	static float fadeAt(float tilesAway, float edgeTiles)
	{
		if (edgeTiles <= 0f)
		{
			return 0f;
		}

		/*
		 * The band has to fit inside the region it is fading, or it dims everything.
		 *
		 * A fixed 5 tile band is fine against the 25 tile scan radius, but the edge can be
		 * much closer than that when the light budget is what binds - somewhere dense it may
		 * be 3 tiles, at which point a fixed band reaches past the player and a torch at
		 * arm's length comes out at 0.4 brightness. Capping it at a share of the radius
		 * keeps the fade where it belongs: on the outermost lights, the ones about to be
		 * dropped, and nowhere near the ones you are standing next to.
		 */
		float band = Math.min(FADE_BAND, edgeTiles * 0.35f);
		if (band <= 0f)
		{
			return tilesAway < edgeTiles ? 1f : 0f;
		}

		return Math.max(0f, Math.min(1f, (edgeTiles - tilesAway) / band));
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

	private void collect(List<TileObject> out, Set<Long> seen, Tile tile)
	{
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject obj : gameObjects)
			{
				if (obj != null && isLight(obj.getId(), obj.getRenderable()))
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

		WallObject wall = tile.getWallObject();
		if (wall != null && (isLight(wall.getId(), wall.getRenderable1())
			|| isLight(wall.getId(), wall.getRenderable2())))
		{
			out.add(wall);
		}

		GroundObject ground = tile.getGroundObject();
		if (ground != null && isLight(ground.getId(), ground.getRenderable()))
		{
			out.add(ground);
		}

		DecorativeObject dec = tile.getDecorativeObject();
		if (dec != null && (isLight(dec.getId(), dec.getRenderable())
			|| isLight(dec.getId(), dec.getRenderable2())))
		{
			out.add(dec);
		}
	}

	/**
	 * The colour this light glows, or -1 for firelight.
	 *
	 * <p>Only a model that was actually read gets remembered. One that is not there yet -
	 * a zone still being built - is asked again on the next scan, for the same reason the
	 * light verdict is: caching the absence would fix the wrong answer for the session.
	 */
	private int glowOf(TileObject obj)
	{
		Integer cached = colourCache.get(obj.getId());
		if (cached != null)
		{
			return cached;
		}

		boolean read = false;
		int rgb = -1;
		for (Renderable renderable : renderablesOf(obj))
		{
			Model model = modelOf(renderable);
			if (model == null)
			{
				continue;
			}

			read = true;
			rgb = FlameDetector.glowRgb(model.getFaceColors1());
			if (rgb >= 0)
			{
				break;
			}
		}

		if (read)
		{
			colourCache.put(obj.getId(), rgb);
		}
		return rgb;
	}

	private static Renderable[] renderablesOf(TileObject obj)
	{
		if (obj instanceof GameObject)
		{
			return new Renderable[]{((GameObject) obj).getRenderable()};
		}
		if (obj instanceof WallObject)
		{
			WallObject wall = (WallObject) obj;
			return new Renderable[]{wall.getRenderable1(), wall.getRenderable2()};
		}
		if (obj instanceof DecorativeObject)
		{
			DecorativeObject dec = (DecorativeObject) obj;
			return new Renderable[]{dec.getRenderable(), dec.getRenderable2()};
		}
		if (obj instanceof GroundObject)
		{
			return new Renderable[]{((GroundObject) obj).getRenderable()};
		}
		return new Renderable[0];
	}

	private boolean isLight(int id, Renderable renderable)
	{
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

		// Name first, since it is far cheaper than building a model.
		if (nameSuggestsLight(name))
		{
			lightCache.put(id, true);
			return true;
		}

		/*
		 * A model that is not there yet is not the same as a model that is not on fire. The
		 * renderable can be null, or getModel() can return null, while a zone is still being
		 * built - and caching that as "no" would leave the object dark for the rest of the
		 * session even once its geometry arrived. Only a model we actually read gets a
		 * verdict; anything else is left uncached to be retried next tick.
		 */
		Model model = modelOf(renderable);
		if (model == null)
		{
			return false;
		}

		boolean light = isBurning(renderable, model);
		lightCache.put(id, light);
		return light;
	}

	/**
	 * Whether an object is visibly on fire: animated, and painted like a flame.
	 *
	 * <p>Both halves are load-bearing. Colour alone catches yellow banners and gilded trim;
	 * animation alone catches windmills and spinning wheels. Something that flickers *and*
	 * is bright saturated orange is a fire.
	 */
	/**
	 * Whether an object is painted like fire.
	 *
	 * <p>No longer requires the object to be animated. That gate was there because colour
	 * alone would claim a yellow banner, and a flicker is good evidence of a flame - but it
	 * also excluded every static wall torch, which is precisely what this exists to find.
	 * The saturation and brightness floors carry the discrimination instead.
	 */
	static boolean isBurning(Renderable renderable, Model model)
	{
		return FlameDetector.looksLikeFlame(model.getFaceColors1());
	}

	/**
	 * The model behind a renderable, or null if there is not one to read.
	 *
	 * <p>Static scenery <i>is</i> its own model, and {@code getModel()} on one of those
	 * returns null - it exists to unwrap things that hold a model, like an animated object
	 * holding the current frame. Calling it unconditionally asked every wall torch in the
	 * game to hand over its model and took the null for "no geometry here", which is why
	 * ::lightids reported "renderable fx but model null" for essentially all of them.
	 */
	static Model modelOf(Renderable renderable)
	{
		if (renderable instanceof Model)
		{
			return (Model) renderable;
		}

		if (renderable == null)
		{
			return null;
		}

		try
		{
			return renderable.getModel();
		}
		catch (RuntimeException ex)
		{
			log.debug("could not read model", ex);
			return null;
		}
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
