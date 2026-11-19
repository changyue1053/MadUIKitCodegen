import copy
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zlib

root_id, owner_id, frame_id = "81:1", "81:2", "I81:2;82:17"
vector_ids = [f"{frame_id};82:{i}" for i in (18, 19, 20)]
width, height = 16, 12
fill = {"type": "SOLID", "color": {"r": 0, "g": .5, "b": .75, "a": 1}}
rectangles = ((1, 1, 4, 8), (6, 3, 4, 8), (11, 1, 4, 8))

def bbox(x, y, w, h):
    return dict(x=x, y=y, width=w, height=h)

def layer(id, type, w, h, mode, children):
    return dict(id=id, type=type, layoutMode=mode,
                layoutSizingHorizontal="FIXED", layoutSizingVertical="FIXED",
                absoluteBoundingBox=bbox(0, 0, w, h), children=children)

def png_chunk(key, payload):
    prefix = key + payload
    return struct.pack(">I", len(payload)) + prefix + struct.pack(">I", zlib.crc32(prefix))

def payload():
    scanlines = b"".join(b"\0" + b"".join(
        bytes((0, 128, 191, 255)) if any(a <= x < a+w and b <= y < b+h for a,b,w,h in rectangles)
        else bytes(4) for x in range(width)) for y in range(height))
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width,height,8,6,0,0,0))
            + png_chunk(b"IDAT", zlib.compress(scanlines)) + png_chunk(b"IEND", b""))

def main():
    launcher = Path(sys.argv[1]).resolve()
    leaf = [dict(id=id,type="VECTOR",absoluteBoundingBox=bbox(*r),fills=[fill])
            for id,r in zip(vector_ids, rectangles)]
    frame = layer(frame_id, "FRAME", width, height, "NONE", leaf)
    owner = layer(owner_id, "INSTANCE", width, height, "HORIZONTAL", [frame])
    owner["componentId"] = "82:1"
    doc = layer(root_id, "FRAME", 40, 24, "VERTICAL", [owner])
    png = payload()
    asset = dict(schemaVersion=2,nodeId=frame_id,framePath=[root_id,owner_id,frame_id],
                 vectorIds=vector_ids,file="mad_icon_synthetic_bars.png",sha256=hashlib.sha256(png).hexdigest())

    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp)
        (p/asset["file"]).write_bytes(png)
        def invoke(name, mutate=lambda d,o,f,v,m:None, *, ok=False, flatten=True, wanted=None):
            d,m = copy.deepcopy((doc,asset))
            o=d["children"][0]
            f=o["children"][0]
            v=f["children"][0]
            mutate(d,o,f,v,m)
            (p/"data.json").write_text(json.dumps({"version":"synthetic-bars","nodes":{root_id:{"document":d}}}))
            (p/"map.json").write_text(json.dumps(m))
            out=p/name
            cmd=[str(launcher),"generate-figma","--input",str(p/"data.json"),"--node",root_id,"--out",str(out),
                 "--asset-map",str(p/"map.json")]
            if flatten:
                cmd.append("--flatten-instances")
            r=subprocess.run(cmd, capture_output=True,text=True)
            diag=json.loads((out/"diagnostics.json").read_text())
            assert (r.returncode==0) == ok, (name, r.stdout,r.stderr,diag)
            assert diag["ok"]==ok, (name, diag)
            if wanted:
                assert wanted.lower() in str(diag).lower(), (name,diag)
            if not ok:
                assert not (out/"manifest.json").exists()
                assert not list(out.rglob("*.png")) and not list(out.rglob("*.kt"))
            return out
        one=invoke("good",ok=True)
        two=invoke("repeat",ok=True)
        assert {a.relative_to(one):a.read_bytes() for a in one.rglob("*") if a.is_file()} == {
            b.relative_to(two):b.read_bytes() for b in two.rglob("*") if b.is_file()}
        code=next(one.glob("*.kt")).read_text()
        assert code.count("painterResource(")==1 and "contentDescription = null" in code, code
        assert next(one.rglob("*.png")).read_bytes()==png
        assert json.loads((one/"manifest.json").read_text())["decorativeAsset"]["nodeId"]==frame_id
        invoke("no-opt-in", flatten=False)
        invoke("foreign-owner",lambda d,o,f,v,m:(v.update(id="I81:99;82:18"),m["vectorIds"].__setitem__(0,"I81:99;82:18")),wanted="actual expanded numeric INSTANCE")
        invoke("wrong-path",lambda d,o,f,v,m:m["framePath"].insert(1,"81:40"),wanted="exact declared ancestry")
        invoke("no-instance",lambda d,o,f,v,m:o.update(type="FRAME"),wanted="expanded INSTANCE")
        invoke("root-interaction",lambda d,o,f,v,m:d.update(interactions=[{}]),wanted="interactive ancestors")
        invoke("owner-interaction",lambda d,o,f,v,m:o.update(reactions=[{}]),wanted="interactive ancestors")
        invoke("leaf-interaction",lambda d,o,f,v,m:v.update(reactions=[{}]),wanted="unsupported atomic VECTOR feature")
        invoke("unknown-leaf-field",lambda d,o,f,v,m:v.update(segmentProperties=[]),wanted="unsupported atomic VECTOR field")
        invoke("extra-leaf",lambda d,o,f,v,m:f["children"].append({**v,"id":"81:5"}),wanted="exact declared")
        invoke("reordered-leaves",lambda d,o,f,v,m:f["children"].reverse(),wanted="order")
        invoke("outside-frame",lambda d,o,f,v,m:v["absoluteBoundingBox"].update(x=100),wanted="inside")
        invoke("wrong-dimensions",lambda d,o,f,v,m:f["absoluteBoundingBox"].update(width=17),wanted="match the PNG")
        invoke("hidden-leaf",lambda d,o,f,v,m:v.update(visible=False),wanted="visible direct VECTOR")
        invoke("not-vector",lambda d,o,f,v,m:v.update(type="TEXT"),wanted="visible direct VECTOR")
        invoke("clip",lambda d,o,f,v,m:f.update(clipsContent=True),wanted="cannot clip")
        invoke("masked",lambda d,o,f,v,m:v.update(isMask=True),wanted="unmasked")
        invoke("duplicates",lambda d,o,f,v,m:d["children"].append(dict(id=vector_ids[0],visible=False)),wanted="exactly once")
        if len(sys.argv)>2:
            import shutil
            shutil.copytree(one, Path(sys.argv[2]))
    print("Atomic decorative frame: deterministic bundle and 17 rejection scenarios passed")

if __name__=="__main__":
    main()
