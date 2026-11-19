#!/usr/bin/env python3
"""Strict opt-in contract for one controlled SDK filter tab."""
import json
import math
import re

def check(ok,message):
    if not ok: raise ValueError(message)
def read(p, sidecar=False):
    check(p.is_file() and not p.is_symlink(), "Missing or linked component JSON")
    if sidecar: check(1 <= p.stat().st_size <= 4096, "Invalid binding sidecar size")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            check(key not in result, "Duplicate component JSON key")
            result[key] = value
        return result
    def reject_constant(value):
        raise ValueError("Non-finite component JSON")
    try:
        value = json.loads(p.read_text(encoding="utf-8"), object_pairs_hook=unique, parse_constant=reject_constant)
    except RecursionError as error:
        raise ValueError("Component JSON too deep") from error
    def walk(item, depth=0):
        check(depth <= 128, "Component JSON too deep")
        if isinstance(item, str): item.encode("utf-8", errors="strict")
        elif isinstance(item, (int,float)) and not isinstance(item,bool):
            try: finite = math.isfinite(float(item))
            except OverflowError: finite = False
            check(finite, "Non-finite component number")
        elif isinstance(item, dict):
            for key, child in item.items():
                key.encode("utf-8", errors="strict")
                walk(child, depth+1)
        elif isinstance(item,list):
            for child in item: walk(child, depth+1)
    walk(value)
    check(isinstance(value,dict), "Component JSON root must be object")
    return value
def validate(value, side, node):
    try:
        return _validate(value, side, node)
    except (KeyError, IndexError, TypeError, AttributeError) as error:
        raise ValueError("Malformed component binding structure") from error


def _validate(value,side,node):
    check(set(side)=={'schemaVersion','binding'} and type(side['schemaVersion']) is int and side['schemaVersion']==1,'sidecar schema')
    b=side['binding'];check(isinstance(b,dict) and set(b)=={'instanceId','componentKey','labelProperty','enabledProperty','labelNodeId'},'binding fields')
    check(all(isinstance(v,str) and 1<=len(v.encode("utf-16-le"))//2<=128 for v in b.values()),'binding strings')
    check(re.fullmatch(r'[A-Za-z0-9_-]+',b['componentKey']),'component key')
    check(b['labelProperty']!=b['enabledProperty'],'colliding property keys')
    entry=value['nodes'][node];doc=entry['document']
    check('components' not in value,'ambiguous/out-of-selection component metadata')
    check(doc['type']=='FRAME' and doc['layoutMode']=='HORIZONTAL','horizontal root required')
    check(len(doc['children'])==1,'initial slice supports one direct child')
    inst=doc['children'][0];id=b['instanceId']
    check(inst['id']==id and inst['type']=='INSTANCE','claimed instance missing')
    allowed={'id','type','name','componentId','componentProperties','layoutMode','layoutWrap','layoutSizingHorizontal','layoutSizingVertical','absoluteBoundingBox','itemSpacing','primaryAxisAlignItems','counterAxisAlignItems','paddingLeft','paddingRight','paddingTop','paddingBottom','fills','children','cornerRadius','rectangleCornerRadii','clipsContent'}
    check(set(inst)<=allowed,'unsupported claimed instance appearance/action/variant')
    check(inst['layoutMode']=='HORIZONTAL' and inst['layoutSizingHorizontal']==inst['layoutSizingVertical']=='FIXED','fixed horizontal slot required')
    cid=inst['componentId'];components=entry['components']
    check(isinstance(components,dict) and all(isinstance(c,dict) for c in components.values()) and cid in components,'missing authoritative component')
    check(components[cid].get('key')==b['componentKey'],'authoritative component key mismatch')
    check(sum(c.get('key')==b['componentKey'] for c in components.values())==1,'ambiguous authoritative key')
    props=inst['componentProperties'];check(set(props)=={b['labelProperty'],b['enabledProperty']},'unknown/changed property key')
    label=props[b['labelProperty']];enabled=props[b['enabledProperty']]
    check(set(label)=={'type','value'} and label['type']=='TEXT' and isinstance(label['value'],str),'Label must be typed TEXT')
    check(set(enabled)=={'type','value'} and enabled['type']=='BOOLEAN' and type(enabled['value']) is bool,'Enabled must be typed BOOLEAN')
    check(len(inst['children'])==1,'complete single text slot required')
    text=inst['children'][0]
    check(set(text)<={'id','name','type','characters','style','fills','layoutSizingHorizontal','layoutSizingVertical'},'unsupported slot field')
    check(text['id']==b['labelNodeId'] and text['type']=='TEXT' and text['characters']==label['value'],'TEXT property and rendered slot disagree')
    check(set(text['style'])<={'fontFamily','fontWeight','fontSize','textAutoResize','lineHeightPx','lineHeightUnit','letterSpacing'},'unsupported slot typography')
    return dict(instanceId=id,componentId=cid,componentKey=b['componentKey'],labelProperty=b['labelProperty'],enabledProperty=b['enabledProperty'],labelNodeId=b['labelNodeId'],enabled=enabled['value'])

