-- CreateTable
CREATE TABLE "text_usage" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "kind" TEXT NOT NULL,
    "charsIn" INTEGER,
    "charsOut" INTEGER,
    "ms" INTEGER NOT NULL,
    "model" TEXT NOT NULL,
    "ok" BOOLEAN NOT NULL,

    CONSTRAINT "text_usage_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "text_usage_userId_createdAt_idx" ON "text_usage"("userId", "createdAt");

-- AddForeignKey
ALTER TABLE "text_usage" ADD CONSTRAINT "text_usage_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;
