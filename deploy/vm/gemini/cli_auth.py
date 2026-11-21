"""Browser-driven sign-in for the CLI text agents (Claude Code and Antigravity CLI).

Both CLIs have a built-in terminal OAuth flow that prints an authorization URL and waits for the redirect's code:
- Claude Code (`_bundled/claude auth login --claudeai`): redirects to `https://platform.claude.com/oauth/code/callback`
  and reads `<code>#<state>` on its PTY.
- Antigravity CLI (`agy acp --login`): redirects to `https://antigravity.google/oauth-callback` and reads either the
  full callback URL or the authorization code on its PTY.

The phone opens the authorization URL in a Ren browser tab, intercepts the callback redirect automatically, and posts
it back to `/gemini/cli-auth/complete`, so signing in takes one tap with no copy-paste or shell access.
"""

from __future__ import annotations

import asyncio
import contextlib
import errno
import fcntl
import json
import os
import pty
import re
import select
import shutil
import stat
import struct
import subprocess
import tempfile
import termios
import time
import urllib.parse
import uuid
from dataclasses import dataclass, field
from pathlib import Path

import claude_agent_sdk
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from cli_models import CliName, Session, acp_request, acp_session

router = APIRouter(prefix="/gemini/cli-auth")

#: How long an unfinished browser sign-in stays open before its CLI process is reaped.
LOGIN_TTL_SECONDS = 600.0
#: How long `/start` waits for the CLI to print its OAuth URL and prompt.
START_TIMEOUT_SECONDS = 20.0
#: How long `/complete` waits for the CLI to exchange the code and save credentials.
COMPLETE_TIMEOUT_SECONDS = 30.0
#: Maximum status check duration before failing fast.
STATUS_TIMEOUT_SECONDS = 15.0

DEFAULT_CALLBACK_PREFIX: dict[CliName, str] = {
    "claude-code": "https://platform.claude.com/oauth/code/callback",
    "antigravity": "https://antigravity.google/oauth-callback",
}

ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")
URL_PATTERNS: dict[CliName, re.Pattern[str]] = {
    "claude-code": re.compile(r"https://(?:claude\.com|claude\.ai)/[^\s\"'<>]+"),
    "antigravity": re.compile(r"https://accounts\.google\.com/[^\s\"'<>]+"),
}
PROMPT_MARKERS: dict[CliName, tuple[str, ...]] = {
    "claude-code": ("paste code", "code here"),
    "antigravity": ("press enter", "authorization code"),
}
CLAUDE_FAILURE_MARKERS = ("login failed:", "invalid code")
AGY_FAILURE_MARKERS = ("couldn't log in:", "failed to log in")


class CliAuthStatus(BaseModel):
    """Whether a CLI provider is currently signed in on this workspace server."""

    provider: CliName
    signed_in: bool
    account: str = ""
    method: str = ""
    pending_session_id: str | None = None
    pending_url: str | None = None
    callback_prefix: str = ""


class CliAuthStartRequest(BaseModel):
    provider: CliName


class CliAuthStart(BaseModel):
    """A live CLI OAuth login waiting for its callback URL or authorization code."""

    session_id: str
    provider: CliName
    url: str
    callback_prefix: str


class CliAuthCompleteRequest(BaseModel):
    session_id: str
    input: str


class CliAuthCancelRequest(BaseModel):
    session_id: str


class CliAuthLogoutRequest(BaseModel):
    provider: CliName


@dataclass
class PendingLogin:
    """One active CLI login subprocess waiting on its PTY."""

    session_id: str
    provider: CliName
    url: str
    callback_prefix: str
    proc: subprocess.Popen[bytes]
    master_fd: int
    temp_dir: Path
    created_at: float = field(default_factory=time.monotonic)
    closed: bool = False

    async def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        with contextlib.suppress(OSError):
            os.close(self.master_fd)
        if self.proc.poll() is None:
            with contextlib.suppress(ProcessLookupError, OSError):
                self.proc.terminate()
            try:
                await asyncio.to_thread(self.proc.wait, 2.0)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(ProcessLookupError, OSError):
                    self.proc.kill()
                with contextlib.suppress(Exception):
                    await asyncio.to_thread(self.proc.wait, 2.0)
        shutil.rmtree(self.temp_dir, ignore_errors=True)


_pending: dict[str, PendingLogin] = {}


def claude_binary() -> str | None:
    """The Claude Code executable shipped by `claude-agent-sdk`, or `claude` on PATH."""
    sdk_file = claude_agent_sdk.__file__
    if sdk_file:
        bundled = Path(sdk_file).parent / "_bundled" / "claude"
        if bundled.is_file():
            return str(bundled)
    return shutil.which("claude")


def agy_binary() -> str | None:
    """The Antigravity CLI executable on PATH."""
    return shutil.which("agy")


