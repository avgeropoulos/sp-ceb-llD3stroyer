"""Synthesizes the little 8-bit sound effects into sfx/*.wav (no audio files needed in git history)."""
import math, os, random, struct, wave

OUT = os.path.join(os.path.dirname(__file__), "..", "sfx")
os.makedirs(OUT, exist_ok=True)
RATE = 22050


def save(name, samples):
    with wave.open(os.path.join(OUT, name + ".wav"), "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 26000)) for s in samples))


def tone(f0, f1, dur, wave_kind="square", vol=0.5, duty=0.5, attack=0.004):
    n = int(dur * RATE); out = []; ph = 0.0
    for i in range(n):
        t = i / n
        f = f0 + (f1 - f0) * t
        ph += f / RATE
        x = ph % 1.0
        if wave_kind == "square":
            v = 1.0 if x < duty else -1.0
        elif wave_kind == "tri":
            v = 4 * abs(x - 0.5) - 1
        else:
            v = math.sin(ph * math.tau)
        env = min(1.0, i / (attack * RATE + 1)) * (1 - t) ** 1.5
        out.append(v * env * vol)
    return out


def noise(dur, vol=0.6, lowpass=0.5, seed=1):
    r = random.Random(seed); n = int(dur * RATE); out = []; last = 0.0
    for i in range(n):
        last = last + (r.uniform(-1, 1) - last) * lowpass
        out.append(last * vol * (1 - i / n) ** 2)
    return out


def seq(*parts):
    out = []
    for p in parts:
        out += p
    return out


def mix(a, b):
    n = max(len(a), len(b))
    return [(a[i] if i < len(a) else 0) + (b[i] if i < len(b) else 0) for i in range(n)]


save("jump", tone(320, 760, 0.16, duty=0.25, vol=0.35))
save("jump2", tone(500, 1100, 0.14, duty=0.25, vol=0.33))
save("coin", seq(tone(988, 988, 0.06, vol=0.3, duty=0.5), tone(1319, 1319, 0.22, vol=0.3)))
save("stomp", mix(tone(400, 120, 0.12, "tri", 0.6), noise(0.08, 0.4)))
save("break", mix(noise(0.25, 0.7, 0.35, 2), tone(180, 60, 0.2, "tri", 0.5)))
save("mine", mix(noise(0.07, 0.5, 0.6, 3), tone(700, 500, 0.05, "square", 0.2, 0.12)))
save("bonk", tone(220, 140, 0.1, "square", 0.4, 0.5))
save("hurt", seq(tone(600, 300, 0.12, vol=0.35), tone(400, 150, 0.2, vol=0.35)))
save("zap", mix(tone(1400, 300, 0.18, "square", 0.25, 0.15), noise(0.1, 0.25, 0.9, 4)))
save("swap", seq(tone(523, 523, 0.06, vol=0.25), tone(784, 784, 0.06, vol=0.25), tone(1047, 1047, 0.12, vol=0.25)))
save("slam", mix(tone(200, 50, 0.35, "tri", 0.8), noise(0.3, 0.7, 0.25, 5)))
save("thunder", mix(noise(1.2, 0.9, 0.15, 6), tone(90, 40, 1.0, "tri", 0.6)))
save("boss_hit", seq(tone(300, 900, 0.08, vol=0.35), tone(900, 200, 0.18, vol=0.35)))
save("arrow", tone(1800, 900, 0.1, "square", 0.15, 0.1))
save("item", seq(*[tone(f, f, 0.07, vol=0.28) for f in (523, 659, 784, 1047, 1319)]))
save("heart", seq(tone(784, 784, 0.08, vol=0.3), tone(1047, 1047, 0.08, vol=0.3), tone(1568, 1568, 0.18, vol=0.3)))
save("woof", seq(tone(500, 280, 0.09, "tri", 0.7), [0] * 1500, tone(520, 260, 0.12, "tri", 0.7)))
save("alert", seq(tone(1200, 1200, 0.06, vol=0.25), [0] * 800, tone(1200, 1200, 0.06, vol=0.25)))
save("clear", seq(*[tone(f, f, d, vol=0.3) for f, d in ((523, .12), (659, .12), (784, .12), (1047, .24), (784, .12), (1047, .5))]))
save("boss_down", seq(*[tone(f, f * 0.7, 0.12, vol=0.35) for f in (800, 700, 600, 500, 400, 300)], noise(0.6, 0.8, 0.2, 7)))
print("sfx written")
