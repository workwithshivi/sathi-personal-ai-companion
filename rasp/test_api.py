
from api_client import upload_transcript, ask_question

meeting_id = "sathi-office-demo-20261009"

# Test transcript upload
try:
    result = upload_transcript(
        meeting_id,
        ["The LED is connected to GPIO 4.","Temp in Noida is 30 degree"]
    )
    print("Transcript API response:", result)
except Exception as exc:
    print("Transcript upload failed:", exc)

# Test Q&A
try:
    result = ask_question(
        meeting_id,
        "What's the temp in noida?"
    )
    print("Q&A API response:", result)
except Exception as exc:
    print("Q&A request failed:", exc)
