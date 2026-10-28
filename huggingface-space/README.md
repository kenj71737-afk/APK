---
title: ALAD Speech Music Separation API
emoji: 🎙️
colorFrom: blue
colorTo: purple
sdk: docker
app_port: 7860
pinned: false
---

# ALAD Speech / Music Separation API

Deploy this folder as a Hugging Face **Docker Space**. It runs the real Demucs `htdemucs` model and exposes:

- `GET /health`
- `POST /separate` (multipart form field named `file`; audio file upload)

`/separate` returns JSON with base64 WAV audio for `vocals_wav_base64` and `instrumental_wav_base64`.

## Dockerfile

The Docker Space must have a `Dockerfile` in the Space root. Use the supplied Dockerfile. CPU inference can take tens of seconds or longer, may time out, and free Spaces can sleep. This is suitable for testing / buffered clips, not guaranteed real-time live dubbing.

## Security and cost

Do not commit personal tokens. Public Spaces can be called by others and have usage limits. Review Hugging Face's current hardware and usage terms before enabling public traffic.
