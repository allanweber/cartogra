FROM node:24.10.0-alpine3.22 AS builder
WORKDIR /build
RUN npm install -g pnpm@12.3.4
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile --prod
COPY app ./app

FROM node:24.10.0-alpine3.22 AS runtime
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
    /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
WORKDIR /srv
COPY --from=builder --chown=node:node /build/node_modules ./node_modules
COPY --from=builder --chown=node:node /build/package.json ./package.json
COPY --from=builder --chown=node:node /build/app ./app
USER node
ENV NODE_ENV=production
EXPOSE 8080
HEALTHCHECK CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["sh", "-c", "exec node app/server.ts"]
