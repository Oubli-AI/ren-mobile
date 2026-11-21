"""Tests for `cli_auth.py`: browser-driven OAuth sign-in for Claude Code and Antigravity CLI."""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path
from typing import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import cli_auth


def _write_executable(path: Path, body: str) -> str:
    path.write_text(body, encoding="utf-8")
    path.chmod(stat.S_IRWXU)
    return str(path)


@pytest.fixture
def fake_clis(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[Path]:
    state_file = tmp_path / "auth_state.json"
    state_file.write_text(json.dumps({"claude": False, "agy": False}), encoding="utf-8")

    fake_claude = _write_executable(
        tmp_path / "claude",
        f"""#!/usr/bin/env python3
import json, sys, pathlib
state_path = pathlib.Path({str(state_file)!r})
state = json.loads(state_path.read_text())
args = sys.argv[1:]
if args[:2] == ["auth", "status"]:
    if state["claude"]:
        print(json.dumps({{"loggedIn": True, "authMethod": "claude.ai", "email": "tim@oubliai.com"}}))
        sys.exit(0)
    print(json.dumps({{"loggedIn": False, "authMethod": "none"}}))
    sys.exit(1)
if args[:2] == ["auth", "login"]:
    print("Opening browser to sign in...")
    print("If the browser didn't open, visit: https://claude.com/cai/oauth/authorize?code=true&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&state=st123", flush=True)
    sys.stdout.write("Paste code here if prompted > ")
    sys.stdout.flush()
    line = sys.stdin.readline().strip()
    if line == "goodcode#st123":
        state["claude"] = True
        state_path.write_text(json.dumps(state))
        print("Logged in as tim@oubliai.com", flush=True)
        sys.exit(0)
    print("Login failed: That code wasn't accepted.", flush=True)
    # Match real claude CLI staying open after a failed paste.
    sys.stdin.readline()
    sys.exit(1)
if args[:2] == ["auth", "logout"]:
    state["claude"] = False
    state_path.write_text(json.dumps(state))
    sys.exit(0)
sys.exit(2)
""",
    )

    fake_agy = _write_executable(
        tmp_path / "agy",
        f"""#!/usr/bin/env python3
import json, sys, pathlib
state_path = pathlib.Path({str(state_file)!r})
state = json.loads(state_path.read_text())
args = sys.argv[1:]
if args == ["acp", "--login"]:
    print("Open this link in a browser to log in with Google:\\n", flush=True)
    print("  https://accounts.google.com/o/oauth2/auth?redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&state=agystate", flush=True)
    print("\\nThen paste the authorization code, or the full URL of the page you land on, and press Enter:", flush=True)
    line = sys.stdin.readline().strip()
    if "code=goodagy" in line and "state=agystate" in line:
        state["agy"] = True
        state_path.write_text(json.dumps(state))
        print("Logged in!", flush=True)
        sys.exit(0)
    print("couldn't log in: token exchange failed: invalid_grant", flush=True)
    sys.exit(1)
if args == ["acp"]:
    for raw in sys.stdin:
        msg = json.loads(raw)
        method = msg.get("method")
        mid = msg.get("id")
        if method == "initialize":
            print(json.dumps({{"jsonrpc": "2.0", "id": mid, "result": {{"protocolVersion": 1}}}}), flush=True)
        elif method == "session/new":
            if state["agy"]:
                print(json.dumps({{"jsonrpc": "2.0", "id": mid, "result": {{"sessionId": "s1"}}}}), flush=True)
            else:
                print(json.dumps({{"jsonrpc": "2.0", "id": mid, "error": {{"code": -32000, "message": "Authentication required"}}}}), flush=True)
        elif method == "logout":
            state["agy"] = False
            state_path.write_text(json.dumps(state))
            print(json.dumps({{"jsonrpc": "2.0", "id": mid, "result": {{}}}}), flush=True)
    sys.exit(0)
sys.exit(2)
""",
    )

    monkeypatch.setattr(cli_auth, "claude_binary", lambda: fake_claude)
    monkeypatch.setattr(cli_auth, "agy_binary", lambda: fake_agy)
    monkeypatch.setattr(cli_auth, "_google_account_email", lambda: "tim@cato-labs.com")
    monkeypatch.setattr("cli_models.HOME", tmp_path / "cli_home")
    yield tmp_path
    cli_auth._pending.clear()


def test_normalize_auth_input() -> None:
    assert (
        cli_auth.normalize_auth_input(
            "claude-code",
            "https://platform.claude.com/oauth/code/callback?code=abc123&state=xyz789",
        )
        == "abc123#xyz789"
    )
    assert cli_auth.normalize_auth_input("claude-code", "abc123#xyz789") == "abc123#xyz789"
    agy_url = "https://antigravity.google/oauth-callback?state=st1&code=4/0Acode"
    assert cli_auth.normalize_auth_input("antigravity", agy_url) == agy_url
    assert cli_auth.normalize_auth_input("antigravity", "4/0Acode") == "4/0Acode"
    with pytest.raises(ValueError, match="refused"):
        cli_auth.normalize_auth_input(
            "antigravity",
            "https://antigravity.google/oauth-callback?error=access_denied",
        )
    with pytest.raises(ValueError, match="does not include an authorization code"):
        cli_auth.normalize_auth_input("claude-code", "https://platform.claude.com/oauth/code/callback?state=only")


def test_claude_sign_in_and_logout_flow(fake_clis: Path) -> None:
    app = FastAPI()
    app.include_router(cli_auth.router)
    with TestClient(app) as client:
        status = client.get("/gemini/cli-auth/status", params={"provider": "claude-code"}).json()
        assert status["signed_in"] is False

        started = client.post("/gemini/cli-auth/start", json={"provider": "claude-code"}).json()
        assert started["url"].startswith("https://claude.com/cai/oauth/authorize")
        assert started["callback_prefix"] == "https://platform.claude.com/oauth/code/callback"

        done = client.post(
            "/gemini/cli-auth/complete",
            json={
                "session_id": started["session_id"],
                "input": "https://platform.claude.com/oauth/code/callback?code=goodcode&state=st123",
            },
        )
        assert done.status_code == 200
        assert done.json()["signed_in"] is True
        assert done.json()["account"] == "tim@oubliai.com"

        logged_out = client.post("/gemini/cli-auth/logout", json={"provider": "claude-code"}).json()
        assert logged_out["signed_in"] is False


def test_claude_bad_code_returns_400_without_hanging(fake_clis: Path) -> None:
    app = FastAPI()
    app.include_router(cli_auth.router)
    with TestClient(app) as client:
        started = client.post("/gemini/cli-auth/start", json={"provider": "claude-code"}).json()
        bad = client.post(
            "/gemini/cli-auth/complete",
            json={
                "session_id": started["session_id"],
                "input": "https://platform.claude.com/oauth/code/callback?code=bad&state=st123",
            },
        )
        assert bad.status_code == 400
        assert "wasn't accepted" in bad.json()["detail"]


def test_antigravity_sign_in_cancel_and_logout_flow(fake_clis: Path) -> None:
    app = FastAPI()
    app.include_router(cli_auth.router)
    with TestClient(app) as client:
        assert client.get("/gemini/cli-auth/status", params={"provider": "antigravity"}).json()["signed_in"] is False

        first = client.post("/gemini/cli-auth/start", json={"provider": "antigravity"}).json()
        assert client.post("/gemini/cli-auth/cancel", json={"session_id": first["session_id"]}).status_code == 200

        started = client.post("/gemini/cli-auth/start", json={"provider": "antigravity"}).json()
        assert started["url"].startswith("https://accounts.google.com/o/oauth2/auth")
        assert started["callback_prefix"] == "https://antigravity.google/oauth-callback"

        done = client.post(
            "/gemini/cli-auth/complete",
            json={
                "session_id": started["session_id"],
                "input": "https://antigravity.google/oauth-callback?state=agystate&code=goodagy",
            },
        )
        assert done.status_code == 200
        assert done.json()["signed_in"] is True
        assert done.json()["account"] == "tim@cato-labs.com"

        logged_out = client.post("/gemini/cli-auth/logout", json={"provider": "antigravity"}).json()
        assert logged_out["signed_in"] is False
