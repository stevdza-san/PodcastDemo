"""Generate the original, offline 60-second demo fixture using only Python's stdlib."""

from array import array
from math import pi, sin
from pathlib import Path
import sys
import wave

SAMPLE_RATE = 22_050
DURATION_SECONDS = 60
NOTES = (261.63, 329.63, 392.00, 523.25, 440.00, 349.23, 293.66, 392.00)

output = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/demo_episode.wav"
output.parent.mkdir(parents=True, exist_ok=True)
samples = array("h")
for index in range(SAMPLE_RATE * DURATION_SECONDS):
    time = index / SAMPLE_RATE
    second = int(time)
    within_note = time - second
    # A gentle chime each second; the melody changes over time to make resume audible.
    envelope = max(0.0, min(within_note / 0.025, (0.8 - within_note) / 0.25, 1.0))
    frequency = NOTES[second % len(NOTES)] * (1.0 if second < 30 else 0.75)
    sample = sin(2 * pi * frequency * time) + 0.25 * sin(4 * pi * frequency * time)
    samples.append(round(5_000 * envelope * sample))

if sys.byteorder != "little":
    samples.byteswap()
with wave.open(str(output), "wb") as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(SAMPLE_RATE)
    audio.writeframes(samples.tobytes())
print(f"Generated {output.name}: {DURATION_SECONDS}s, mono PCM16, {SAMPLE_RATE} Hz")
