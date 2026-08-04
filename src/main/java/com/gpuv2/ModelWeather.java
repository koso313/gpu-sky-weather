package com.gpuv2;

import com.gpuv2.config.WeatherMode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Weather drawn with the game's own animated precipitation models, as an alternative to
 * the screen-space shader pass.
 *
 * <p>The models are pre-animated volumes rather than single droplets, so a grid of a few
 * dozen instances around the player covers the visible area - this is not one object per
 * raindrop.
 *
 * <p>Model and animation ids were identified from ScreteMonge's 3D-Weather plugin
 * (BSD-2-Clause); the implementation here is our own.
 */
@Slf4j
class ModelWeather
{
	private static final int RAIN_MODEL = 15524;
	private static final int RAIN_ANIMATION = 7001;
	private static final int SNOW_MODEL = 27835;
	private static final int SNOW_ANIMATION = 7000;

	/** Tiles between instances. The models cover an area, so they don't need to be dense. */
	private static final int SPACING = 4;
	private static final int TILE = 128;

	private final Client client;
	private final List<RuneLiteObject> objects = new ArrayList<>();

	/** What the currently-built models represent, so they're only rebuilt on a real change. */
	private WeatherMode builtFor = WeatherMode.OFF;
	private Model model;
	private Animation animation;

	ModelWeather(Client client)
	{
		this.client = client;
	}

	/**
	 * Repositions the grid around the player, creating or destroying instances as the mode
	 * or density changes. Must run on the client thread.
	 *
	 * @param radius how many instances to place either side of the player
	 */
	void update(WeatherMode mode, int radius)
	{
		if (mode == WeatherMode.OFF)
		{
			clear();
			return;
		}

		Player player = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (player == null || wv == null)
		{
			clear();
			return;
		}

		if (mode != builtFor)
		{
			// Different precipitation - rebuild the model and drop the old instances.
			clear();
			if (!buildModel(mode))
			{
				return;
			}
			builtFor = mode;
		}

		int side = radius * 2 + 1;
		int wanted = side * side;
		resize(wanted);

		LocalPoint origin = player.getLocalLocation();
		if (origin == null)
		{
			return;
		}

		// Snap the grid to fixed world coordinates so it doesn't visibly slide along with
		// the player - the precipitation should look like it belongs to the world.
		int step = SPACING * TILE;
		int baseX = Math.floorDiv(origin.getX(), step) * step;
		int baseY = Math.floorDiv(origin.getY(), step) * step;

		int i = 0;
		for (int gx = -radius; gx <= radius; ++gx)
		{
			for (int gy = -radius; gy <= radius; ++gy)
			{
				RuneLiteObject o = objects.get(i++);
				LocalPoint lp = new LocalPoint(baseX + gx * step, baseY + gy * step, wv);
				o.setLocation(lp, wv.getPlane());
				o.setActive(true);
			}
		}
	}

	private boolean buildModel(WeatherMode mode)
	{
		int modelId = mode.isRainLike() ? RAIN_MODEL : SNOW_MODEL;
		int animId = mode.isRainLike() ? RAIN_ANIMATION : SNOW_ANIMATION;

		try
		{
			var data = client.loadModelData(modelId);
			if (data == null)
			{
				log.warn("weather model {} not in cache", modelId);
				return false;
			}

			// Stretched vertically so one instance covers the height of the scene.
			model = data.cloneColors().scale(100, 256, 100).light();
			animation = client.loadAnimation(animId);
			return model != null && animation != null;
		}
		catch (RuntimeException ex)
		{
			log.warn("failed to build weather model {}", modelId, ex);
			return false;
		}
	}

	private void resize(int wanted)
	{
		while (objects.size() > wanted)
		{
			RuneLiteObject o = objects.remove(objects.size() - 1);
			o.setActive(false);
		}

		while (objects.size() < wanted)
		{
			RuneLiteObject o = client.createRuneLiteObject();
			o.setModel(model);
			o.setAnimation(animation);
			o.setShouldLoop(true);
			objects.add(o);
		}
	}

	/**
	 * Deactivates and drops every instance. Safe to call repeatedly.
	 */
	void clear()
	{
		for (RuneLiteObject o : objects)
		{
			o.setActive(false);
		}
		objects.clear();
		builtFor = WeatherMode.OFF;
	}
}
