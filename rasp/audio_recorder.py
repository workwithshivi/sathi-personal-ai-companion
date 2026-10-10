import subprocess
from pathlib import Path


SAMPLE_RATE = 16000
CHANNELS = 1


def record_audio(
    output_file,
    duration=5
):

    output_file = Path(output_file)

    output_file.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    print()
    print("================================")
    print("          RECORDING")
    print("================================")
    print(f"Duration: {duration} seconds")
    print("Speak now...")
    print()

    command = [
        "arecord",
        "-d",
        str(duration),
        "-f",
        "S16_LE",
        "-r",
        str(SAMPLE_RATE),
        "-c",
        str(CHANNELS),
        str(output_file),
    ]

    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    if result.returncode != 0:
        print("Recording failed:")
        print(result.stderr)

        raise RuntimeError(
            "arecord failed"
        )

    print("Recording complete.")
    print(f"Audio saved to: {output_file}")

    return output_file
