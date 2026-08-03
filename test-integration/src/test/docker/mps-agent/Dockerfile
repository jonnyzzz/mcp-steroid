ARG BASE_IMAGE=mcp-steroid-ide-base-test
FROM ${BASE_IMAGE}

USER root

# Copy and extract MPS archive (pre-downloaded by Gradle)
COPY ide.tar.gz /tmp/ide.tar.gz
RUN mkdir -p /opt/idea && chmod a+rwx /opt && \
    tar -xzf /tmp/ide.tar.gz -C /opt/idea --strip-components=1 && \
    rm /tmp/ide.tar.gz

USER agent
WORKDIR /home/agent

ENV PATH="/home/agent/.local/bin:/opt/idea/bin:/usr/local/bin:/usr/bin:$PATH"
ENV SHELL=/bin/bash
ENV HOME=/home/agent

CMD ["sleep", "infinity"]
