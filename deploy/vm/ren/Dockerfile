# ren desktop's backend for the phone, as ren desktop runs it (its deploy/systemd/ron-api.service runs
# `python agent/api/main.py`): ren's own code (github.com/Oubli-AI/ren, was ImRonAI/TheBrowser) at one commit, unmodified, with its pinned
# Strands source and requirements. compose.yaml sets what ren's deploy/ron.env.example leaves to the host.
FROM python:3.14-slim-bookworm
ARG REN_COMMIT=0f6fa57b1b3896f19cb873aa713833baba42a351
RUN apt-get update && apt-get install -y --no-install-recommends git build-essential socat && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/ren
RUN git clone --filter=blob:none --no-checkout https://github.com/Oubli-AI/ren.git . \
    && git sparse-checkout set agent deploy \
    && git checkout --detach "$REN_COMMIT"
# The second install is ren's own pin for the MCP packages, then electron-playwright-mcp as ren installs it
# (deploy/vps-install.sh): ren's MCP server (agent/api/core/ren_mcp.py) builds its browser tools from it at startup.
# That package has no remote; the VM's build context holds a copy of its pyproject.toml, README.md and
# src/electron_playwright_mcp from the owner's checkout (see AGENTS.md).
COPY electron-playwright-mcp /opt/electron-playwright-mcp
RUN bash deploy/prepare-strands.sh && pip install --no-cache-dir -r agent/api/requirements.txt \
    && pip install --no-cache-dir 'fastmcp[apps]==3.4.0' 'mcp==1.26.0' 'pyyaml>=6.0.3' 'httpx>=0.28.1' \
    && pip install --no-cache-dir --ignore-requires-python --no-deps /opt/electron-playwright-mcp && pip check
# Deep research prints its report with Playwright's Chromium (playwright is in ren's requirements); without it ren
# writes a plain PDF. Its slides need Slidev on Node, which isn't here, so a run has no deck.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
RUN python -m playwright install --with-deps chromium
RUN useradd --create-home --uid 1000 ren && install -d -o ren -g ren /home/ren/data
USER 1000:1000
ENV PYTHONPATH=/opt/ren PYTHONUNBUFFERED=1 API_HOST=0.0.0.0 API_PORT=8765
EXPOSE 8765 8766
# ren desktop serves deep research to loopback only (agent/api/endpoints/deep_research.py), as the desktop app is its
# one caller. On the VM that caller is the app's Research page behind Caddy's token, so Caddy sends /deep-research to
# 8766, which hands the connection to the backend from inside the container, where it arrives from 127.0.0.1.
CMD ["sh", "-c", "socat TCP-LISTEN:8766,fork,reuseaddr TCP:127.0.0.1:8765 & exec python agent/api/main.py"]
