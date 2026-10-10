from pathlib import Path

from audio_recorder import record_audio
from offline_stt_func import transcribe_wav


BASE_DIR = Path("/home/piuser/savan")

RECORDINGS_DIR = BASE_DIR / "recordings"

AUDIO_FILE = RECORDINGS_DIR / "input.wav"


def main():

    print()
    print("========================================")
    print("       OFFLINE VOICE ASSISTANT")
    print("========================================")

    try:

        # ==========================================
        # 1. RECORD AUDIO
        # ==========================================

        record_audio(
            output_file=AUDIO_FILE,
            duration=5,
        )

        # ==========================================
        # 2. TRANSCRIBE
        # ==========================================

        print()
        print("Transcribing offline...")

        text = transcribe_wav(
            AUDIO_FILE
        )

        # ==========================================
        # 3. RESULT
        # ==========================================

        print()
        print("========================================")
        print("                RESULT")
        print("========================================")

        if text:

            print()
            print("You said:")
            print(text)

        else:

            print()
            print("No speech detected.")

        print()

    except Exception as error:

        print()
        print("========================================")
        print("                ERROR")
        print("========================================")

        print(error)

        print()


if __name__ == "__main__":
    main()
