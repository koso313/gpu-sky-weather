package com.gpuv2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SceneryFilterTest
{
	@Test
	public void matchesTheTreesPeopleMean()
	{
		String[] trees = {
			"Tree", "Oak tree", "Willow tree", "Yew tree", "Magic tree",
			"Maple tree", "Mahogany", "Teak", "Arctic pine", "Dead tree",
		};

		for (String name : trees)
		{
			assertTrue(name + " should be a tree", SceneryFilter.isTree(name));
		}
	}

	/**
	 * What is left behind after a tree matters more than the tree. A stump is what tells you
	 * the tree is felled, and hiding it would leave nothing to look at where one stood.
	 */
	@Test
	public void keepsWhatIsLeftBehind()
	{
		String[] kept = {"Tree stump", "Stump", "Logs", "Seedling", "Sapling"};

		for (String name : kept)
		{
			assertFalse(name + " should be kept", SceneryFilter.isTree(name));
		}
	}

	@Test
	public void matchesGroundClutter()
	{
		assertTrue(SceneryFilter.isClutter("Flowers"));
		assertTrue(SceneryFilter.isClutter("Daisies"));
		assertTrue(SceneryFilter.isClutter("Bush"));
		assertTrue(SceneryFilter.isClutter("Mushroom"));
	}

	/**
	 * Anything clickable must survive. The cost of a false positive here is a missing
	 * interaction, which is far worse than a slightly busier floor.
	 */
	@Test
	public void leavesUsefulSceneryAlone()
	{
		String[] kept = {
			"Bank booth", "Door", "Ladder", "Staircase", "Altar", "Furnace",
			"Anvil", "Fishing spot", "Rocks", "Chest",
		};

		for (String name : kept)
		{
			assertFalse(name + " should not be hidden", SceneryFilter.isTree(name));
			assertFalse(name + " should not be hidden", SceneryFilter.isClutter(name));
		}
	}

	@Test
	public void ignoresCaseAndMissingNames()
	{
		assertTrue(SceneryFilter.isTree("OAK TREE"));
		assertFalse(SceneryFilter.isTree(null));
		assertFalse(SceneryFilter.isTree(""));
		assertFalse(SceneryFilter.isTree("null"));
	}

	/** The two categories are independent - a tree is not clutter and vice versa. */
	@Test
	public void categoriesDoNotOverlap()
	{
		assertFalse(SceneryFilter.isClutter("Oak tree"));
		assertFalse(SceneryFilter.isTree("Flowers"));
	}
}
