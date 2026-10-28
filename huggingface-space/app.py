"""Hugging Face Space API for speech/music separation.

POST /separate with multipart field `file` (WAV/MP3/etc). Returns JSON containing
base64 WAVs for `vocals` and `instrumental`. This is a real Demucs inference
endpoint, not a mock. CPU Spaces may be slow; not intended for sub-second live use.
"""
import base64, os, subprocess, tempfile, uuid, shutil
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse

app = FastAPI(title="ALAD audio separation API", version="1.0")
MODEL = os.getenv("DEMUCS_MODEL", "htdemucs")

@app.get("/health")
def health():
    return {"ok": True, "model": MODEL, "engine": "demucs", "note": "CPU inference can be slow"}

@app.post("/separate")
async def separate(file: UploadFile = File(...)):
    allowed = {"audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3", "audio/flac", "application/octet-stream"}
    if file.content_type and file.content_type not in allowed:
        raise HTTPException(415, f"Unsupported audio content type: {file.content_type}")
    work = Path(tempfile.mkdtemp(prefix="alad_sep_"))
    try:
        suffix = Path(file.filename or "input.wav").suffix or ".wav"
        source = work / ("input" + suffix)
        source.write_bytes(await file.read())
        # Demucs outputs separated stems under <out>/<model>/<track-name>/
        out = work / "out"
        cmd = ["python", "-m", "demucs", "-n", MODEL, "--two-stems", "vocals", "-o", str(out), str(source)]
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=240)
        if proc.returncode != 0:
            raise HTTPException(500, "Demucs inference failed: " + proc.stderr.decode("utf-8", "ignore")[-1200:])
        candidates = list(out.rglob("vocals.wav"))
        if not candidates:
            raise HTTPException(500, "Demucs did not produce vocals.wav")
        vocals_path = candidates[0]
        instrumental_path = vocals_path.with_name("no_vocals.wav")
        if not instrumental_path.exists():
            raise HTTPException(500, "Demucs did not produce no_vocals.wav")
        return JSONResponse({
            "ok": True,
            "model": MODEL,
            "vocals_wav_base64": base64.b64encode(vocals_path.read_bytes()).decode("ascii"),
            "instrumental_wav_base64": base64.b64encode(instrumental_path.read_bytes()).decode("ascii"),
        })
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "Separation timed out; use a faster Space or shorter audio segment")
    finally:
        shutil.rmtree(work, ignore_errors=True)
