"""Bake imported Blender wood color and bump to portable glTF textures."""
import sys
from pathlib import Path

import bpy

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'code/tools'))
from pegboard_material import append_source_material, policy, is_pegboard

pegboard_policy = policy()

export_args = sys.argv[sys.argv.index('--') + 1:]
out = Path(export_args[0])
scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.samples = 1
scene.render.bake.use_pass_direct = False
scene.render.bake.use_pass_indirect = False
scene.render.bake.use_pass_color = True
scene.render.bake.margin = 4


def exported(obj):
    return (obj.type in {'MESH', 'CURVE', 'FONT', 'EMPTY'}
            and not obj.name.startswith(('TEST |', 'BALL |'))
            and not obj.name.endswith('_ReferenceBall'))


def is_wood(material):
    return material and material.name.endswith(('satin ash', 'oiled walnut'))


def select(obj):
    bpy.ops.object.select_all(action='DESELECT')
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)


for obj in list(scene.objects):
    if not exported(obj) or obj.type not in {'MESH', 'CURVE'}:
        continue
    if obj.type == 'CURVE' and any(is_wood(mat) for mat in obj.data.materials):
        select(obj)
        bpy.ops.object.convert(target='MESH')
        obj = bpy.context.object
    if obj.type != 'MESH':
        continue
    used = {polygon.material_index for polygon in obj.data.polygons}
    if len(used) != 1:
        continue
    slot = next(iter(used))
    if slot >= len(obj.data.materials) or not is_wood(obj.data.materials[slot]):
        continue

    select(obj)
    if obj.data.users > 1:
        obj.data = obj.data.copy()
    for previous in list(obj.data.uv_layers):
        obj.data.uv_layers.remove(previous)
    uv = obj.data.uv_layers.new(name='BakedWood')
    uv.active_render = True
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(island_margin=pegboard_policy['newUvIslandMargin'] if is_pegboard(obj) else .015)
    bpy.ops.object.mode_set(mode='OBJECT')
    material = append_source_material(bpy, obj) if is_pegboard(obj) else obj.data.materials[slot].copy()
    obj.data.materials[slot] = material
    tree = material.node_tree
    shader = next(node for node in tree.nodes if node.type == 'BSDF_PRINCIPLED')
    resolution = (pegboard_policy['resolution'] if is_pegboard(obj) else
                  512 if obj.name.startswith(('BOARD |', 'TRACK |')) or obj.name.endswith('_Rail') else 192)
    scene.render.bake.margin = pegboard_policy['bakeMargin'] if is_pegboard(obj) else 4

    def bake_channel(name, channel, colorspace):
        raster = bpy.data.images.new(obj.name + ' ' + name, resolution, resolution, alpha=False)
        raster.colorspace_settings.name = colorspace
        texture = tree.nodes.new('ShaderNodeTexImage')
        texture.image = raster
        tree.nodes.active = texture
        bpy.ops.object.bake(type=channel)
        raster.pack()
        return texture

    color = bake_channel('wood color', 'DIFFUSE', 'sRGB')
    normal = bake_channel('wood normal', 'NORMAL', 'Non-Color')
    roughness = bake_channel('wood roughness', 'ROUGHNESS', 'Non-Color') if is_pegboard(obj) else None
    tree.links.new(color.outputs['Color'], shader.inputs['Base Color'])
    tangent = tree.nodes.new('ShaderNodeNormalMap')
    tree.links.new(normal.outputs['Color'], tangent.inputs['Color'])
    tree.links.new(tangent.outputs['Normal'], shader.inputs['Normal'])
    if roughness:
        tree.links.new(roughness.outputs['Color'], shader.inputs['Roughness'])

if out.stem == 'goal':
    bumper = bpy.data.objects['Goal_SoftBumper']
    steel = bpy.data.materials['04 | steel ball']
    bumper.data.materials[0] = steel

bpy.ops.object.select_all(action='DESELECT')
for obj in scene.objects:
    obj.select_set(exported(obj))
bpy.ops.export_scene.gltf(
    filepath=str(out),
    use_selection=True,
    export_animations=False,
    export_apply='--apply-modifiers' in export_args[1:],
)
