# The Strands agents (Gemini, NVIDIA NIM, Mistral, Telnyx, xAI) run on the computer image, so the harness's shell, files and tools are the computer's, in its home.
FROM emrys-computer:0.3.1
USER root
RUN UV_PYTHON_INSTALL_DIR=/opt/python uv venv /opt/gemini --python 3.12 \
    && uv pip install --python /opt/gemini/bin/python "strands-agents[bidi,bidi-google,gemini,mistral,openai,web-fetch]==1.64.0" \
        strands-harness==0.4.0 mistralai==2.10.1 openai==2.50.0 strands-xai==0.6.1 xai-sdk==1.19.0 strands-agents-tools==0.8.12 fastapi==0.162.0 uvicorn==0.54.0 elevenlabs==2.75.0
# ren desktop's web tools, as ren's own agents use them: ren's backend source at one commit, unmodified
# (agent/api/core/web_tools.py, brave_tools.py, xai_model.py and what they import), with the one package of ren's that the
# image lacks; strands-agents-tools above vends the Tavily, Exa and Bright Data tools web_tools() gives.
ARG REN_COMMIT=9ae76d00edfd998b3063cb9cde8bfe0bbea56ead
RUN git clone --filter=blob:none --no-checkout https://github.com/Oubli-AI/ren.git /opt/ren \
    && git -C /opt/ren sparse-checkout set agent \
    && git -C /opt/ren checkout --detach "$REN_COMMIT" \
    && uv pip install --python /opt/gemini/bin/python python-dotenv==1.2.1
# ren's config makes its session folder when it is imported; it goes in the agents' home on the shared volume.
ENV PYTHONPATH=/opt/ren SESSION_STORAGE_DIR=/config/emrys-gemini/ren
COPY app.py providers.py live_model.py mistral_model.py media_capabilities.py context_windows.py /opt/gemini/
USER 1000:1000
WORKDIR /config/workspace
EXPOSE 8100
CMD ["/opt/gemini/bin/python", "/opt/gemini/app.py"]
