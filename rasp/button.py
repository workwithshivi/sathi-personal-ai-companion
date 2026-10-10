import os
import subprocess
import sys
import time
from pathlib import Path

os.environ.setdefault("GPIOZERO_PIN_FACTORY", "lgpio")

from gpiozero import Button, LED


BUTTON_PIN = int(os.environ.get("SATHI_BUTTON_GPIO", "22"))
BUTTON_PULL_UP = os.environ.get("SATHI_BUTTON_PULL_UP", "true").lower() == "true"
LED_PIN = int(os.environ.get("SATHI_LED_GPIO", "23"))
LED_ACTIVE_HIGH = os.environ.get("SATHI_LED_ACTIVE_HIGH", "true").lower() == "true"
SCRIPT_DIR = Path(__file__).resolve().parent
ASK_MEMORY_SCRIPT = SCRIPT_DIR / "ask_memory.py"


def main():
    led = LED(LED_PIN, active_high=LED_ACTIVE_HIGH)

    try:
        print(
            f"Waiting for button on BCM GPIO {BUTTON_PIN}; "
            f"session LED on BCM GPIO {LED_PIN}, "
            f"active_high={LED_ACTIVE_HIGH}.",
            flush=True,
        )
        led.on()
        time.sleep(0.5)
        led.off()

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
                while not button.is_pressed:
                    time.sleep(0.02)
                led.on()
                print("Button pressed; starting recording.", flush=True)
            finally:
                button.close()
            print("Starting voice question capture.", flush=True)
            led.on()
            try:
                subprocess.run(
                    [sys.executable, str(ASK_MEMORY_SCRIPT)],
                    cwd=SCRIPT_DIR,
                    check=False,
                )
            finally:
                led.off()

            print("Q&A finished. Waiting for button.", flush=True)
    except KeyboardInterrupt:
        print("\nButton service stopped.", flush=True)
    finally:
        led.off()
        led.close()


if __name__ == "__main__":
    main()