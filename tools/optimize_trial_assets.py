"""Lossless scene cleanup, opaque backdrop batching, and a bounded sky resize.

Only app-owned rendering assets are changed. Gameplay nodes, collision meshes,
nearby material images, UVs and normals retain their source data. The caller must
supply a preserved input directory and a separate output directory.
"""
from pathlib import Path
import argparse, copy, hashlib, io, json, struct
import numpy as np
from PIL import Image

TYPES={5121:np.dtype('u1'),5123:np.dtype('<u2'),5125:np.dtype('<u4'),5126:np.dtype('<f4')}
WIDTH={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}

def read(path):
    b=path.read_bytes(); length,kind=struct.unpack_from('<II',b,12)
    assert kind==0x4e4f534a
    return json.loads(b[20:20+length]),bytearray(b[28+length:])

def values(j,b,i):
    a=j['accessors'][i];v=j['bufferViews'][a['bufferView']];dtype=TYPES[a['componentType']];w=WIDTH[a['type']]
    assert 'sparse' not in a
    return np.ndarray((a['count'],w),dtype,buffer=b,offset=v.get('byteOffset',0)+a.get('byteOffset',0),
        strides=(v.get('byteStride',dtype.itemsize*w),dtype.itemsize)).copy()

def append(j,b,data,kind,ctype):
    while len(b)%4:b.append(0)
    view=len(j['bufferViews']);j['bufferViews'].append({'buffer':0,'byteOffset':len(b),'byteLength':data.nbytes})
    b.extend(data.tobytes());idx=len(j['accessors'])
    a={'bufferView':view,'componentType':ctype,'count':len(data),'type':kind}
    if kind=='VEC3':a.update(min=data.min(axis=0).tolist(),max=data.max(axis=0).tolist())
    j['accessors'].append(a);return idx

def batch_opaque(j,b):
    ids=[i for i,n in enumerate(j['nodes']) if n.get('name','').startswith('Timber') and n['name'].endswith('Opaque')]
    assert len(ids)==24 and len(j['scenes'])==1 and 'animations'not in j
    attrs={};indices=[];offset=0;material=None
    for i in ids:
        node=j['nodes'][i];assert set(node)<= {'name','mesh'}
        prim=j['meshes'][node['mesh']]['primitives'];assert len(prim)==1
        p=prim[0];assert p.get('mode',4)==4
        material=p['material'] if material is None else material;assert p['material']==material
        for name,a in p['attributes'].items():attrs.setdefault(name,[]).append(values(j,b,a))
        indices.append(values(j,b,p['indices']).astype('<u4')+offset)
        offset+=j['accessors'][p['attributes']['POSITION']]['count']
    merged={'attributes':{},'material':material}
    for name,parts in attrs.items():
        assert len(parts)==len(ids)
        data=np.concatenate(parts);merged['attributes'][name]=append(j,b,data,'VEC'+str(data.shape[1]),5126)
    merged['indices']=append(j,b,np.concatenate(indices),'SCALAR',5125)
    j['meshes'].append({'name':'TimberOpaqueBatch','primitives':[merged]})
    j['nodes'].append({'name':'TimberOpaqueBatch','mesh':len(j['meshes'])-1})
    keep=[i for i in range(len(j['nodes'])) if i not in ids];node_map={old:new for new,old in enumerate(keep)}
    j['nodes']=[j['nodes'][i] for i in keep]
    j['scenes'][0]['nodes']=[node_map[i] for i in keep]
    meshes=sorted({n['mesh'] for n in j['nodes']});mesh_map={old:new for new,old in enumerate(meshes)}
    j['meshes']=[j['meshes'][i] for i in meshes]
    for n in j['nodes']:n['mesh']=mesh_map[n['mesh']]

def texture_slots(value):
    if isinstance(value,dict):
        for k,v in value.items():
            if k.endswith('Texture') and isinstance(v,dict) and 'index'in v:yield v
            else:yield from texture_slots(v)
    elif isinstance(value,list):
        for v in value:yield from texture_slots(v)

