FROM node:22-slim AS builder
WORKDIR /app/frontend

ENV PNPM_CONFIG_STRICT_DEP_BUILDS=false

RUN corepack enable && corepack prepare pnpm@10.33.4 --activate

COPY frontend/package.json frontend/pnpm-lock.yaml ./
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

# Phase 0 stub image for the frontend SSR runtime.
WORKDIR /app

COPY --from=builder --chown=node:node /app/frontend/.output ./.output

USER node
EXPOSE 3000
CMD ["node", ".output/server/index.mjs"]