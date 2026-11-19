import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

launcher = Path(sys.argv[1]).resolve()
root_id = "91:3"

def textbox(index, weight):
    return dict(id=f"91:{index}",type="TEXT",characters="Quartz",
                layoutSizingHorizontal="HUG", layoutSizingVertical="HUG",
                style=dict(fontFamily="sans-serif",fontSize=25,fontWeight=weight,
                           lineHeightUnit="PIXELS",lineHeightPx=34),
                fills=[dict(type="SOLID",color=dict(r=.12,g=.16,b=.21,a=1))])

def figma(weights):
    return {"version":"synthetic-medium-weight",
            "nodes":{root_id:{"document":dict(id=root_id,type="FRAME", layoutMode="VERTICAL",
                layoutSizingHorizontal="FIXED",layoutSizingVertical="FIXED",
                absoluteBoundingBox=dict(width=180,height=140),paddingLeft=12,paddingTop=6,
                itemSpacing=5,children=[textbox(i+4,w) for i,w in enumerate(weights)])}}}

def main():
    with tempfile.TemporaryDirectory() as directory:
        p = Path(directory)

        def invoke(name, value, *, normalized=False, expected=0, wanted=None, tool=launcher):
            input_path = p / "input.json"
            input_path.write_text(json.dumps(value))
            output = p / name
            args = [str(tool),"generate" if normalized else "generate-figma","--input",str(input_path)]
            if not normalized:
                args += ["--node",root_id]
            args += ["--out", str(output)]
            proc = subprocess.run(args,capture_output=True,text=True)
            diag = json.loads((output/"diagnostics.json").read_text())
            assert proc.returncode==expected and diag["ok"]==(expected==0),(name,proc.stdout,proc.stderr,diag)
            if wanted:
                assert wanted in str(diag),(name,diag)
            if expected:
                assert not (output/"manifest.json").exists() and not list(output.glob("*.kt"))
            return output

        accepted = invoke("accepted",figma([400,500,700]))
        repeat = invoke("repeat",figma([400,500,700]))
        assert {x.name:x.read_bytes() for x in accepted.iterdir()} == {x.name:x.read_bytes() for x in repeat.iterdir()}
        norm = json.loads((accepted/"normalized-frame.json").read_text())
        regular, medium, bold = norm["frame"]["children"]
        assert "medium" not in regular and "bold" not in regular
        assert medium["medium"] is True and "bold" not in medium
        assert bold["bold"] is True and "medium" not in bold
        lines = [line for line in next(accepted.glob("*.kt")).read_text().splitlines() if 'text = "Quartz"' in line]
        assert len(lines)==3 and "fontWeight" not in lines[0] and "FontWeight.Medium" in lines[1] and "FontWeight.Bold" in lines[2]
        direct = invoke("direct",norm,normalized=True)
        assert "FontWeight.Medium" in next(direct.glob("*.kt")).read_text()
        for index,value in enumerate((0,100,450,550,600,800,900,500.01,"500",True,None,{},[])):
            invoke(f"unsupported-{index}",figma([value]),expected=2,wanted="style.fontWeight")
        for index,value in enumerate(("true",1,None,[])):
            bad=copy.deepcopy(norm)
            bad["frame"]["children"][1]["medium"]=value
            invoke(f"bad-flag-{index}",bad,normalized=True,expected=2,wanted="Expected boolean")
        conflicting=copy.deepcopy(norm)
        conflicting["frame"]["children"][1]["bold"]=True
        invoke("conflict",conflicting,normalized=True,expected=2,wanted="Medium and bold")
        normalized_regular=copy.deepcopy(norm)
        normalized_regular["frame"]["children"][1]["medium"]=False
        read_regular=invoke("explicit-no-medium",normalized_regular,normalized=True)
        assert "FontWeight.Medium" not in next(read_regular.glob("*.kt")).read_text()
        # Optional local cross-version comparison and capture staging, never
        # required for the checked-in tests or dependent on a private input.
        if len(sys.argv)>2:
            baseline = Path(sys.argv[2]).resolve()
            invoke("baseline-reject-500",figma([500]),expected=2,wanted="style.fontWeight",tool=baseline)
            old = invoke("baseline-legacy",figma([400,700]),tool=baseline)
            new = invoke("new-legacy",figma([400,700]))
            assert {x.name:x.read_bytes() for x in old.iterdir()} == {x.name:x.read_bytes() for x in new.iterdir()}
            print("Prior baseline rejects 500; full 400/700 output bundle unchanged against baseline")
        if len(sys.argv)>3:
            import shutil
            shutil.copytree(accepted,Path(sys.argv[3]))
    print("Medium font: deterministic bundle, validated direct model, legacy cases and 18 rejection checks passed")

if __name__=="__main__":
    main()
