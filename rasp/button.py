import os
import subprocess
import sys
from pathlib import Path

os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")

from gpiozero import Button


BUTTON_PIN = int(os.environ.get("SATHI_BUTTON_GPIO", "22"))
BUTTON_PULL_UP = os.environ.get("SATHI_BUTTON_PULL_UP", "true").lower() == "true"
SCRIPT_DIR = Path(__file__).resolve().parent
ASK_MEMORY_SCRIPT = SCRIPT_DIR / "ask_memory.py"


def main():
    try:
        print(f"Waiting for button on BCM GPIO {BUTTON_PIN}.", flush=True)

        while True:
            button = Button(
                BUTTON_PIN,
                pull_up=BUTTON_PULL_UP,
                bounce_time=0.1,
            )
            try:
                print(
                    f"Button ready: BCM {BUTTON_PIN}, "
                    f"pull_up={BUTTON_PULL_UP}, "
                    f"initial_pressed={button.is_pressed}.",
                    flush=True,
                )
                button.wait_for_press()
                print("Button press detected; release it to start Q&A.", flush=True)
                button.wait_for_release()
            finally:
                button.close()
            print("Starting voice Q&A.", flush=True)
            subprocess.run(
                [sys.executable, str(ASK_MEMORY_SCRIPT)],
                cwd=SCRIPT_DIR,
                check=False,
            )

            print("Q&A finished. Waiting for button.", flush=True)
    except KeyboardInterrupt:
        print("\nButton service stopped.", flush=True)


if __name__ == "__main__":
    main()