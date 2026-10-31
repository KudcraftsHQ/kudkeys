# Kud Keys — one container: Hono serves /api/*, the built web app and the
# /v1/stream voice WebSocket. Vite is build-time only. Mirrors board's Dockerfile.
#
# Build:    docker build -t kudkeys .
# Run:      docker run -p 8080:8080 --env-file .env kudkeys
#
# Coolify: dockerfile build pack, base directory /, port 8080, healthcheck
# GET /api/health (no database call; /health is an alias).

# ───── Stage 1: install root + workspace deps ──────────────────────────────
FROM oven/bun:1.4 AS deps
WORKDIR /app
COPY package.json bun.lock ./
COPY app/package.json app/package.json
COPY server/package.json server/package.json
COPY prisma prisma
RUN bun install --frozen-lockfile

# ───── Stage 2: build the SPA into server/public ───────────────────────────
FROM deps AS app-build
WORKDIR /app
COPY app app
COPY server server
RUN cd app && bun run build

# ───── Stage 3: runtime ────────────────────────────────────────────────────
FROM oven/bun:1.4 AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8080

# curl: Coolify's container healthcheck; openssl: Prisma's query engine.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openssl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=deps /app/node_modules /app/node_modules
COPY --from=deps /app/package.json /app/package.json
COPY --from=deps /app/bun.lock /app/bun.lock
COPY --from=deps /app/prisma /app/prisma
# Every workspace in package.json must exist or bun refuses to run anything.
COPY --from=deps /app/app/package.json /app/app/package.json
COPY server /app/server
COPY --from=app-build /app/server/public /app/server/public
# Workspace symlinks live under server/node_modules; copy after the source.
COPY --from=deps /app/server/node_modules /app/server/node_modules

RUN DATABASE_URL_POOLING=postgresql://build DATABASE_URL=postgresql://build bunx prisma generate

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD curl -fsS http://localhost:8080/api/health || exit 1
# Apply pending migrations, then start. DATABASE_URL_POOLING falls back to
# DATABASE_URL when unset. exec so the server gets SIGTERM from `docker stop`.
CMD ["sh", "-c", "export DATABASE_URL_POOLING=\"${DATABASE_URL_POOLING:-$DATABASE_URL}\" && bunx prisma migrate deploy && exec bun run server/index.ts"]
