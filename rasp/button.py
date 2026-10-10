import RPi.GPIO as GPIO
import time

GPIO.setmode(GPIO.BCM)
GPIO.setup(22, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)

print("Waiting for button press...")

try:
    while True:
        if GPIO.input(22) == GPIO.HIGH:
            print("Button pressed!")
            time.sleep(0.3)  # debounce delay
except KeyboardInterrupt:
    GPIO.cleanup()
