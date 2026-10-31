import { PrismaClient } from '@prisma/client'

// One client per process. Runtime goes through DATABASE_URL_POOLING
// (pgbouncer, ?pgbouncer=true) and falls back to the direct DATABASE_URL when
// it isn't set; see prisma/schema.prisma.
export const db = new PrismaClient({
  datasourceUrl: process.env.DATABASE_URL_POOLING || process.env.DATABASE_URL,
})
