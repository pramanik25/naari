from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "Naari_Kavach_Store_Icon_512.png"
SIZE = 512
SCALE = 4
N = SIZE * SCALE

# Match the diagonal rose-to-violet gradient in ic_launcher_background.xml.
stops = [
    (0.0, (255, 77, 126)),
    (0.55, (142, 42, 140)),
    (1.0, (59, 47, 168)),
]
background = Image.new("RGB", (N, N))
pixels = background.load()
for y in range(N):
    for x in range(N):
        t = (x + y) / (2 * (N - 1))
        for i in range(len(stops) - 1):
            if stops[i][0] <= t <= stops[i + 1][0]:
                a, ca = stops[i]
                b, cb = stops[i + 1]
                u = (t - a) / (b - a)
                pixels[x, y] = tuple(round(ca[k] + (cb[k] - ca[k]) * u) for k in range(3))
                break

# Recreate the white shield and heart-shaped cutout from ic_launcher_foreground.xml.
def pt(x, y):
    return (round(x * SIZE / 108 * SCALE), round(y * SIZE / 108 * SCALE))

mask = Image.new("L", (N, N), 0)
draw = ImageDraw.Draw(mask)
shield = [(54,29),(75,36.5),(75,52),(74,58),(72,63),(69,67),(65,71),(60,74),(54,80),
          (48,78),(43,75),(39,72),(36,68),(34,64),(33,59),(33,52),(33,36.5)]
draw.polygon([pt(x,y) for x,y in shield], fill=255)

def cubic(p0, p1, p2, p3, steps=24):
    points=[]
    for i in range(steps + 1):
        t=i/steps; q=1-t
        x=q**3*p0[0]+3*q*q*t*p1[0]+3*q*t*t*p2[0]+t**3*p3[0]
        y=q**3*p0[1]+3*q*q*t*p1[1]+3*q*t*t*p2[1]+t**3*p3[1]
        points.append((x,y))
    return points

heart=[]
heart += cubic((54,63.5),(54,63.5),(43.5,56.8),(43.5,50.3))[:-1]
heart += cubic((43.5,50.3),(43.5,47),(46,44.6),(49,44.6))[:-1]
heart += cubic((49,44.6),(51.1,44.6),(53,45.9),(54,47.8))[:-1]
heart += cubic((54,47.8),(55,45.9),(56.9,44.6),(59,44.6))[:-1]
heart += cubic((59,44.6),(62,44.6),(64.5,47),(64.5,50.3))[:-1]
heart += cubic((64.5,50.3),(64.5,56.8),(54,63.5),(54,63.5))
draw.polygon([pt(x,y) for x,y in heart], fill=0)

icon = Image.new("RGB", (N,N), (0,0,0))
icon.paste(background, (0,0))
white = Image.new("RGB", (N,N), (255,255,255))
icon.paste(white, (0,0), mask)
icon.resize((SIZE,SIZE), Image.Resampling.LANCZOS).save(OUTPUT, "PNG", optimize=True)
print(OUTPUT)