def strip_ansi(text: str) -> str:
    return ANSI_RE.sub("", text)


def callback_prefix_from_url(provider: CliName, auth_url: str) -> str:
    """Extracts the OAuth `redirect_uri` prefix from the authorize URL the CLI emitted."""
    query = urllib.parse.parse_qs(urllib.parse.urlparse(auth_url).query)
    redirect = query.get("redirect_uri", [""])[0].strip()
    if redirect.startswith("https://") or redirect.startswith("http://"):
        return redirect.split("?", 1)[0].split("#", 1)[0]
    return DEFAULT_CALLBACK_PREFIX[provider]


def normalize_auth_input(provider: CliName, raw: str) -> str:
    """Converts a browser callback URL or pasted code into what the CLI expects on stdin."""
    text = raw.strip()
    if not text:
        raise ValueError("Missing authorization code or callback URL.")
    if text.startswith(("https://", "http://")):
        parsed = urllib.parse.urlparse(text)
        params = urllib.parse.parse_qs(parsed.query)
        if parsed.fragment and "=" in parsed.fragment:
            for key, values in urllib.parse.parse_qs(parsed.fragment).items():
                params.setdefault(key, values)
        if "error" in params:
            detail = params.get("error_description", params["error"])[0]
            raise ValueError(f"Sign-in was refused: {detail}")
        code = params.get("code", [""])[0].strip()
        if not code:
            raise ValueError("The callback URL does not include an authorization code.")
        if provider == "claude-code":
            state = params.get("state", [""])[0].strip()
            return f"{code}#{state}" if state else code
        return text
    return text


def _pending_for(provider: CliName) -> PendingLogin | None:
    now = time.monotonic()
    for login in list(_pending.values()):
        if login.provider == provider and not login.closed and now - login.created_at < LOGIN_TTL_SECONDS:
            if login.proc.poll() is None:
                return login
    return None


async def _reap_expired(provider: CliName | None = None) -> None:
    now = time.monotonic()
    for session_id, login in list(_pending.items()):
        expired = now - login.created_at >= LOGIN_TTL_SECONDS or login.proc.poll() is not None
        if expired or (provider is not None and login.provider == provider):
            _pending.pop(session_id, None)
            await login.close()


def _make_no_browser_env(temp_dir: Path, strip_claude_token: bool) -> dict[str, str]:
    """Environment with no-op `open`/`xdg-open` shims so the CLI prints the URL instead of opening a browser."""
    bin_dir = temp_dir / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    for name in ("open", "xdg-open", "sensible-browser", "x-www-browser"):
        shim = bin_dir / name
        shim.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        shim.chmod(stat.S_IRWXU)
    env = dict(os.environ)
    env["PATH"] = f"{bin_dir}:{env.get('PATH', '')}"
    env["BROWSER"] = str(bin_dir / "xdg-open")
    env["TERM"] = "dumb"
    env["NO_COLOR"] = "1"
    env["COLUMNS"] = "2000"
    if strip_claude_token:
        env.pop("CLAUDE_CODE_OAUTH_TOKEN", None)
    return env


def _read_available(fd: int, timeout: float) -> bytes:
    """Reads whatever bytes are currently ready on `fd`, waiting up to `timeout` seconds."""
    ready, _, _ = select.select([fd], [], [], timeout)
    if not ready:
        return b""
    try:
        return os.read(fd, 8192)
    except OSError as error:
        if error.errno == errno.EIO:
            return b""
        raise


async def _wait_for_auth_url(provider: CliName, proc: subprocess.Popen[bytes], master_fd: int) -> str:
    """Reads PTY output until the CLI prints its OAuth URL and input prompt."""
    pattern = URL_PATTERNS[provider]
    markers = PROMPT_MARKERS[provider]
    buf = ""
    found_url: str | None = None
    deadline = time.monotonic() + START_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        remaining = max(0.05, min(0.2, deadline - time.monotonic()))
        chunk = await asyncio.to_thread(_read_available, master_fd, remaining)
        if chunk:
            buf += strip_ansi(chunk.decode("utf-8", errors="replace"))
            if found_url is None and (match := pattern.search(buf)):
                found_url = match.group(0).rstrip(".,);")
            if found_url is not None and any(marker in buf.lower() for marker in markers):
                return found_url
        if (code := proc.poll()) is not None:
            if found_url is not None:
                return found_url
            detail = _last_meaningful_line(buf) or f"exited with code {code}"
            raise RuntimeError(f"Could not start {provider} sign-in: {detail}")
    if found_url is not None:
        return found_url
    detail = _last_meaningful_line(buf) or "timed out waiting for the sign-in link"
    raise RuntimeError(f"Could not start {provider} sign-in: {detail}")


