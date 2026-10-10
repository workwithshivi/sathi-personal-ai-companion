from database import init_db, save_transcription

init_db()

save_transcription(
    "I am building a Raspberry Pi voice assistant"
)

save_transcription(
    "My LED is connected to GPIO 4"
)

save_transcription(
    "I am using OnePlus Nord Buds 2"
)

print("Test data inserted.")
