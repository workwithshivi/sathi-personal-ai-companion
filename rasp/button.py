import os
import subprocess
import sys
from pathlib import Path

os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")

from gpiozero import Button


BUTTON_PIN = int(os.environ.get("SATHI_BUTTON_GPIO", "22"))
SCRIPT_DIR = Path(__file__).resolve().parent
ASK_MEMORY_SCRIPT = SCRIPT_DIR / "ask_memory.py"


def main():
    try:
        print(f"Waiting for button on BCM GPIO {BUTTON_PIN}.", flush=True)

        while True:
            print("test 1")
            button = Button(
                BUTTON_PIN,
                pull_up=True,
                bounce_time=0.1,
            )
            print("test 2")
            try:
                print("test 3")
                button.wait_for_press()
                button.wait_for_release()
                print("test 4")
            finally:
                button.close()
                print("test 5")
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