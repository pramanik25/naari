"""Builds promotion/naari_kavach_promo_hi.mp4 from real app screenshots + Hindi narration.

Run:  python promotion/build_video.py
Needs: pip install imageio-ffmpeg pillow, and Microsoft Edge (used headless to render the slides).
"""
import html
import re
import subprocess
import urllib.request
from pathlib import Path

import imageio_ffmpeg
from PIL import Image

ROOT = Path(__file__).resolve().parent
SCREENS = ROOT / "screens"
AUDIO = ROOT / "audio"
WORK = ROOT / "work"
OUT = ROOT / "naari_kavach_promo_hi.mp4"
LOGO = ROOT.parent / "Naari_Kavach_Store_Icon_512.png"
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
EDGE = next(p for p in (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
) if Path(p).exists())

W, H, FPS = 1080, 1920, 30
STATUS_BAR = 64          # px cropped from the top of every screenshot (emulator status bar)
LEAD, TAIL = 0.2, 0.25 # silence around each narration line, seconds

CDN = "https://d8j0ntlcm91z4.cloudfront.net/user_3GwiEwRawR8YkYevMNR73nqE3Qe/hf_20261010_095010_"

# kind: "logo" (brand card), "phone" (one or two real screens), "card" (cropped real screen regions)
SCENES = [
    dict(kind="logo", job="0f85362e-2865-47ca-a357-76dbaa3124c3",
         head="अब आप अकेली नहीं हैं", sub="रात का सुनसान रास्ता, और मन में डर।\nपर अब आप अकेली नहीं हैं।"),
    dict(kind="phone", job="8c2c69ff-3cf2-404f-b157-c99fc516f3a7", shots=["10_sos_holding.png"],
         head="3 सेकंड SOS दबाइए", sub="नारी कवच में बस तीन सेकंड\nSOS बटन दबाइए।"),
    dict(kind="card", job="e278cb9f-f514-41dd-b95f-872706e9ded8", shots=["11_sos_active.png"],
         crops=[(444, 860), (1548, 1930)],
         head="अलर्ट · कॉल · सायरन", sub="आपके अपनों को लाइव लोकेशन के साथ\nअलर्ट, कॉल, और तेज़ सायरन।"),
    dict(kind="phone", job="ff07b90e-97b8-407c-af26-35a306504215", shots=["01_home_sos.png"],
         head="हिलाएँ · पावर ×3 · गुप्त शब्द", sub="बटन न दबा सकें? फ़ोन हिलाइए, पावर बटन\nदबाइए, या अपना गुप्त शब्द बोलिए।"),
    dict(kind="phone", job="8db2c962-0e7e-435b-ab25-8dc54597c73b", shots=["03_safety_kit.png"],
         head="सबूत अपने-आप सुरक्षित", sub="SOS के दौरान सबूत चुपचाप रिकॉर्ड होकर\nसबूत लॉकर में सुरक्षित रहते हैं।"),
    dict(kind="phone", job="1c06741d-79da-449b-9517-93b2140b8379", shots=["08_fake_call_ringing.png"],
         head="नकली कॉल", sub="असहज महसूस हो? नकली कॉल बजाइए,\nऔर आराम से वहाँ से निकल जाइए।"),
    dict(kind="phone", job="45c36f08-b593-4eae-812c-0178eb27a098", shots=["04_safety_tools.png"],
         head="कैब मोड · चेक-इन टाइमर", sub="कैब मोड और चेक-इन टाइमर, सफ़र में\nपरिवार को हर पल ख़बर देते हैं।"),
    dict(kind="phone", job="829112f5-c286-490f-adb1-485dedffd1ef", shots=["06_safe_places.png", "09_together.png"],
         head="सुरक्षित जगहें · सर्कल · कम्युनिटी", sub="आस-पास की सुरक्षित जगहें, परिवार का सर्कल,\nऔर कम्युनिटी, सब एक ही ऐप में।"),
    dict(kind="logo", job="bfac4181-071b-433c-af48-76aac92e9cd4", final=True,
         head="आपकी सुरक्षा, हमेशा साथ", sub="आज ही डाउनलोड करें"),
]

CSS = """
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:1080px;height:1920px;overflow:hidden}
body{font-family:'Nirmala UI','Noto Sans Devanagari',sans-serif;color:#fff;
 background:radial-gradient(900px 700px at 15% 8%,rgba(255,70,120,.38),transparent 70%),
            radial-gradient(900px 800px at 95% 95%,rgba(110,80,230,.42),transparent 70%),#0b0a12;
 display:flex;flex-direction:column;align-items:center}
.brand{margin-top:70px;display:flex;align-items:center;gap:18px;font-size:34px;letter-spacing:6px;color:#f3c66b;font-weight:600}
.brand img{width:56px;height:56px;border-radius:14px}
.head{margin-top:26px;font-size:74px;font-weight:700;text-align:center;line-height:1.25;padding:0 50px}
.stage{flex:1;width:100%;display:flex;align-items:center;justify-content:center;gap:36px}
.phone{border-radius:58px;padding:14px;background:#1c1a26;border:3px solid #3a3550;
 box-shadow:0 40px 110px rgba(255,70,120,.35),0 0 0 1px rgba(255,255,255,.06)}
.phone img{display:block;border-radius:46px}
.card{width:940px;border-radius:44px;overflow:hidden;border:3px solid #3a3550;
 box-shadow:0 40px 110px rgba(255,70,120,.35);margin:18px 0}
.card img{display:block;width:100%}
.cards{display:flex;flex-direction:column;align-items:center}
.sub{margin-bottom:96px;width:960px;padding:30px 36px;border-radius:36px;background:rgba(10,9,18,.78);
 border:2px solid rgba(255,255,255,.12);font-size:46px;line-height:1.45;text-align:center;white-space:pre-line}
.logo{width:420px;height:420px;border-radius:96px;box-shadow:0 40px 120px rgba(255,70,120,.5)}
.name{font-size:132px;font-weight:800;margin-top:54px}
.latin{font-size:44px;letter-spacing:12px;color:#f3c66b;margin-top:6px}
.tag{font-size:62px;font-weight:600;margin-top:44px;text-align:center;padding:0 60px}
.cta{margin-top:70px;padding:30px 70px;border-radius:999px;font-size:56px;font-weight:700;
 background:linear-gradient(90deg,#ff3f7f,#7a55e8)}
.center{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center}
"""


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed:\n{r.stderr[-1500:]}")
    return r


