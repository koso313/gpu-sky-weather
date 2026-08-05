# GPU v2

A standalone GPU renderer for RuneLite with a light suite of graphical enhancements —
a day/night sky, weather, dynamic lighting from fires and torches, fog and
post-processing — built to stay close to the original art rather than replace it.

Everything is optional. The **Preset** dropdown at the top switches between `Default`,
which renders the game with the GPU and nothing else, and `Custom`, which uses your
settings. Flipping between them changes nothing you have configured, so it is a safe
way to compare.

![The plugin at a glance](docs/images/hero.png)

## Requirements

- OpenGL 3.3 or newer
- Only one GPU renderer can run at a time. GPU v2 will not start alongside the stock
  **GPU** plugin or **117HD** — disable those first.

## Features

### Sky and time of day

The sky runs a full day cycle from your system clock, shifting continuously through
dawn, midday, dusk and night rather than stepping between fixed states. The sun rises
in the east and sets in the west, matching the game's own compass, and its height
drives the sky colour, the direction of the lighting and the god rays.

At night there is a starfield, shooting stars and an aurora low in the northern sky.
The moon waxes and wanes on its own cycle, and its phase decides both where it sits
and how much light it throws — a new moon leaves genuinely darker nights.

| Dawn | Midday | Dusk | Night |
|---|---|---|---|
| ![Dawn](docs/images/sky-dawn.png) | ![Midday](docs/images/sky-midday.png) | ![Dusk](docs/images/sky-dusk.png) | ![Night](docs/images/sky-night.png) |

A **Preview time** slider holds the sky at any minute of the day, so you can sweep
through a sunrise to see the whole transition instead of waiting for one.

Underground, the sky blacks out and the weather stops — instantly on the way in, and
fading back as you climb out.

### Weather

Rain, storms, snow, blizzards and overcast. Each condition settles the sky to match
rather than falling out of a clear blue sky: the cloud deck thickens, the sun goes,
and the light on the ground dims with it. Storms bring lightning that flashes the
world as well as the sky.

Snow settles on upward-facing surfaces as it falls and melts once it stops; rain
darkens the ground and pools puddles.

| Rain | Storm | Snow | Overcast |
|---|---|---|---|
| ![Rain](docs/images/weather-rain.png) | ![Storm](docs/images/weather-storm.png) | ![Snow](docs/images/weather-snow.png) | ![Overcast](docs/images/weather-overcast.png) |

Set to **Automatic**, the weather changes on its own over time, keeping clear skies
roughly two thirds of the time so that a storm arriving is still worth noticing.

### Dynamic lighting

Fires, torches, lanterns and braziers light the ground around them. Sources are found
automatically — by name, and by looking at the model for burning faces — so there is
no list of IDs to maintain and no setup.

Lights flicker on their own rhythms, fade in through dusk and out again at dawn, and
are always lit underground.

![Torchlit street at night](docs/images/lighting-night.png)

### Fog and lighting

Distance fog fades the far edge of the scene into the sky colour, which hides the hard
line where drawing stops. Ground mist pools in low ground and valleys, and aerial
perspective tints distant scenery with the colour of the air.

Over the top of the game's own shading sits a configurable ambient and directional
light, with optional smooth (per-vertex) lighting for curved surfaces.

### Display and performance

Draw distance up to 184 tiles, MSAA, anisotropic filtering, FXAA, sharpening and
render scaling from 50% to 200%. Tone mapping rolls off bright areas that would
otherwise clip to flat white.

A performance overlay reports frame rate, frame time, 1% lows and — on NVIDIA cards —
GPU temperature and utilisation.

![Performance overlay](docs/images/perf-overlay.png)

Trees and ground clutter can be left out of the scene entirely, which is a saving as
well as a view.

## Configuration

Every setting has a description explaining what it does; hover any of them in the
plugin panel. The sections are:

| Section | What is in it |
|---|---|
| **Display** | Draw distance, anti-aliasing, render scale, tone mapping, object hiding |
| **Performance** | Frame rate, vsync, threads, effect quality, performance overlay |
| **Sky** | Sky colour mode, time of day, preview time, underground sky |
| **Fog** | Distance fog, ground mist, aerial perspective |
| **Sun and moon** | The discs themselves, glare, moon phases |
| **Stars and aurora** | Starfield, shooting stars, aurora |
| **Clouds** | Cover, strength, drift speed, cloud shadows |
| **Weather** | Condition, intensity, wind, ground effects, lightning |
| **Lighting** | Ambient and sun light, dynamic lights, underground darkening |
| **Post-processing** | Bloom, god rays, colour grading, colourblindness correction |

## Credits

Built on the stock RuneLite GPU plugin, which does the work of getting the scene onto
the graphics card. The enhancements on top of it are this plugin's own.
