FROM node:22-slim AS builder
WORKDIR /app/frontend

ENV PNPM_CONFIG_STRICT_DEP_BUILDS=false

RUN corepack enable && corepack prepare pnpm@11.7.0 --activate

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

# Copy only files needed to build the app and avoid host artifacts like node_modules.
COPY frontend/components.json ./components.json
COPY frontend/prettier.config.js ./prettier.config.js
COPY frontend/eslint.config.js ./eslint.config.js
COPY frontend/tsconfig.json ./tsconfig.json
COPY frontend/vite.config.ts ./vite.config.ts
COPY frontend/public ./public
COPY frontend/src ./src
RUN pnpm build

FROM node:22-slim AS runtime

WORKDIR /app/frontend

ENV PNPM_CONFIG_STRICT_DEP_BUILDS=false

# Vite's SSR build externalizes npm dependencies instead of bundling them, so
# `dist/server/server.js` still does plain `require`/`import` of packages
# like react at runtime — node_modules must be present in the image.
# Use the same workspace-root layout as the builder stage so pnpm resolves
# the workspace correctly, and install production deps only.
RUN corepack enable && corepack prepare pnpm@11.7.0 --activate

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile --prod --ignore-scripts

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runtime only ever runs `node dist/server/server.js` — strip npm/corepack/npx,
# the corepack-downloaded pnpm tarballs, and the pnpm store, to shrink the CVE
# surface. The corepack cache is the important one: it holds a full pnpm install
# whose bundled deps (tar, undici, ip-address, brace-expansion) are what Trivy
# reports against this image, even though nothing at runtime ever executes pnpm.
# Must happen after the pnpm install above, which needs corepack/npm.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
    /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
    /usr/local/bin/pnpm /usr/local/bin/pnpx /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    /root/.cache/node/corepack /root/.cache/pnpm /root/.local/share/pnpm /root/.npm \
    /home/node/.cache/node/corepack /home/node/.local/share/pnpm

COPY --from=builder --chown=node:node /app/frontend/dist ./dist

USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 CMD curl -f http://localhost:3000/ || exit 1
CMD ["node", "dist/server/server.js"]