def audio_duration(path):
    r = subprocess.run([FFMPEG, "-i", str(path)], capture_output=True, text=True, errors="replace")
    h, m, s = re.search(r"Duration: (\d+):(\d+):([\d.]+)", r.stderr).groups()
    return int(h) * 3600 + int(m) * 60 + float(s)


def prep_shot(name, crop=None):
    """Drop the status bar (or cut a region) from a raw screenshot; returns the prepared file."""
    img = Image.open(SCREENS / name).convert("RGB")
    box = (0, crop[0], img.width, crop[1]) if crop else (0, STATUS_BAR, img.width, img.height)
    out = WORK / (f"{Path(name).stem}_{box[1]}_{box[3]}.png")
    img.crop(box).save(out)
    return out


def scene_html(sc):
    logo = LOGO.as_uri()
    if sc["kind"] == "logo":
        if sc.get("final"):
            body = (f'<div class="center"><img class="logo" src="{logo}"><div class="name">नारी कवच</div>'
                    f'<div class="latin">NAARI KAVACH</div><div class="tag">{html.escape(sc["head"])}</div>'
                    f'<div class="cta">{html.escape(sc["sub"])}</div></div>')
        else:
            body = (f'<div class="center"><img class="logo" src="{logo}"><div class="name">नारी कवच</div>'
                    f'<div class="latin">NAARI KAVACH</div></div><div class="sub">{html.escape(sc["sub"])}</div>')
        return f"<html><head><meta charset='utf-8'><style>{CSS}</style></head><body>{body}</body></html>"

    if sc["kind"] == "card":
        imgs = "".join(f'<div class="card"><img src="{prep_shot(sc["shots"][0], c).as_uri()}"></div>'
                       for c in sc["crops"])
        stage = f'<div class="stage"><div class="cards">{imgs}</div></div>'
    else:
        width = 590 if len(sc["shots"]) == 1 else 460
        imgs = "".join(f'<div class="phone"><img width="{width}" src="{prep_shot(s).as_uri()}"></div>'
                       for s in sc["shots"])
        stage = f'<div class="stage">{imgs}</div>'
    return (f"<html><head><meta charset='utf-8'><style>{CSS}</style></head><body>"
            f'<div class="brand"><img src="{logo}">NAARI KAVACH</div>'
            f'<div class="head">{html.escape(sc["head"])}</div>{stage}'
            f'<div class="sub">{html.escape(sc["sub"])}</div></body></html>')


def main():
    for d in (AUDIO, WORK):
        d.mkdir(exist_ok=True)
    clips = []
    for i, sc in enumerate(SCENES, 1):
        mp3 = AUDIO / f"line{i:02d}.mp3"
        if not mp3.exists():
            urllib.request.urlretrieve(f"{CDN}{sc['job']}.mp3", mp3)
        dur = audio_duration(mp3) + LEAD + TAIL
        frames = round(dur * FPS)

        page, png = WORK / f"scene{i:02d}.html", WORK / f"scene{i:02d}.png"
        page.write_text(scene_html(sc), encoding="utf-8")
        run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
             f"--window-size={W},{H}", f"--screenshot={png}", page.as_uri()])
        Image.open(png).convert("RGB").resize((W, H)).save(png)

        clip = WORK / f"scene{i:02d}.mp4"
        vf = (f"scale={W * 2}:{H * 2},zoompan=z='1+0.05*on/{frames}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
              f":d={frames}:s={W}x{H}:fps={FPS},fade=t=in:st=0:d=0.3,fade=t=out:st={dur - 0.3:.3f}:d=0.3,format=yuv420p")
        af = f"adelay={int(LEAD * 1000)}:all=1,apad,atrim=0:{dur:.3f},aresample=44100"
        run([FFMPEG, "-y", "-i", str(png), "-i", str(mp3), "-filter_complex", f"[0:v]{vf}[v];[1:a]{af}[a]",
             "-map", "[v]", "-map", "[a]", "-frames:v", str(frames), "-c:v", "libx264", "-preset", "medium",
             "-crf", "18", "-c:a", "aac", "-b:a", "192k", "-ac", "2", str(clip)])
        clips.append(clip)
        print(f"scene {i}: {dur:.2f}s")

    listing = WORK / "concat.txt"
    listing.write_text("".join(f"file '{c.as_posix()}'\n" for c in clips), encoding="utf-8")
    run([FFMPEG, "-y", "-f", "concat", "-safe", "0", "-i", str(listing), "-c:v", "libx264", "-preset", "medium",
         "-crf", "18", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(OUT)])
    print(f"done: {OUT} ({audio_duration(OUT):.1f}s)")


if __name__ == "__main__":
    main()