def _last_meaningful_line(output: str) -> str:
    lines = [line.strip() for line in output.replace("\r", "\n").splitlines() if line.strip()]
    return lines[-1] if lines else ""


def _extract_failure(provider: CliName, output: str) -> str | None:
    markers = CLAUDE_FAILURE_MARKERS if provider == "claude-code" else AGY_FAILURE_MARKERS
    for line in output.replace("\r", "\n").splitlines():
        cleaned = line.strip()
        lower = cleaned.lower()
        if any(marker in lower for marker in markers):
            return cleaned
    return None


def _google_account_email() -> str:
    """Best-effort Google account email from `~/.gemini/google_accounts.json`, if Antigravity wrote it."""
    path = Path.home() / ".gemini" / "google_accounts.json"
    if not path.is_file():
        return ""
    try:
        loaded: object = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return ""
    if isinstance(loaded, dict):
        active = loaded.get("active")
        if isinstance(active, str):
            return active.strip()
    return ""


async def _claude_status() -> tuple[bool, str, str]:
    binary = claude_binary()
    if not binary:
        return False, "", "missing"
    proc = await asyncio.create_subprocess_exec(
        binary, "auth", "status", "--json",
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, _ = await asyncio.wait_for(proc.communicate(), timeout=STATUS_TIMEOUT_SECONDS)
    except TimeoutError:
        proc.kill()
        await proc.wait()
        raise RuntimeError("Claude Code status check timed out.") from None
    raw = stdout.decode("utf-8", errors="replace").strip()
    if not raw:
        return False, "", ""
    try:
        loaded: object = json.loads(raw)
    except ValueError:
        return False, "", ""
    if not isinstance(loaded, dict):
        return False, "", ""
    logged_in = bool(loaded.get("loggedIn"))
    email = str(loaded.get("email") or "").strip()
    method = str(loaded.get("authMethod") or "").strip()
    account = email or ("Server token" if logged_in and method == "oauth_token" else "")
    return logged_in, account, method


async def _agy_status() -> tuple[bool, str, str]:
    binary = agy_binary()
    if not binary:
        return False, "", "missing"
    session = Session([])
    session.folder.mkdir(parents=True, exist_ok=True)
    try:
        await session.start([binary, "acp"], str(session.folder))
        await asyncio.wait_for(acp_session(session, None), timeout=STATUS_TIMEOUT_SECONDS)
        return True, _google_account_email(), "google"
    except RuntimeError as error:
        if "authentication required" in str(error).lower() or "-32000" in str(error):
            return False, "", ""
        raise
    finally:
        await session.close()


async def cli_auth_status(provider: CliName) -> CliAuthStatus:
    """Checks live sign-in status for `provider` and attaches any active pending login."""
    await _reap_expired()
    if provider == "claude-code":
        signed_in, account, method = await _claude_status()
    else:
        signed_in, account, method = await _agy_status()
    pending = _pending_for(provider)
    return CliAuthStatus(
        provider=provider,
        signed_in=signed_in,
        account=account,
        method=method,
        pending_session_id=pending.session_id if pending else None,
        pending_url=pending.url if pending else None,
        callback_prefix=pending.callback_prefix if pending else DEFAULT_CALLBACK_PREFIX[provider],
    )


async def start_cli_auth(provider: CliName) -> CliAuthStart:
    """Starts the CLI's native OAuth flow in a PTY and returns its browser sign-in URL."""
    await _reap_expired(provider=provider)
    if provider == "claude-code":
        binary = claude_binary()
        if not binary:
            raise RuntimeError("Claude Code CLI is not installed on this server.")
        command = [binary, "auth", "login", "--claudeai"]
    else:
        binary = agy_binary()
        if not binary:
            raise RuntimeError("Antigravity CLI is not installed on this server.")
        command = [binary, "acp", "--login"]

    temp_dir = Path(tempfile.mkdtemp(prefix=f"ren-auth-{provider}-"))
    env = _make_no_browser_env(temp_dir, strip_claude_token=(provider == "claude-code"))
    master_fd, slave_fd = pty.openpty()
    # Wide PTY so long OAuth authorize URLs are never line-wrapped.
    with contextlib.suppress(OSError):
        fcntl.ioctl(slave_fd, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 2000, 0, 0))

    try:
        proc = subprocess.Popen(
            command,
            stdin=slave_fd,
            stdout=slave_fd,
            stderr=slave_fd,
            env=env,
            cwd=str(temp_dir),
            start_new_session=True,
            close_fds=True,
        )
    except Exception:
        os.close(master_fd)
        os.close(slave_fd)
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise
    os.close(slave_fd)

    try:
        url = await _wait_for_auth_url(provider, proc, master_fd)
    except Exception:
        login = PendingLogin("", provider, "", "", proc, master_fd, temp_dir)
        await login.close()
        raise

    session_id = uuid.uuid4().hex
    callback_prefix = callback_prefix_from_url(provider, url)
    _pending[session_id] = PendingLogin(
        session_id=session_id,
        provider=provider,
        url=url,
        callback_prefix=callback_prefix,
        proc=proc,
        master_fd=master_fd,
        temp_dir=temp_dir,
    )
    return CliAuthStart(session_id=session_id, provider=provider, url=url, callback_prefix=callback_prefix)


