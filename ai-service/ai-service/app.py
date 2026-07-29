"""
CodeInsight Local AI Insight Service
-------------------------------------
Flask service that replaces the Gemini API call in AIInsightServlet.java
with a locally-running Ollama model (no internet, no API key, no cost).

Run:
    pip install -r requirements.txt
    ollama pull qwen2.5-coder:3b      (one-time, see README)
    ollama serve                       (if not already running as a service)
    python app.py

The Java backend talks to this service at:
    http://localhost:8001/insight
(configurable in AIInsightServlet.java via the AI_INSIGHT_URL env var)
"""

from flask import Flask, request, jsonify
import requests
import json
import re
import os

app = Flask(__name__)

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434/api/generate")
MODEL_NAME = os.environ.get("OLLAMA_MODEL","qwen2.5-coder:1.5b")

# Fields the Java servlet expects back, in this exact shape, every time.
RESPONSE_FIELDS = [
    "explanation", "errorAnalysis", "errorFix", "concepts",
    "timeComplex", "spaceComplex", "complexity", "suggestions", "optimizedCode"
]


def empty_result():
    return {field: "" for field in RESPONSE_FIELDS}


def build_prompt(code: str, problem: str, verdict: str) -> str:
    has_error = bool(verdict) and any(
        kw in verdict for kw in ("Error", "TLE", "Wrong")
    )

    error_section = ""
    if has_error:
        error_section = (
            f'IMPORTANT: The code has a verdict of "{verdict}". You MUST:\n'
            '1. In "errorAnalysis": Clearly explain WHY this error is happening in simple words.\n'
            '   If it is a Compilation Error - explain the syntax mistake.\n'
            '   If it is a Runtime Error - explain what caused the crash (null pointer, array out of bounds, etc.).\n'
            '   If it is TLE - explain why the code is too slow and what approach to use.\n'
            '   If it is Wrong Answer - explain why the output does not match what was expected.\n'
            '2. In "errorFix": Give the corrected code with comments explaining what was changed and why.\n\n'
        )

    problem_line = problem if problem else "Not specified"
    verdict_line = verdict if verdict else "Not submitted yet"

    return (
        "You are an experienced Java code reviewer helping a learner. Be accurate and specific to\n"
        "the ACTUAL code below - never give generic or boilerplate answers that could apply to any\n"
        "code. Different fields need different styles, described exactly below:\n\n"
        f"Problem: {problem_line}\n"
        f"Verdict: {verdict_line}\n\n"
        f"{error_section}"
        f"Code to analyze:\n{code}\n\n"
        "Respond with ONLY this JSON (no markdown, no extra text, no ```json fences).\n"
        "Field-by-field instructions:\n"
        "{\n"
        '  "explanation": beginner-friendly, plain-English walkthrough of what THIS code does,\n'
        '     step by step, using simple words and a real-life analogy if it helps.\n'
        '  "errorAnalysis": leave "" if there is no error; otherwise see instructions above.\n'
        '  "errorFix": leave "" if there is no error; otherwise see instructions above.\n'
        '  "concepts": list (comma-separated) the specific Java concepts/APIs THIS code uses\n'
        '     (e.g. "HashMap, recursion, ArrayList") - not a generic CS glossary.\n'
        '  "timeComplex": ONLY the Big-O notation, e.g. "O(n log n)". No explanation here.\n'
        '  "spaceComplex": ONLY the Big-O notation, e.g. "O(n)". No explanation here.\n'
        '  "complexity": 1-2 plain-English sentences explaining WHY the time/space complexity\n'
        '     above is what it is, referencing the actual loops/data structures in the code.\n'
        '  "suggestions": 2-3 concrete, specific improvements for THIS code (not generic advice\n'
        '     like "add comments"), each as a short bullet-style sentence.\n'
        '  "optimizedCode": a complete, valid, compilable Java version of the code with the\n'
        "     improvements applied. Escape it correctly as a single JSON string (use \\n for\n"
        "     newlines, escape any double quotes). If the code is already optimal, return it\n"
        "     unchanged.\n"
        "}"
    )


def extract_json(text: str) -> dict:
    """Strip markdown fences / stray text and parse the first {...} block."""
    cleaned = re.sub(r"```json\s*", "", text)
    cleaned = re.sub(r"```", "", cleaned).strip()

    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end == -1 or end <= start:
        raise ValueError(f"No JSON object found in model output: {cleaned[:200]}")

    candidate = cleaned[start:end + 1]
    return json.loads(candidate)


def call_ollama(prompt: str) -> str:
    payload = {
        "model": MODEL_NAME,
        "prompt": prompt,
        "stream": False,
        "format": "json",
        "options": {
            "temperature": 0.3,
            "num_predict": 1024
        }
    }
    resp = requests.post(OLLAMA_URL, json=payload, timeout=120)
    resp.raise_for_status()
    body = resp.json()
    return body.get("response", "")


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "model": MODEL_NAME})


@app.route("/insight", methods=["POST"])
def insight():
    body = request.get_json(silent=True) or {}
    code = (body.get("code") or "").strip()
    problem = (body.get("problem") or "").strip()
    verdict = (body.get("verdict") or "").strip()

    if not code:
        return jsonify({"success": False, "message": "Code is required."}), 400

    prompt = build_prompt(code, problem, verdict)

    try:
        raw = call_ollama(prompt)
    except requests.exceptions.ConnectionError:
        return jsonify({
            "success": False,
            "message": f"Cannot reach Ollama at {OLLAMA_URL}. "
                        f"Make sure Ollama is running (try: ollama serve) "
                        f"and that you've pulled the model (ollama pull {MODEL_NAME})."
        }), 503
    except requests.exceptions.Timeout:
        return jsonify({
            "success": False,
            "message": "The local model took too long to respond. "
                        "Try a smaller model or a shorter code snippet."
        }), 504
    except Exception as e:
        return jsonify({"success": False, "message": f"Ollama request failed: {e}"}), 502

    try:
        parsed = extract_json(raw)
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"Model did not return valid JSON: {e}"
        }), 502

    result = empty_result()
    for field in RESPONSE_FIELDS:
        result[field] = parsed.get(field, "")

    result["success"] = True
    return jsonify(result)

@app.route("/analyze", methods=["POST"])
def analyze():
    body = request.get_json(silent=True) or {}
    code = (body.get("code") or "").strip()
    problem = (body.get("problem") or "").strip()
    verdict = (body.get("verdict") or "").strip()

    if not code:
        return jsonify({"success": False, "message": "Code is required."}), 400

    prompt = build_prompt(code, problem, verdict)

    try:
        raw = call_ollama(prompt)
    except requests.exceptions.ConnectionError:
        return jsonify({
            "success": False,
            "message": f"Cannot reach Ollama at {OLLAMA_URL}. "
                        f"Make sure Ollama is running (try: ollama serve) "
                        f"and that you've pulled the model (ollama pull {MODEL_NAME})."
        }), 503
    except requests.exceptions.Timeout:
        return jsonify({
            "success": False,
            "message": "The local model took too long to respond. "
                        "Try a smaller model or a shorter code snippet."
        }), 504
    except Exception as e:
        return jsonify({"success": False, "message": f"Ollama request failed: {e}"}), 502

    try:
        parsed = extract_json(raw)
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"Model did not return valid JSON: {e}"
        }), 502

    result = empty_result()
    for field in RESPONSE_FIELDS:
        result[field] = parsed.get(field, "")
    result["success"] = True
    return jsonify(result)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8001)
