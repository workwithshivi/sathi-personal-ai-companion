
from pathlib import Path

import numpy as np
import soundfile as sf
import sherpa_onnx

BASE_DIR = Path("/home/piuser/sathi-personal-ai-companion/rasp")
MODEL_DIR = BASE_DIR / "models" / "hi-hinglish-swift"

SAMPLE_RATE = 48000

# Load once and reuse for every audio chunk.
recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(MODEL_DIR / "encoder.int8.onnx"),
    decoder=str(MODEL_DIR / "decoder.int8.onnx"),
    tokens=str(MODEL_DIR / "tokens.txt"),
    num_threads=2,
    decoding_method="greedy_search",
    language="hi",
    task="transcribe",
)


def transcribe_wav(audio_path):
    audio, sample_rate = sf.read(
        str(audio_path),
        dtype="float32",
        always_2d=False,
    )

    if audio.ndim == 2:
        audio = audio.mean(axis=1)

    audio = np.asarray(audio, dtype=np.float32)

    if sample_rate != SAMPLE_RATE:
        raise ValueError(
            f"Expected 16000 Hz audio, received {sample_rate} Hz"
        )

    if audio.size == 0:
        return ""

    # Ignore near-silent chunks.
    if np.max(np.abs(audio)) < 0.005:
        return ""

    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, audio)
    recognizer.decode_stream(stream)

    return stream.result.text.strip()


if __name__ == "__main__":
    sample = BASE_DIR / "test.wav"

    if not sample.exists():
        raise FileNotFoundError(
            f"Put a 16 kHz mono test recording at {sample}"
        )

    print("Transcribing offline...")
    print("Result:", transcribe_wav(sample))
