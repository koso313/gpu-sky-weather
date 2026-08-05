package com.gpuv2;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;

/**
 * Decides which scenery to leave out of the scene entirely.
 *
 * <p>Matched on names, the same approach {@link LightScanner} takes and for the same
 * reason: it needs no id database and works in areas nobody has visited. Unlike lights,
 * though, the things being matched here are all interactable - trees are chopped, flowers
 * are picked - so they do have names, and the model fallback lights needed is unnecessary.
 *
 * <p>Applied at scene upload, so the geometry is never built rather than being built and
 * skipped. That makes it a saving as well as a view, but it also means a change only takes
 * effect on the next scene load - which the plugin forces when the setting changes.
 */
@Slf4j
@Singleton
class SceneryFilter
{
	/**
	 * Anything whose name contains one of these is a tree.
	 *
	 * <p>"tree" alone catches oak, willow, magic and the rest, since they are all named as
	 * trees. The others are the ones that are not.
	 */
	private static final String[] TREE_WORDS = {
		"tree", "willow", "yew", "maple", "mahogany", "teak", "arctic pine",
	};

	/** Names containing a tree word that are not trees. */
	private static final String[] NOT_TREES = {
		"tree stump", "stump", "tree roots", "log", "seedling", "sapling",
	};

	/**
	 * Ground clutter: the small decorative things scattered over terrain.
	 *
	 * <p>Deliberately narrow. Anything a player might want to click is not clutter, and the
	 * cost of being wrong here is a missing interaction rather than a slightly busier floor.
	 */
	private static final String[] CLUTTER_WORDS = {
		"flowers", "daisies", "bush", "fern", "reeds", "weeds",
		"mushroom", "pebbles", "rubble", "grass",
	};

	private final Client client;

	/** Object id to whether it is hidden, so each id is resolved once. */
	private final Map<Integer, Boolean> cache = new HashMap<>();

	private boolean hideTrees;
	private boolean hideClutter;

	@Inject
	SceneryFilter(Client client)
	{
		this.client = client;
	}

	/**
	 * Updates what is being hidden. Clears the cache, since the answer for every id may have
	 * changed.
	 */
	void configure(boolean hideTrees, boolean hideClutter)
	{
		if (this.hideTrees != hideTrees || this.hideClutter != hideClutter)
		{
			this.hideTrees = hideTrees;
			this.hideClutter = hideClutter;
			cache.clear();
		}
	}

	boolean active()
	{
		return hideTrees || hideClutter;
	}

	boolean hidden(int id)
	{
		if (!active())
		{
			return false;
		}

		Boolean cached = cache.get(id);
		if (cached != null)
		{
			return cached;
		}

		String name = resolveName(id);
		if (name == null)
		{
			/*
			 * Uncached, not stored. A definition may not be loaded yet, and caching that as
			 * "keep" would be harmless here but caching it as anything at all risks the same
			 * bug the light scanner had, where one badly timed lookup stuck for the session.
			 */
			return false;
		}

		boolean hide = (hideTrees && matches(name, TREE_WORDS, NOT_TREES))
			|| (hideClutter && matches(name, CLUTTER_WORDS, null));

		cache.put(id, hide);
		return hide;
	}

	private String resolveName(int id)
	{
		try
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			return comp == null ? null : comp.getName();
		}
		catch (RuntimeException ex)
		{
			log.debug("could not resolve object {}", id, ex);
			return null;
		}
	}

	static boolean matches(String name, String[] words, String[] exclusions)
	{
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			return false;
		}

		String lower = name.toLowerCase(Locale.ROOT);

		if (exclusions != null)
		{
			for (String no : exclusions)
			{
				if (lower.contains(no))
				{
					return false;
				}
			}
		}

		for (String word : words)
		{
			if (lower.contains(word))
			{
				return true;
			}
		}

		return false;
	}

	static boolean isTree(String name)
	{
		return matches(name, TREE_WORDS, NOT_TREES);
	}

	static boolean isClutter(String name)
	{
		return matches(name, CLUTTER_WORDS, null);
	}
}
