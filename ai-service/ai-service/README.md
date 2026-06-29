# CodeInsight Local AI Insight Service

This replaces the Gemini API call in `AIInsightServlet.java` with a
locally-running open-source model via [Ollama](https://ollama.com) —
no internet connection, no API key, no per-request cost.

## 1. Install Ollama (Windows)

Download and run the installer from https://ollama.com/download/windows.
After installing, Ollama runs automatically in the background and listens
on `http://localhost:11434`.

## 2. Pull the model

Open Command Prompt / PowerShell and run:

```
ollama pull qwen2.5-coder:3b
```

This downloads a small (~2GB) model specialized for code, well-suited to
machines with 8GB RAM and no dedicated GPU. (Want better quality and have
more RAM/a GPU? You can swap in a larger model — see "Changing the model"
below.)

Verify it works:

```
ollama run qwen2.5-coder:3b "Explain what a HashMap is in one sentence"
```

## 3. Install Python dependencies

From this folder (`ai-service/`):

```
pip install -r requirements.txt
```

## 4. Run the service

```
python app.py
```

You should see Flask start on port 8001. Leave this running alongside your
Java backend (port 8080) and frontend (port 5173).

Check it's healthy:

```
curl http://localhost:8001/health
```

## 5. Point the Java backend at it

By default, `AIInsightServlet.java` calls `http://localhost:8001/insight`.
If you want to use a different host/port, set an environment variable
before starting the backend:

```
set AI_INSIGHT_URL=http://localhost:8001/insight
```

## Changing the model

Edit `OLLAMA_MODEL` in `app.py`, or set an environment variable instead of
editing code:

```
set OLLAMA_MODEL=qwen2.5-coder:7b
```

then `ollama pull qwen2.5-coder:7b` first. Bigger models give better
explanations but need more RAM and respond more slowly — only go bigger if
your machine has a GPU or 16GB+ RAM and you find 3b's answers too shallow.

## Troubleshooting

- **"Cannot reach Ollama"** — Ollama isn't running. Open it from the Start
  Menu, or run `ollama serve` in a terminal.
- **Very slow responses (30s+)** — normal on CPU-only laptops for the first
  request (model loads into memory). Later requests are faster. If every
  request is slow, try a smaller model.
- **"Model did not return valid JSON"** — small models occasionally drift
  from the requested format. The service already strips ```json fences and
  extracts the first {...} block; if this still happens often, consider
  qwen2.5-coder:7b, which follows formatting instructions more reliably.