def optimize(path,target):
    j,b=read(path);before=copy.deepcopy(j);old_bytes=path.stat().st_size
    if path.name=='stage-timber.glb':batch_opaque(j,b)
    slots=list(texture_slots(j.get('materials',[])));textures=sorted({s['index'] for s in slots})
    ims=[];im_map={};seen={};pixels_before=0;pixels_after=0
    replacements={}
    for i in sorted({j['textures'][t]['source'] for t in textures}):
        im=j['images'][i];v=j['bufferViews'][im['bufferView']];raw=bytes(b[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']])
        pic=Image.open(io.BytesIO(raw));pixels_before+=pic.width*pic.height
        if path.name=='stage-timber.glb' and im.get('name')=='sky-color':
            assert pic.size==(4096,2048)
            pic=pic.resize((2048,1024),Image.Resampling.LANCZOS);stream=io.BytesIO();pic.save(stream,format='PNG');raw=stream.getvalue()
        digest=hashlib.sha256(raw).hexdigest()
        if digest not in seen:
            seen[digest]=len(ims);item=copy.deepcopy(im);ims.append(item);pixels_after+=pic.width*pic.height
            replacements[item['bufferView']]=raw
        im_map[i]=seen[digest]
    newtex=[];tex_map={};seen_tex={}
    for t in textures:
        tex=copy.deepcopy(j['textures'][t]);tex['source']=im_map[tex['source']]
        key=json.dumps({k:v for k,v in tex.items() if k!='name'},sort_keys=True)
        if key not in seen_tex:seen_tex[key]=len(newtex);newtex.append(tex)
        tex_map[t]=seen_tex[key]
    for slot in slots:slot['index']=tex_map[slot['index']]
    j['images']=ims;j['textures']=newtex
    used=sorted({a for m in j['meshes'] for p in m['primitives'] for a in list(p['attributes'].values())+([p['indices']] if 'indices'in p else [])})
    amap={a:i for i,a in enumerate(used)}
    for m in j['meshes']:
        for p in m['primitives']:
            p['attributes']={name:amap[a] for name,a in p['attributes'].items()}
            if 'indices'in p:p['indices']=amap[p['indices']]
    j['accessors']=[j['accessors'][a] for a in used]
    views=sorted({a['bufferView'] for a in j['accessors']}|{im['bufferView'] for im in ims});vmap={old:i for i,old in enumerate(views)}
    newb=bytearray();newv=[]
    for i in views:
        v=copy.deepcopy(j['bufferViews'][i]);raw=replacements.get(i,bytes(b[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']]))
        while len(newb)%4:newb.append(0)
        v['byteOffset']=len(newb);v['byteLength']=len(raw);newv.append(v);newb.extend(raw)
    for a in j['accessors']:a['bufferView']=vmap[a['bufferView']]
    for im in ims:im['bufferView']=vmap[im['bufferView']]
    j['bufferViews']=newv;j['buffers']=[{'byteLength':len(newb)}]
    while len(newb)%4:newb.append(0)
    data=json.dumps(j,separators=(',',':')).encode();data+=b' '*((-len(data))%4)
    output=struct.pack('<III',0x46546c67,2,28+len(data)+len(newb))+struct.pack('<II',len(data),0x4e4f534a)+data+struct.pack('<II',len(newb),0x004e4942)+newb
    target.write_bytes(output)
    if path.name!='stage-timber.glb':
        # Physical and interactive geometry stays bit-for-bit identical per accessor.
        oldj,oldb=read(path)
        assert oldj['nodes']==j['nodes']
        for oldm,newm in zip(oldj['meshes'],j['meshes']):
            for op,np_ in zip(oldm['primitives'],newm['primitives']):
                for name,i in op['attributes'].items():assert np.array_equal(values(oldj,oldb,i),values(j,newb,np_['attributes'][name]))
                if 'indices'in op:assert np.array_equal(values(oldj,oldb,op['indices']),values(j,newb,np_['indices']))
    triangles=lambda x:sum(x['accessors'][p['indices']]['count']//3 for m in x['meshes'] for p in m['primitives'])
    assert triangles(before)==triangles(j)
    return {'asset':path.name,'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'outputSha256':hashlib.sha256(output).hexdigest(),
        'bytesBefore':old_bytes,'bytesAfter':len(output),'nodesBefore':len(before['nodes']),'nodesAfter':len(j['nodes']),
        'primitivesBefore':sum(len(m['primitives']) for m in before['meshes']),'primitivesAfter':sum(len(m['primitives']) for m in j['meshes']),
        'trianglesPreserved':triangles(j),'imagesBefore':len(before.get('images',[])),'imagesAfter':len(ims),
        'referencedRgbaBytesBefore':pixels_before*4,'referencedRgbaBytesAfter':pixels_after*4}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    assert a.input.resolve()!=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True)
    report=[optimize(f,a.output/f.name) for f in sorted(a.input.glob('*.glb'))]
    a.report.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
