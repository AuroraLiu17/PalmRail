"""Export a scene-linear, neutral studio environment for the standalone rail."""
import bpy
import numpy as np
from pathlib import Path

width, height = 512, 256
longitude = (np.arange(width) + .5) * (2 * np.pi / width) - np.pi
latitude = (np.arange(height) + .5) * (np.pi / height) - np.pi / 2
x = np.cos(latitude)[:, None] * np.sin(longitude)[None, :]
y = np.sin(latitude)[:, None]
z = np.cos(latitude)[:, None] * np.cos(longitude)[None, :]
radiance = np.broadcast_to(.23 + .13 * (y + 1), (height, width)).copy()
for direction, spread, strength in (
    ((-.45, .61, .65), 16, 2.7),  # broad key reflection
    ((.65, .44, -.61), 19, 1.55),  # softer rim reflection
):
    dx, dy, dz = np.asarray(direction) / np.linalg.norm(direction)
    radiance += strength * np.exp(spread * (dx * x + dy * y + dz * z - 1))
pixels = np.ones((height, width, 4), dtype=np.float32)
pixels[:, :, :3] = radiance[:, :, None]
image = bpy.data.images.new('Neutral rail studio', width, height, float_buffer=True)
image.pixels.foreach_set(pixels.ravel())
scene = bpy.context.scene
scene.render.image_settings.file_format = 'OPEN_EXR'
scene.render.image_settings.color_mode = 'RGB'
scene.render.image_settings.color_depth = '16'
output = Path(__file__).resolve().parents[1] / 'app/src/main/assets/rail-neutral-studio.exr'
image.save_render(str(output), scene=scene)
