from pathlib import Path

import numpy as np
import soundfile as sf
import sherpa_onnx


# ============================================================
# CONFIGURATION
# ============================================================

BASE_DIR = Path("/home/piuser/sathi-personal-ai-companion/rasp")

MODEL_DIR = BASE_DIR / "models" / "hi-hinglish-swift"

SAMPLE_RATE = 16000


# ============================================================
# LOAD MODEL
# ============================================================

print("Loading offline STT model...")

recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=str(MODEL_DIR / "encoder.int8.onnx"),
    decoder=str(MODEL_DIR / "decoder.int8.onnx"),
    tokens=str(MODEL_DIR / "tokens.txt"),
    num_threads=2,
    decoding_method="greedy_search",
    language="hi",
    task="transcribe",
)

print("STT model loaded.")


# ============================================================
# TRANSCRIBE
# ============================================================

def transcribe_wav(audio_path):

    audio_path = Path(audio_path)

    if not audio_path.exists():
        raise FileNotFoundError(
            f"Audio file not found: {audio_path}"
        )

    audio, sample_rate = sf.read(
        str(audio_path),
        dtype="float32",
        always_2d=False,
    )

    print(f"Audio sample rate: {sample_rate} Hz")

    # Stereo -> mono
    if audio.ndim == 2:
        audio = audio.mean(axis=1)

    audio = np.asarray(
        audio,
        dtype=np.float32
    )

    # Convert explicitly to int for comparison
    sample_rate = int(sample_rate)

    if sample_rate != 16000:
        raise ValueError(
            f"Expected 16000 Hz audio, "
            f"received {sample_rate} Hz"
        )

    if audio.size == 0:
        return ""

    # Ignore silence
    max_amplitude = np.max(np.abs(audio))

    if max_amplitude < 0.005:
        return ""

    print(f"Audio samples: {len(audio)}")
    print(f"Max amplitude: {max_amplitude:.4f}")

    # ========================================================
    # SHERPA ONNX
    # ========================================================

    stream = recognizer.create_stream()

    stream.accept_waveform(
        16000,
        audio
    )

    recognizer.decode_stream(stream)

    result = stream.result.text.strip()

    return result


# ============================================================
# TEST
# ============================================================

if __name__ == "__main__":

    sample = BASE_DIR / "test.wav"

    if not sample.exists():
        raise FileNotFoundError(
            f"Put a 16 kHz mono test recording at {sample}"
        )

    print("Transcribing offline...")

    result = transcribe_wav(sample)

    print("Result:", result)