async def complete_cli_auth(session_id: str, raw_input: str) -> CliAuthStatus:
    """Feeds the OAuth callback URL or code into the live CLI PTY and verifies sign-in."""
    login = _pending.pop(session_id, None)
    if login is None or login.closed:
        raise LookupError("That sign-in session expired. Tap Sign in again.")

    try:
        payload = normalize_auth_input(login.provider, raw_input)
        ending = "\r" if login.provider == "claude-code" else "\n"
        await asyncio.to_thread(os.write, login.master_fd, (payload + ending).encode("utf-8"))

        output = ""
        failure_seen_at: float | None = None
        deadline = time.monotonic() + COMPLETE_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            remaining = max(0.05, min(0.2, deadline - time.monotonic()))
            chunk = await asyncio.to_thread(_read_available, login.master_fd, remaining)
            if chunk:
                output += strip_ansi(chunk.decode("utf-8", errors="replace"))
                if failure_seen_at is None and _extract_failure(login.provider, output) is not None:
                    failure_seen_at = time.monotonic()
            if login.proc.poll() is not None:
                break
            if failure_seen_at is not None and time.monotonic() - failure_seen_at >= 1.0:
                break

        failure = _extract_failure(login.provider, output)
        exit_code = login.proc.poll()
        if exit_code is None:
            if failure:
                raise ValueError(failure)
            raise RuntimeError("Timed out finishing sign-in. Please try again.")
        if exit_code != 0:
            raise ValueError(failure or _last_meaningful_line(output) or f"Sign-in failed (exit {exit_code}).")
    finally:
        await login.close()

    status = await cli_auth_status(login.provider)
    if not status.signed_in:
        raise RuntimeError("Sign-in finished, but the CLI still reports signed out.")
    return status


async def cancel_cli_auth(session_id: str) -> None:
    login = _pending.pop(session_id, None)
    if login is not None:
        await login.close()


async def logout_cli_auth(provider: CliName) -> CliAuthStatus:
    """Signs out of `provider` on the workspace server and returns the updated status."""
    await _reap_expired(provider=provider)
    if provider == "claude-code":
        binary = claude_binary()
        if binary:
            proc = await asyncio.create_subprocess_exec(
                binary, "auth", "logout",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            )
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(proc.communicate(), timeout=STATUS_TIMEOUT_SECONDS)
    else:
        binary = agy_binary()
        if binary:
            session = Session([])
            session.folder.mkdir(parents=True, exist_ok=True)
            try:
                await session.start([binary, "acp"], str(session.folder))
                await asyncio.wait_for(
                    acp_request(session, "initialize", {"protocolVersion": 1, "clientCapabilities": {}, "clientInfo": {"name": "ren", "version": "1"}}),
                    timeout=STATUS_TIMEOUT_SECONDS,
                )
                await asyncio.wait_for(acp_request(session, "logout", {}), timeout=STATUS_TIMEOUT_SECONDS)
            finally:
                await session.close()
    return await cli_auth_status(provider)


@router.get("/status")
async def status_endpoint(provider: CliName) -> CliAuthStatus:
    try:
        return await cli_auth_status(provider)
    except Exception as error:
        raise HTTPException(502, str(error)) from error


@router.post("/start")
async def start_endpoint(body: CliAuthStartRequest) -> CliAuthStart:
    try:
        return await start_cli_auth(body.provider)
    except Exception as error:
        raise HTTPException(502, str(error)) from error


@router.post("/complete")
async def complete_endpoint(body: CliAuthCompleteRequest) -> CliAuthStatus:
    try:
        return await complete_cli_auth(body.session_id, body.input)
    except LookupError as error:
        raise HTTPException(404, str(error)) from error
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    except Exception as error:
        raise HTTPException(502, str(error)) from error


@router.post("/cancel")
async def cancel_endpoint(body: CliAuthCancelRequest) -> dict[str, bool]:
    await cancel_cli_auth(body.session_id)
    return {"ok": True}


@router.post("/logout")
async def logout_endpoint(body: CliAuthLogoutRequest) -> CliAuthStatus:
    try:
        return await logout_cli_auth(body.provider)
    except Exception as error:
        raise HTTPException(502, str(error)) from error
