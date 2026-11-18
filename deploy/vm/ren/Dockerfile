# ren desktop's backend for the phone, as ren desktop runs it (its deploy/systemd/ron-api.service runs
# `python agent/api/main.py`): ren's own code (github.com/Oubli-AI/ren, was ImRonAI/TheBrowser) at one commit, unmodified, with its pinned
# Strands source and requirements. compose.yaml sets what ren's deploy/ron.env.example leaves to the host.
FROM python:3.14-slim-bookworm
ARG REN_COMMIT=beaa580e5c0a58eeb167ae1b306801d66c2daa88
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
# writes a plain PDF.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
RUN python -m playwright install --with-deps chromium
# Its deck is a Slidev project, which its slidev skill builds and exports with pnpm on Node (@slidev/cli wants 22.22 or later); the
# Node and pnpm are the toolbox's (deploy/vm/toolbox/install.sh). The export's playwright-chromium fetches its own
# browser into PLAYWRIGHT_BROWSERS_PATH, so the agents' user owns it.
ARG NODE=26.13.0
ARG NODE_SHA256=d28a96af6c38596f6d9d97c0ee531c13d238d757742387d1928de822d959e997
RUN apt-get update && apt-get install -y --no-install-recommends curl xz-utils && rm -rf /var/lib/apt/lists/* \
    && curl -fsSLO "https://nodejs.org/dist/v$NODE/node-v$NODE-linux-x64.tar.xz" \
    && echo "$NODE_SHA256  node-v$NODE-linux-x64.tar.xz" | sha256sum -c - \
    && tar -xJf "node-v$NODE-linux-x64.tar.xz" -C /usr/local --strip-components=1 \
        --exclude CHANGELOG.md --exclude LICENSE --exclude README.md \
    && rm "node-v$NODE-linux-x64.tar.xz" && npm install -g pnpm@12.16.1 && npm cache clean --force
RUN useradd --create-home --uid 1000 ren && install -d -o ren -g ren /home/ren/data && chown -R ren:ren /opt/ms-playwright
USER 1000:1000
ENV PYTHONPATH=/opt/ren PYTHONUNBUFFERED=1 API_HOST=0.0.0.0 API_PORT=8765
EXPOSE 8765 8766
# ren desktop serves deep research to loopback only (agent/api/endpoints/deep_research.py), as the desktop app is its
# one caller. On the VM that caller is the app's Research page behind Caddy's token, so Caddy sends /deep-research to
# 8766, which hands the connection to the backend from inside the container, where it arrives from 127.0.0.1.
CMD ["sh", "-c", "socat TCP-LISTEN:8766,fork,reuseaddr TCP:127.0.0.1:8765 & exec python agent/api/main.py"]
