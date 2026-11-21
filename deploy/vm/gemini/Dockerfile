# The Strands agents (Claude Code, Antigravity CLI, Gemini, NVIDIA NIM, Mistral, Telnyx, xAI) run on the computer image, so the harness's shell, files and tools are the computer's, in its home.
FROM emrys-computer:0.3.1
USER root
RUN UV_PYTHON_INSTALL_DIR=/opt/python uv venv /opt/gemini --python 3.12 \
    && uv pip install --python /opt/gemini/bin/python "strands-agents[bidi,bidi-google,gemini,mistral,openai,web-fetch]==1.64.0" \
        strands-harness==0.4.0 mistralai==2.10.1 openai==2.50.0 strands-xai==0.6.1 xai-sdk==1.19.0 strands-agents-tools==0.8.12 google-genai==2.34.0 fastapi==0.162.0 uvicorn==0.54.0 elevenlabs==2.75.0 mcp==2.3.0 claude-agent-sdk==0.2.187
# ren desktop's web tools and memory, as ren's own agents use them: ren's backend source at one commit, unmodified
# (agent/api/core/web_tools.py, brave_tools.py, xai_model.py, dreaming.py and what they import), with the one package of
# ren's that the image lacks and huggingface_hub (ren's pin) for its bucket storage; strands-agents-tools above vends the
# Tavily, Exa and Bright Data tools web_tools() gives.
ARG REN_COMMIT=760df191d1ffe9eaae7f65d6dd18ce972be124ac
RUN git clone --filter=blob:none --no-checkout https://github.com/Oubli-AI/ren.git /opt/ren \
    && git -C /opt/ren sparse-checkout set agent \
    && git -C /opt/ren checkout --detach "$REN_COMMIT" \
    && uv pip install --python /opt/gemini/bin/python python-dotenv==1.2.1 huggingface_hub==1.20.1
# ren's config makes its session folder when it is imported; it goes in the agents' home on the shared volume. The
# second brain and the journals are in ren's own storage, which compose.yaml sets as the `ren` service's.
ENV PYTHONPATH=/opt/ren SESSION_STORAGE_DIR=/config/emrys-gemini/ren
# Antigravity CLI, the text agent `cli_models.py` drives beside Claude Code (which claude-agent-sdk above ships): its
# 1.5.13 Linux release, one binary, `antigravity`, installed as `agy`, checksum-checked. It signs in with the owner's
# Google account, kept under ~/.gemini; Claude Code signs in with CLAUDE_CODE_OAUTH_TOKEN in gemini.env.
RUN curl -fsSL -o /tmp/agy.tgz https://github.com/google-antigravity/antigravity-cli/releases/download/1.5.13/agy_cli_linux_x64.tar.gz \
    && echo "7fa9cc6df0d09562693d637c406a86c1b0f52ab3364714b1fd2d79cc6060d4b2  /tmp/agy.tgz" | sha256sum -c - \
    && tar -xzf /tmp/agy.tgz -C /usr/local/bin antigravity && mv /usr/local/bin/antigravity /usr/local/bin/agy && rm /tmp/agy.tgz
COPY app.py providers.py studio.py cli_models.py cli_auth.py cli_bridge.py mistral_model.py media_capabilities.py context_windows.py /opt/gemini/
USER 1000:1000
WORKDIR /config/workspace
EXPOSE 8100
CMD ["/opt/gemini/bin/python", "/opt/gemini/app.py"]
