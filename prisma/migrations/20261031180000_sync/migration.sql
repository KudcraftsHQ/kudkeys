-- CreateTable
CREATE TABLE "keyboard_settings" (
    "userId" TEXT NOT NULL,
    "prefs" JSONB NOT NULL,
    "keyTimes" JSONB NOT NULL DEFAULT '{}',
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "deviceId" TEXT NOT NULL,
    "syncedAt" TIMESTAMP(3) NOT NULL,
    "version" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "keyboard_settings_pkey" PRIMARY KEY ("userId")
);

-- CreateTable
CREATE TABLE "word" (
    "userId" TEXT NOT NULL,
    "word" TEXT NOT NULL,
    "locale" TEXT NOT NULL,
    "count" INTEGER NOT NULL,
    "source" TEXT NOT NULL,
    "deleted" BOOLEAN NOT NULL DEFAULT false,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "changedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "word_pkey" PRIMARY KEY ("userId","word","locale")
);

-- CreateTable
CREATE TABLE "backup" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL,
    "deviceId" TEXT NOT NULL,
    "deviceName" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "sizeBytes" INTEGER NOT NULL,
    "sha256" TEXT NOT NULL,
    "data" BYTEA NOT NULL,

    CONSTRAINT "backup_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "word_userId_changedAt_idx" ON "word"("userId", "changedAt");

-- CreateIndex
CREATE INDEX "backup_userId_createdAt_idx" ON "backup"("userId", "createdAt");

-- AddForeignKey
ALTER TABLE "keyboard_settings" ADD CONSTRAINT "keyboard_settings_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "word" ADD CONSTRAINT "word_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "backup" ADD CONSTRAINT "backup_userId_fkey" FOREIGN KEY ("userId") REFERENCES "kudkeys_user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

